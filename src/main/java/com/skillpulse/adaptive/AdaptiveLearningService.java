package com.skillpulse.adaptive;

import com.skillpulse.auth.AppUser;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.practice.PracticeQuestion;
import com.skillpulse.practice.PracticeTopic;
import com.skillpulse.practice.UserPracticeAttempt;
import com.skillpulse.practice.UserPracticeAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores each learner's mastery and review state. It contains NO machine-learning maths:
 * Bayesian Knowledge Tracing, forgetting-curve decay and SM-2 scheduling are computed by the
 * Python ml-service through {@link MlServiceClient}.
 *
 * Safety rules when the ML service is unavailable:
 *  - Mastery is never overwritten with a guess. The stored value is kept, the attempt is counted and
 *    the row is flagged ml_synced = false. As soon as the service is back, the row is rebuilt from the
 *    stored attempts through /bkt/replay (see {@link #resyncPending}).
 *  - Review dates follow a fixed interval ladder.
 *  - ML calls never run inside a database transaction, and a failure here never affects the saved answer.
 */
@Service
public class AdaptiveLearningService {
    private static final Logger log = LoggerFactory.getLogger(AdaptiveLearningService.class);
    private static final double INITIAL_MASTERY = 0.20;
    private static final int[] FIXED_INTERVALS = {1, 3, 7, 14, 30};
    private static final double FORGET_THRESHOLD = 0.60;
    private static final long DECAY_CACHE_MS = 30000L;
    private static final long DECAY_MISS_CACHE_MS = 5000L;

    private static final class DecaySnapshot {
        final long expiresAt;
        final Map<Long, Double> values;

        DecaySnapshot(long expiresAt, Map<Long, Double> values) {
            this.expiresAt = expiresAt;
            this.values = values;
        }
    }

    private final UserTopicMasteryRepository mastery;
    private final UserReviewScheduleRepository reviews;
    private final UserPracticeAttemptRepository attempts;
    private final MlServiceClient ml;
    private final TransactionTemplate tx;
    private final Map<Long, DecaySnapshot> decayCache = new ConcurrentHashMap<Long, DecaySnapshot>();

    public AdaptiveLearningService(UserTopicMasteryRepository mastery, UserReviewScheduleRepository reviews,
                                   UserPracticeAttemptRepository attempts, MlServiceClient ml,
                                   PlatformTransactionManager txManager) {
        this.mastery = mastery;
        this.reviews = reviews;
        this.attempts = attempts;
        this.ml = ml;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Updates mastery and the review schedule after an answer that is ALREADY saved. Deliberately not
     * @Transactional: each step reads, calls the ML service with no transaction open, then writes in a
     * short transaction. Any failure is logged and swallowed so it can never undo the learner's answer.
     */
    public void recordAttempt(AppUser user, PracticeQuestion question, boolean correct, int seconds, Instant at) {
        try {
            updateMastery(user, question, correct, seconds, at);
        } catch (RuntimeException ex) {
            log.warn("Mastery update skipped for user {}: {}", user.getId(), ex.toString());
        }
        try {
            updateReview(user, question, correct, seconds, at);
        } catch (RuntimeException ex) {
            log.warn("Review schedule update skipped for user {}: {}", user.getId(), ex.toString());
        }
        decayCache.remove(user.getId());
    }

    /** Seeds mastery and review schedules from old answers for topics that have no state yet. */
    @Transactional
    public void initializeFromHistory(AppUser user, List<UserPracticeAttempt> history) {
        resyncPending(user); // repair rows written while the ML service was down

        Set<Long> initialized = new HashSet<Long>();
        for (UserTopicMastery m : mastery.findByUserId(user.getId())) initialized.add(m.getTopic().getId());

        List<UserPracticeAttempt> ordered = new ArrayList<UserPracticeAttempt>(history);
        Collections.reverse(ordered); // oldest first

        Map<Long, List<UserPracticeAttempt>> byTopic = new LinkedHashMap<Long, List<UserPracticeAttempt>>();
        for (UserPracticeAttempt a : ordered) {
            Long topicId = a.getQuestion().getTopic().getId();
            if (initialized.contains(topicId)) continue;
            if (!byTopic.containsKey(topicId)) byTopic.put(topicId, new ArrayList<UserPracticeAttempt>());
            byTopic.get(topicId).add(a);
        }
        if (byTopic.isEmpty()) return;

        Map<Long, List<MlServiceClient.ReplayAttempt>> replay =
                new LinkedHashMap<Long, List<MlServiceClient.ReplayAttempt>>();
        for (Map.Entry<Long, List<UserPracticeAttempt>> entry : byTopic.entrySet()) {
            replay.put(entry.getKey(), toReplayRows(entry.getValue()));
        }
        Map<Long, Double> seeded = ml.bktReplay(replay); // null when the ML service is unavailable

        for (Map.Entry<Long, List<UserPracticeAttempt>> entry : byTopic.entrySet()) {
            List<UserPracticeAttempt> topicAttempts = entry.getValue();
            UserPracticeAttempt last = topicAttempts.get(topicAttempts.size() - 1);
            int right = 0;
            for (UserPracticeAttempt a : topicAttempts) if (Boolean.TRUE.equals(a.getCorrect())) right++;

            UserTopicMastery m = new UserTopicMastery();
            m.setUser(user);
            m.setTopic(last.getQuestion().getTopic());
            Double fromMl = seeded == null ? null : seeded.get(entry.getKey());
            if (fromMl != null) {
                m.setMasteryProbability(clamp(fromMl));
            } else {
                // Temporary placeholder (plain accuracy). ml_synced=false makes it get rebuilt by BKT later.
                m.setMasteryProbability(clamp((double) right / topicAttempts.size()));
                m.setMlSynced(false);
            }
            m.setAttemptCount(topicAttempts.size());
            m.setCorrectCount(right);
            m.setLastAttemptAt(last.getAttemptedAt());
            m.setUpdatedAt(last.getAttemptedAt());
            mastery.save(m);
        }
        seedReviews(user, byTopic);
        decayCache.remove(user.getId());
    }

    /**
     * Gives old answers a real review schedule. One /review/replay call covers every question; if the ML service is
     * unavailable the fixed interval ladder is used instead (computed in memory, one save per question).
     */
    private void seedReviews(AppUser user, Map<Long, List<UserPracticeAttempt>> byTopic) {
        Map<Long, List<UserPracticeAttempt>> byQuestion = new LinkedHashMap<Long, List<UserPracticeAttempt>>();
        for (List<UserPracticeAttempt> topicAttempts : byTopic.values()) {
            for (UserPracticeAttempt a : topicAttempts) { // already oldest first
                Long questionId = a.getQuestion().getId();
                if (!byQuestion.containsKey(questionId)) byQuestion.put(questionId, new ArrayList<UserPracticeAttempt>());
                byQuestion.get(questionId).add(a);
            }
        }
        if (byQuestion.isEmpty()) return;

        Map<Long, List<MlServiceClient.ReplayAttempt>> rows = new LinkedHashMap<Long, List<MlServiceClient.ReplayAttempt>>();
        for (Map.Entry<Long, List<UserPracticeAttempt>> entry : byQuestion.entrySet()) {
            List<MlServiceClient.ReplayAttempt> list = new ArrayList<MlServiceClient.ReplayAttempt>();
            for (UserPracticeAttempt a : entry.getValue()) {
                list.add(new MlServiceClient.ReplayAttempt(Boolean.TRUE.equals(a.getCorrect()), "MEDIUM",
                        a.getTimeTakenSeconds(), 0));
            }
            rows.put(entry.getKey(), list);
        }
        Map<Long, MlServiceClient.ReviewResult> planned = ml.reviewReplay(rows); // null when unavailable

        for (Map.Entry<Long, List<UserPracticeAttempt>> entry : byQuestion.entrySet()) {
            List<UserPracticeAttempt> list = entry.getValue();
            UserPracticeAttempt last = list.get(list.size() - 1);
            UserReviewSchedule s = reviewFor(user, last.getQuestion());
            MlServiceClient.ReviewResult r = planned == null ? null : planned.get(entry.getKey());
            if (r != null) {
                s.setRepetitions(r.repetitions);
                s.setIntervalDays(r.intervalDays);
                s.setEaseFactor(r.easeFactor);
                s.setLastQuality(r.quality);
                s.setLastReviewedAt(last.getAttemptedAt());
                s.setDueAt(last.getAttemptedAt().plus(Duration.ofDays(r.intervalDays)));
                s.setUpdatedAt(last.getAttemptedAt());
            } else {
                for (UserPracticeAttempt a : list) stepFixed(s, Boolean.TRUE.equals(a.getCorrect()), a.getAttemptedAt());
            }
            reviews.save(s);
        }
    }

    @Transactional
    public void ensureColdStart(AppUser user, List<PracticeTopic> topics) {
        for (PracticeTopic topic : topics) {
            if (!mastery.findByUserIdAndTopicId(user.getId(), topic.getId()).isPresent()) {
                UserTopicMastery m = new UserTopicMastery();
                m.setUser(user);
                m.setTopic(topic);
                mastery.save(m);
            }
        }
    }

    /**
     * Rebuilds every mastery row flagged ml_synced = false from the stored attempts through the ML
     * service. Cheap no-op when nothing is pending; does nothing while the service is still down.
     */
    public void resyncPending(AppUser user) {
        final Long userId = user.getId();
        final Map<Long, List<MlServiceClient.ReplayAttempt>> replay =
                tx.execute(status -> {
                    Map<Long, List<MlServiceClient.ReplayAttempt>> out =
                            new LinkedHashMap<Long, List<MlServiceClient.ReplayAttempt>>();
                    for (UserTopicMastery m : mastery.findByUserId(userId)) {
                        if (m.isMlSynced()) continue;
                        Long topicId = m.getTopic().getId();
                        List<MlServiceClient.ReplayAttempt> rows = replayRows(userId, topicId);
                        if (!rows.isEmpty()) out.put(topicId, rows);
                    }
                    return out;
                });
        if (replay == null || replay.isEmpty()) return;

        final Map<Long, Double> seeded = ml.bktReplay(replay); // outside any transaction
        if (seeded == null || seeded.isEmpty()) return;

        tx.execute(status -> {
            for (Map.Entry<Long, Double> entry : seeded.entrySet()) {
                UserTopicMastery m = mastery.findByUserIdAndTopicId(userId, entry.getKey()).orElse(null);
                if (m == null || m.isMlSynced()) continue;
                m.setMasteryProbability(clamp(entry.getValue()));
                m.setMlSynced(true);
                m.setUpdatedAt(Instant.now());
                mastery.save(m);
            }
            return null;
        });
        decayCache.remove(userId);
        log.info("Rebuilt {} mastery row(s) for user {} after the ML service came back.", seeded.size(), userId);
    }

    private void updateMastery(AppUser user, PracticeQuestion q, boolean correct, int seconds, Instant at) {
        final Long userId = user.getId();
        final Long topicId = q.getTopic().getId();
        final UserTopicMastery current = mastery.findByUserIdAndTopicId(userId, topicId).orElse(null);
        final UserTopicMastery base = current != null ? current : new UserTopicMastery();
        final boolean pending = current != null && !current.isMlSynced();
        int gap = current == null || current.getLastAttemptAt() == null ? 0 : daysBetween(current.getLastAttemptAt(), at);
        PracticeQuestion.Difficulty d = q.getDifficulty();

        Double stepped = null;   // result of one BKT step
        Double rebuilt = null;   // result of replaying every stored attempt (already includes this one)
        if (pending) {
            final List<MlServiceClient.ReplayAttempt> rows = tx.execute(status -> replayRows(userId, topicId));
            if (rows != null && !rows.isEmpty()) {
                Map<Long, List<MlServiceClient.ReplayAttempt>> request =
                        new LinkedHashMap<Long, List<MlServiceClient.ReplayAttempt>>();
                request.put(topicId, rows);
                Map<Long, Double> out = ml.bktReplay(request);
                rebuilt = out == null ? null : out.get(topicId);
            }
        } else {
            stepped = ml.bktUpdate(base.getMasteryProbability(), correct, d == null ? "MEDIUM" : d.name(), seconds, gap,
                    INITIAL_MASTERY, base.getLearnProbability(), base.getGuessProbability(), base.getSlipProbability());
        }

        try {
            writeMastery(user, q, correct, at, stepped, rebuilt);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
            // Two submits for the same topic raced (double click): retry once on the fresh row.
            log.info("Concurrent mastery update for user {} topic {}; retrying once.", userId, topicId);
            writeMastery(user, q, correct, at, stepped, rebuilt);
        }
    }

    private void writeMastery(final AppUser user, final PracticeQuestion q, final boolean correct, final Instant at,
                              final Double fStepped, final Double fRebuilt) {
        final Long userId = user.getId();
        final Long topicId = q.getTopic().getId();
        tx.execute(status -> {
            UserTopicMastery m = mastery.findByUserIdAndTopicId(userId, topicId).orElseGet(() -> {
                UserTopicMastery x = new UserTopicMastery();
                x.setUser(user);
                x.setTopic(q.getTopic());
                return x;
            });
            if (fRebuilt != null) {
                m.setMasteryProbability(clamp(fRebuilt));
                m.setMlSynced(true);
            } else if (fStepped != null) {
                m.setMasteryProbability(clamp(fStepped));
            } else {
                // ML unavailable: keep the stored mastery, only count the attempt, and mark it for a rebuild.
                m.setMlSynced(false);
            }
            m.setAttemptCount(m.getAttemptCount() + 1);
            m.setCorrectCount(m.getCorrectCount() + (correct ? 1 : 0));
            m.setLastAttemptAt(at);
            m.setUpdatedAt(at);
            mastery.save(m);
            return null;
        });
    }

    private void updateReview(AppUser user, PracticeQuestion q, boolean correct, int seconds, Instant at) {
        final Long userId = user.getId();
        final Long questionId = q.getId();
        UserReviewSchedule existing = reviews.findByUserIdAndQuestionId(userId, questionId).orElse(null);
        UserReviewSchedule base = existing != null ? existing : new UserReviewSchedule();
        final MlServiceClient.ReviewResult r = ml.reviewSchedule(base.getRepetitions(), base.getIntervalDays(),
                base.getEaseFactor(), correct, seconds);

        tx.execute(status -> {
            UserReviewSchedule s = reviewFor(user, q);
            if (r == null) {
                applyFixedInterval(s, correct, at);
                return null;
            }
            s.setRepetitions(r.repetitions);
            s.setIntervalDays(r.intervalDays);
            s.setEaseFactor(r.easeFactor);
            s.setLastQuality(r.quality);
            s.setLastReviewedAt(at);
            s.setDueAt(at.plus(Duration.ofDays(r.intervalDays)));
            s.setUpdatedAt(at);
            reviews.save(s);
            return null;
        });
    }

    private UserReviewSchedule reviewFor(AppUser user, PracticeQuestion q) {
        return reviews.findByUserIdAndQuestionId(user.getId(), q.getId()).orElseGet(() -> {
            UserReviewSchedule x = new UserReviewSchedule();
            x.setUser(user);
            x.setQuestion(q);
            return x;
        });
    }

    /** Non-ML safety net: a wrong answer returns tomorrow, a right one climbs a fixed ladder. */
    private void applyFixedInterval(UserReviewSchedule s, boolean correct, Instant at) {
        stepFixed(s, correct, at);
        reviews.save(s);
    }

    /** One step of the fixed ladder, applied in memory only (the caller saves). */
    private void stepFixed(UserReviewSchedule s, boolean correct, Instant at) {
        int repetitions = correct ? s.getRepetitions() + 1 : 0;
        int interval = correct ? FIXED_INTERVALS[Math.min(repetitions, FIXED_INTERVALS.length) - 1] : 1;
        s.setRepetitions(repetitions);
        s.setIntervalDays(interval);
        s.setLastQuality(correct ? 4 : 2);
        s.setLastReviewedAt(at);
        s.setDueAt(at.plus(Duration.ofDays(interval)));
        s.setUpdatedAt(at);
    }

    /** Stored attempts of one topic as plain replay rows, oldest first. Call inside a transaction. */
    private List<MlServiceClient.ReplayAttempt> replayRows(Long userId, Long topicId) {
        List<UserPracticeAttempt> list = new ArrayList<UserPracticeAttempt>(
                attempts.findByUserIdAndQuestionTopicIdOrderByAttemptedAtDesc(userId, topicId));
        Collections.reverse(list);
        return toReplayRows(list);
    }

    private List<MlServiceClient.ReplayAttempt> toReplayRows(List<UserPracticeAttempt> oldestFirst) {
        List<MlServiceClient.ReplayAttempt> rows = new ArrayList<MlServiceClient.ReplayAttempt>();
        Instant previous = null;
        for (UserPracticeAttempt a : oldestFirst) {
            int gap = previous == null ? 0 : daysBetween(previous, a.getAttemptedAt());
            PracticeQuestion.Difficulty d = a.getQuestion().getDifficulty();
            rows.add(new MlServiceClient.ReplayAttempt(Boolean.TRUE.equals(a.getCorrect()),
                    d == null ? "MEDIUM" : d.name(), a.getTimeTakenSeconds(), gap));
            previous = a.getAttemptedAt();
        }
        return rows;
    }

    /**
     * Mastery after forgetting, as computed by the ML service. All of a learner's topics are decayed in
     * ONE call and kept for {@value #DECAY_CACHE_MS} ms (cleared whenever the learner answers), so module
     * locks and dashboards do not cost one HTTP call per topic. Falls back to the stored value.
     */
    public double effectiveMastery(UserTopicMastery m) {
        Long userId = m.getUser().getId();
        long now = System.currentTimeMillis();
        DecaySnapshot snapshot = decayCache.get(userId);
        if (snapshot == null || snapshot.expiresAt < now) snapshot = loadDecay(userId, now);
        Double value = snapshot.values.get(m.getTopic().getId());
        return value != null ? value : m.getMasteryProbability();
    }

    private DecaySnapshot loadDecay(Long userId, long now) {
        List<MlServiceClient.DecayInput> inputs = new ArrayList<MlServiceClient.DecayInput>();
        Instant clock = Instant.now();
        for (UserTopicMastery row : mastery.findByUserId(userId)) {
            int gap = row.getLastAttemptAt() == null ? 0 : daysBetween(row.getLastAttemptAt(), clock);
            inputs.add(new MlServiceClient.DecayInput(row.getTopic().getId(), row.getMasteryProbability(), gap,
                    FORGET_THRESHOLD));
        }
        Map<Long, Double> values = ml.effectiveMasteries(inputs);
        DecaySnapshot snapshot = values == null
                ? new DecaySnapshot(now + DECAY_MISS_CACHE_MS, Collections.<Long, Double>emptyMap())
                : new DecaySnapshot(now + DECAY_CACHE_MS, values);
        decayCache.put(userId, snapshot);
        return snapshot;
    }

    /** Days until mastery falls below the threshold, from the ML service; -1 when it is unavailable. */
    public int daysUntilThreshold(UserTopicMastery m, double threshold) {
        int gap = m.getLastAttemptAt() == null ? 0 : daysBetween(m.getLastAttemptAt(), Instant.now());
        Long topicId = m.getTopic().getId();
        Map<Long, Integer> result = ml.daysToThreshold(Collections.singletonList(
                new MlServiceClient.DecayInput(topicId, m.getMasteryProbability(), gap, threshold)));
        Integer days = result == null ? null : result.get(topicId);
        return days == null ? -1 : days;
    }

    public List<UserTopicMastery> masteryForSubject(Long userId, String subject) {
        return mastery.findByUserIdAndTopicSubjectNameOrderByMasteryProbabilityAsc(userId, subject);
    }

    public Optional<UserTopicMastery> masteryForTopic(Long userId, Long topicId) {
        return mastery.findByUserIdAndTopicId(userId, topicId);
    }

    public List<UserReviewSchedule> dueForReview(Long userId) {
        return reviews.findByUserIdAndDueAtLessThanEqualOrderByDueAtAsc(userId, Instant.now());
    }

    public List<UserReviewSchedule> dueForReview(Long userId, String subject) {
        return reviews.findByUserIdAndQuestionTopicSubjectNameAndDueAtLessThanEqualOrderByDueAtAsc(
                userId, subject, Instant.now());
    }

    public long dueCount(Long userId) {
        return reviews.countByUserIdAndDueAtLessThanEqual(userId, Instant.now());
    }

    private int daysBetween(Instant from, Instant to) {
        return (int) Math.max(0, Duration.between(from, to).toDays());
    }

    private double clamp(double v) {
        return Math.max(0.01, Math.min(0.99, v));
    }
}

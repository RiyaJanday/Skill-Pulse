package com.skillpulse.ai;

import com.skillpulse.adaptive.AdaptiveLearningService;
import com.skillpulse.adaptive.UserReviewSchedule;
import com.skillpulse.adaptive.UserReviewScheduleRepository;
import com.skillpulse.adaptive.UserTopicMastery;
import com.skillpulse.adaptive.UserTopicMasteryRepository;
import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.UserRepository;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.practice.PracticeDtos;
import com.skillpulse.practice.PracticeQuestion;
import com.skillpulse.practice.PracticeQuestionRepository;
import com.skillpulse.practice.PracticeTopic;
import com.skillpulse.practice.PracticeTopicRepository;
import com.skillpulse.practice.QuestionOption;
import com.skillpulse.practice.QuestionOptionRepository;
import com.skillpulse.practice.UserPracticeAttempt;
import com.skillpulse.practice.UserPracticeAttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges the database and the Python ml-service for the AI features (coach chat, answer explanations,
 * engagement risk, learner segments, question quality, adaptive question order, nudge and question drafts).
 *
 * It contains no ML maths. It gathers plain numbers from the database, sends them to the ml-service and returns
 * the answer. Rules it follows, same as {@link AdaptiveLearningService}:
 *  - Database reads happen inside short transactions; ML calls happen OUTSIDE any transaction.
 *  - Every method returns null (or leaves data untouched) when the ML service is unavailable; callers show a
 *    friendly message and the rest of the app keeps working.
 */
@Service
public class AiInsightsService {
    private static final int MAX_LEARNERS = 5000;
    private static final int MAX_RESPONSES = 200000;
    private static final int WINDOW_DAYS = 30;

    private final UserRepository users;
    private final UserPracticeAttemptRepository attempts;
    private final UserTopicMasteryRepository mastery;
    private final UserReviewScheduleRepository reviews;
    private final PracticeQuestionRepository questions;
    private final QuestionOptionRepository options;
    private final PracticeTopicRepository topics;
    private final AdaptiveLearningService adaptive;
    private final MlServiceClient ml;
    private final TransactionTemplate tx;
    private final ZoneId zone;

    /** questionId -> calibrated difficulty (logit). Filled by the admin question-quality run, used by adaptive order. */
    private volatile Map<Long, Double> calibratedLogits = Collections.emptyMap();

    public AiInsightsService(UserRepository users, UserPracticeAttemptRepository attempts,
                             UserTopicMasteryRepository mastery, UserReviewScheduleRepository reviews,
                             PracticeQuestionRepository questions, QuestionOptionRepository options,
                             PracticeTopicRepository topics, AdaptiveLearningService adaptive, MlServiceClient ml,
                             PlatformTransactionManager txManager,
                             @Value("${skillpulse.notifications.zone:Asia/Kolkata}") String zoneId) {
        this.users = users;
        this.attempts = attempts;
        this.mastery = mastery;
        this.reviews = reviews;
        this.questions = questions;
        this.options = options;
        this.topics = topics;
        this.adaptive = adaptive;
        this.ml = ml;
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        this.tx = template;
        ZoneId resolved;
        try {
            resolved = ZoneId.of(zoneId);
        } catch (RuntimeException ex) {
            resolved = ZoneId.systemDefault();
        }
        this.zone = resolved;
    }

    // ==== learner: overview, coach chat, explanations ======================================================

    /** Engagement check and focus topics for the signed-in learner. */
    public Map<String, Object> overview(AppUser user) {
        Snapshot s = snapshot(user, Instant.now());
        Map<String, Object> risk = riskFor(s.features);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", firstName(user));
        out.put("streak", s.features == null ? 0 : s.features.get("streak"));
        out.put("dueReviews", s.due);
        out.put("weakTopics", s.weakTopics);
        out.put("engagement", risk);
        out.put("engagementNote", s.features == null
                ? "Answer a few practice questions to unlock your engagement check."
                : risk == null ? "The engagement check is unavailable right now." : "");
        return out;
    }

    /**
     * The learner facts the study chat may quote (name, due reviews, weakest topics, streak, accuracy). Only plain
     * numbers and topic names from the database; the chat history is NOT built here, it is stored by ChatService.
     */
    public Map<String, Object> chatContext(AppUser user) {
        Snapshot s = snapshot(user, Instant.now());
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("learnerName", firstName(user));
        context.put("dueReviews", s.due);
        context.put("weakestTopics", s.weakTopics);
        if (s.features != null) {
            context.put("currentStreakDays", s.features.get("streak"));
            context.put("daysSinceLastPractice", s.features.get("daysSinceLast"));
            context.put("practiceDaysInLast14", s.features.get("activeDays14"));
            context.put("accuracyLast30DaysPercent", Math.round(number(s.features.get("accuracy30d")) * 100));
        }
        return context;
    }

    /**
     * Explains one answer the learner has ALREADY submitted. Requiring an existing attempt stops the endpoint being
     * used to reveal answers before answering.
     */
    public Map<String, Object> explain(final AppUser user, final AiDtos.ExplainRequest request) {
        if (request == null || request.getQuestionId() == null || request.getSelectedOptionId() == null) {
            throw new IllegalArgumentException("Choose a question and the option you selected.");
        }
        Map<String, Object> body = tx.execute(status -> {
            PracticeQuestion q = questions.findById(request.getQuestionId())
                    .orElseThrow(() -> new IllegalArgumentException("Question not found."));
            if (!attempts.existsByUserIdAndQuestionId(user.getId(), q.getId())) {
                throw new IllegalArgumentException("Answer this question first, then ask for an explanation.");
            }
            List<QuestionOption> opts = options.findByQuestionIdOrderByDisplayOrderAsc(q.getId());
            List<String> texts = new ArrayList<String>();
            int selected = -1;
            int correct = -1;
            for (int i = 0; i < opts.size(); i++) {
                QuestionOption o = opts.get(i);
                texts.add(o.getOptionText());
                if (o.getId().equals(request.getSelectedOptionId())) selected = i;
                if (Boolean.TRUE.equals(o.getCorrectOption())) correct = i;
            }
            if (selected < 0) throw new IllegalArgumentException("Selected option does not belong to this question.");
            if (correct < 0 || texts.size() < 2 || texts.size() > 4) {
                throw new IllegalArgumentException("This question cannot be explained right now.");
            }
            Integer masteryPercent = null;
            Optional<UserTopicMastery> row = mastery.findByUserIdAndTopicId(user.getId(), q.getTopic().getId());
            if (row.isPresent()) masteryPercent = (int) Math.round(row.get().getMasteryProbability() * 100);

            Map<String, Object> b = new LinkedHashMap<String, Object>();
            b.put("prompt", q.getPrompt());
            b.put("options", texts);
            b.put("selectedIndex", selected);
            b.put("correctIndex", correct);
            b.put("baseExplanation", q.getExplanation());
            b.put("masteryPercent", masteryPercent);
            b.put("topic", q.getTopic().getName());
            return b;
        });
        return ml.callSlow("/tutor/explain", body);
    }

    // ==== learner: adaptive question order ==================================================================

    /**
     * Re-orders a module's questions in place so the ones closest to the learner's productive-struggle zone come
     * first (Rasch model on BKT mastery). Leaves the list untouched if the ML service is unavailable.
     */
    public void applyAdaptiveOrder(AppUser user, PracticeTopic topic, List<PracticeDtos.QuestionResponse> list) {
        if (list == null || list.size() < 2) return;

        double masteryNow = 0.20;
        Optional<UserTopicMastery> row = mastery.findByUserIdAndTopicId(user.getId(), topic.getId());
        if (row.isPresent()) masteryNow = adaptive.effectiveMastery(row.get());

        Instant now = Instant.now();
        Set<Long> due = new HashSet<Long>();
        for (UserReviewSchedule s : reviews.findByUserIdAndQuestionTopicId(user.getId(), topic.getId())) {
            if (!s.getDueAt().isAfter(now)) due.add(s.getQuestion().getId());
        }

        Map<Long, Double> logits = calibratedLogits;
        List<Object> items = new ArrayList<Object>();
        for (PracticeDtos.QuestionResponse q : list) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", q.getId());
            item.put("difficulty", q.getDifficulty());
            Double b = logits.get(q.getId());
            if (b != null) item.put("difficultyLogit", b);
            item.put("due", due.contains(q.getId()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("mastery", masteryNow);
        body.put("questions", items);
        body.put("target", 0.70);

        Map<String, Object> result = ml.callFast("/adaptive/order", body);
        if (result == null || !(result.get("order") instanceof List)) return;

        Map<Long, PracticeDtos.QuestionResponse> byId = new LinkedHashMap<Long, PracticeDtos.QuestionResponse>();
        for (PracticeDtos.QuestionResponse q : list) byId.put(q.getId(), q);
        List<PracticeDtos.QuestionResponse> ordered = new ArrayList<PracticeDtos.QuestionResponse>();
        for (Object id : (List<?>) result.get("order")) {
            if (!(id instanceof Number)) continue;
            PracticeDtos.QuestionResponse q = byId.remove(((Number) id).longValue());
            if (q != null) ordered.add(q);
        }
        ordered.addAll(byId.values()); // anything the service did not mention keeps its place at the end
        list.clear();
        list.addAll(ordered);
    }

    // ==== admin: cohort analytics ==========================================================================

    /** Engagement risk for every learner with activity, highest risk first. Null when the ML service is down. */
    public Map<String, Object> cohortRisk() {
        Cohort cohort = cohort();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (cohort.features.isEmpty()) {
            out.put("learners", new ArrayList<Object>());
            out.put("summary", summary(0, 0, 0));
            out.put("message", "No learner activity yet. Risk scores appear once learners start practising.");
            out.put("model", ml.get("/risk/info"));
            return out;
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("learners", cohort.features);
        Map<String, Object> result = ml.callSlow("/risk/predict", body);
        if (result == null || !(result.get("results") instanceof List)) return null;

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int high = 0, medium = 0, low = 0;
        for (Object o : (List<?>) result.get("results")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) o;
            String key = String.valueOf(r.get("key"));
            AppUser u = cohort.byKey.get(key);
            Map<String, Object> f = cohort.featuresByKey.get(key);
            if (u == null || f == null) continue;
            String level = String.valueOf(r.get("level"));
            if ("HIGH".equals(level)) high++; else if ("MEDIUM".equals(level)) medium++; else low++;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("userId", u.getId());
            row.put("name", u.getName());
            row.put("email", u.getEmail());
            row.put("riskScore", r.get("riskScore"));
            row.put("level", level);
            row.put("reasons", r.get("reasons"));
            row.put("daysSinceLast", f.get("daysSinceLast"));
            row.put("attempts7d", f.get("attempts7d"));
            row.put("accuracy30d", f.get("accuracy30d"));
            row.put("streak", f.get("streak"));
            row.put("dueReviews", f.get("dueReviews"));
            rows.add(row);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(number(b.get("riskScore")), number(a.get("riskScore")));
            }
        });
        out.put("engine", result.get("engine"));
        out.put("summary", summary(high, medium, low));
        out.put("learners", rows);
        out.put("model", ml.get("/risk/info"));
        return out;
    }

    /** k-means learner segments with named groups. Null when the ML service is down. */
    public Map<String, Object> cohortSegments(int k) {
        Cohort cohort = cohort();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (cohort.features.isEmpty()) {
            out.put("segments", new ArrayList<Object>());
            out.put("learners", new ArrayList<Object>());
            out.put("message", "No learner activity yet. Segments appear once learners start practising.");
            return out;
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("learners", cohort.features);
        body.put("k", Math.max(1, Math.min(6, k)));
        Map<String, Object> result = ml.callSlow("/segments/cluster", body);
        if (result == null || !(result.get("segments") instanceof List)) return null;

        Map<Integer, String> names = new HashMap<Integer, String>();
        for (Object o : (List<?>) result.get("segments")) {
            if (o instanceof Map) {
                Map<?, ?> seg = (Map<?, ?>) o;
                if (seg.get("id") instanceof Number) names.put(((Number) seg.get("id")).intValue(), String.valueOf(seg.get("name")));
            }
        }
        List<Map<String, Object>> learners = new ArrayList<Map<String, Object>>();
        if (result.get("assignments") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) result.get("assignments")).entrySet()) {
                AppUser u = cohort.byKey.get(String.valueOf(e.getKey()));
                if (u == null || !(e.getValue() instanceof Number)) continue;
                int segmentId = ((Number) e.getValue()).intValue();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("userId", u.getId());
                row.put("name", u.getName());
                row.put("email", u.getEmail());
                row.put("segmentId", segmentId);
                row.put("segmentName", names.get(segmentId));
                learners.add(row);
            }
        }
        out.putAll(result);
        out.remove("assignments");
        out.put("learners", learners);
        return out;
    }

    /** Rasch calibration and item statistics for every question. Null when the ML service is down. */
    public Map<String, Object> questionQuality() {
        final List<Object> responses = new ArrayList<Object>();
        final List<Object> labelled = new ArrayList<Object>();
        final Map<Long, PracticeQuestion> byId = new HashMap<Long, PracticeQuestion>();
        tx.execute(status -> {
            for (UserPracticeAttempt a : attempts.findAllByOrderByIdDesc(PageRequest.of(0, MAX_RESPONSES)).getContent()) {
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("questionId", a.getQuestion().getId());
                r.put("userId", a.getUser().getId());
                r.put("correct", Boolean.TRUE.equals(a.getCorrect()));
                r.put("seconds", a.getTimeTakenSeconds() == null ? 0 : a.getTimeTakenSeconds());
                responses.add(r);
            }
            for (PracticeQuestion q : questions.findAll()) {
                byId.put(q.getId(), q);
                Map<String, Object> l = new LinkedHashMap<String, Object>();
                l.put("id", q.getId());
                l.put("difficulty", q.getDifficulty() == null ? "MEDIUM" : q.getDifficulty().name());
                labelled.add(l);
            }
            return null;
        });

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("responses", responses);
        body.put("questions", labelled);
        body.put("minResponses", 8);
        Map<String, Object> result = ml.callSlow("/questions/quality", body);
        if (result == null) return null;

        // Attach question text for the admin table (needs the transaction again for lazy topic/subject names).
        final Map<String, Object> mutable = new LinkedHashMap<String, Object>(result);
        final Map<Long, Double> logits = new HashMap<Long, Double>();
        tx.execute(status -> {
            Object rows = mutable.get("questions");
            if (rows instanceof List) {
                for (Object o : (List<?>) rows) {
                    if (!(o instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) o;
                    Object id = row.get("questionId");
                    PracticeQuestion q = id instanceof Number ? byId.get(((Number) id).longValue()) : null;
                    if (q != null) {
                        row.put("prompt", clip(q.getPrompt(), 160));
                        row.put("topicName", q.getTopic().getName());
                        row.put("subjectName", q.getTopic().getSubject().getName());
                        row.put("active", Boolean.TRUE.equals(q.getActive()));
                    }
                    if (id instanceof Number && row.get("difficultyLogit") instanceof Number) {
                        logits.put(((Number) id).longValue(), ((Number) row.get("difficultyLogit")).doubleValue());
                    }
                }
            }
            return null;
        });
        if (!Boolean.TRUE.equals(mutable.get("insufficientData")) && !logits.isEmpty()) calibratedLogits = logits;
        return mutable;
    }

    /** Drafts a re-engagement email for one learner. The admin reviews and sends it; nothing is sent from here. */
    public Map<String, Object> nudge(Long userId) {
        if (userId == null) throw new IllegalArgumentException("Choose a learner.");
        AppUser user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("Learner not found."));
        Snapshot s = snapshot(user, Instant.now());
        Map<String, Object> risk = riskFor(s.features);

        List<Object> weak = new ArrayList<Object>();
        for (Map<String, Object> t : s.weakTopics) {
            if (weak.size() >= 3) break;
            weak.add(t.get("topic"));
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", firstName(user));
        body.put("riskLevel", risk == null ? "MEDIUM" : risk.get("level"));
        body.put("reasons", risk == null ? new ArrayList<Object>() : risk.get("reasons"));
        body.put("weakTopics", weak);
        body.put("dueReviews", s.due);
        body.put("streak", s.features == null ? 0 : s.features.get("streak"));
        Map<String, Object> result = ml.callSlow("/coach/nudge", body);
        if (result == null) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>(result);
        out.put("userId", user.getId());
        out.put("name", user.getName());
        out.put("email", user.getEmail());
        return out;
    }

    /** AI question drafts for one module. The admin reviews each one before saving it to the bank. */
    public Map<String, Object> generateQuestions(AiDtos.GenerateRequest request) {
        if (request == null || request.getTopicId() == null) throw new IllegalArgumentException("Select a module.");
        final Long topicId = request.getTopicId();
        Map<String, Object> body = tx.execute(status -> {
            PracticeTopic topic = topics.findById(topicId).orElseThrow(() -> new IllegalArgumentException("Module not found."));
            List<Object> existing = new ArrayList<Object>();
            List<PracticeQuestion> all = questions.findByTopicIdOrderByIdAsc(topicId);
            for (int i = Math.max(0, all.size() - 25); i < all.size(); i++) existing.add(all.get(i).getPrompt());
            Map<String, Object> b = new LinkedHashMap<String, Object>();
            b.put("subject", topic.getSubject().getName());
            b.put("topic", topic.getName());
            b.put("existingPrompts", existing);
            return b;
        });
        String difficulty = request.getDifficulty() == null ? "MEDIUM" : request.getDifficulty().trim().toUpperCase();
        body.put("difficulty", difficulty.matches("EASY|MEDIUM|HARD") ? difficulty : "MEDIUM");
        body.put("count", Math.max(1, Math.min(5, request.getCount() == null ? 3 : request.getCount())));
        Map<String, Object> result = ml.callSlow("/tutor/generate-questions", body);
        if (result == null) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>(result);
        out.put("topicId", topicId);
        return out;
    }

    // ==== admin: per-topic BKT fitting and data export =====================================================

    private static final int MIN_FIT_SEQUENCES = 5;

    /**
     * Fits Bayesian Knowledge Tracing parameters (learn, guess, slip) for each topic from the real answers of all
     * learners, through the ML service, and stores them on every learner's mastery row for that topic. Stored mastery
     * values are not changed; the new parameters apply from each learner's NEXT answer. Returns null when the ML
     * service is down and nothing could be fitted.
     */
    public Map<String, Object> fitBkt() {
        final Map<Long, Map<Long, List<Boolean>>> byTopic = new LinkedHashMap<Long, Map<Long, List<Boolean>>>();
        final Map<Long, String> topicNames = new HashMap<Long, String>();
        tx.execute(status -> {
            List<UserPracticeAttempt> all = new ArrayList<UserPracticeAttempt>(
                    attempts.findAllByOrderByIdDesc(PageRequest.of(0, MAX_RESPONSES)).getContent());
            Collections.sort(all, new Comparator<UserPracticeAttempt>() {
                @Override
                public int compare(UserPracticeAttempt a, UserPracticeAttempt b) {
                    return a.getAttemptedAt().compareTo(b.getAttemptedAt());
                }
            });
            for (UserPracticeAttempt a : all) {
                Long topicId = a.getQuestion().getTopic().getId();
                Long userId = a.getUser().getId();
                Map<Long, List<Boolean>> perUser = byTopic.get(topicId);
                if (perUser == null) {
                    perUser = new LinkedHashMap<Long, List<Boolean>>();
                    byTopic.put(topicId, perUser);
                }
                List<Boolean> seq = perUser.get(userId);
                if (seq == null) {
                    seq = new ArrayList<Boolean>();
                    perUser.put(userId, seq);
                }
                seq.add(Boolean.TRUE.equals(a.getCorrect()));
            }
            for (PracticeTopic t : topics.findAll()) topicNames.put(t.getId(), t.getName());
            return null;
        });

        List<Map<String, Object>> fitted = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> skipped = new ArrayList<Map<String, Object>>();
        int failedCalls = 0;
        for (Map.Entry<Long, Map<Long, List<Boolean>>> entry : byTopic.entrySet()) {
            Long topicId = entry.getKey();
            String name = topicNames.get(topicId);
            List<Object> sequences = new ArrayList<Object>();
            for (List<Boolean> seq : entry.getValue().values()) {
                if (seq.size() >= 2) sequences.add(seq);
            }
            if (sequences.size() < MIN_FIT_SEQUENCES) {
                skipped.add(skipRow(topicId, name, "Only " + sequences.size()
                        + " learner(s) with 2 or more answers; need " + MIN_FIT_SEQUENCES + "."));
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("sequences", sequences);
            Map<String, Object> result = ml.callSlow("/bkt/fit", body);
            if (result == null || !(result.get("params") instanceof Map)) {
                failedCalls++;
                skipped.add(skipRow(topicId, name, "The ML service could not fit this topic."));
                continue;
            }
            Map<?, ?> p = (Map<?, ?>) result.get("params");
            double init = number(p.get("pInit"));
            double learn = number(p.get("pLearn"));
            double guess = number(p.get("pGuess"));
            double slip = number(p.get("pSlip"));
            if (init <= 0 || learn <= 0 || guess <= 0 || slip <= 0 || guess + slip >= 0.6) {
                skipped.add(skipRow(topicId, name, "Fitted values looked unreliable, so the defaults were kept."));
                continue;
            }
            int updated;
            try {
                List<UserTopicMastery> rows = mastery.findByTopicId(topicId);
                for (UserTopicMastery m : rows) {
                    m.setPriorProbability(init);
                    m.setLearnProbability(learn);
                    m.setGuessProbability(guess);
                    m.setSlipProbability(slip);
                }
                mastery.saveAll(rows);
                updated = rows.size();
            } catch (RuntimeException ex) {
                skipped.add(skipRow(topicId, name, "Could not save because learners were answering at the same time. Run it again."));
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("topicId", topicId);
            row.put("topic", name);
            row.put("learners", result.get("sequences"));
            row.put("observations", result.get("observations"));
            row.put("pInit", init);
            row.put("pLearn", learn);
            row.put("pGuess", guess);
            row.put("pSlip", slip);
            row.put("logLikelihood", result.get("logLikelihood"));
            row.put("rowsUpdated", updated);
            fitted.add(row);
        }
        if (fitted.isEmpty() && failedCalls > 0) return null;

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("fitted", fitted);
        out.put("skipped", skipped);
        out.put("message", fitted.isEmpty()
                ? "No topic has enough answers yet. Each topic needs at least " + MIN_FIT_SEQUENCES
                        + " learners with 2 or more answers."
                : "Fitted " + fitted.size() + " topic(s). The new values apply from each learner's next answer.");
        return out;
    }

    private static Map<String, Object> skipRow(Long topicId, String name, String reason) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("topicId", topicId);
        row.put("topic", name);
        row.put("reason", reason);
        return row;
    }

    /**
     * Every stored answer as CSV, oldest first, for retraining the ML models on real data (python train.py --attempts).
     * Only anonymous numeric ids are included: no names, emails or question text.
     */
    public String exportAttemptsCsv() {
        final StringBuilder out = new StringBuilder("userId,topicId,questionId,difficulty,correct,seconds,attemptedAt\n");
        tx.execute(status -> {
            List<UserPracticeAttempt> all = new ArrayList<UserPracticeAttempt>(
                    attempts.findAllByOrderByIdDesc(PageRequest.of(0, MAX_RESPONSES)).getContent());
            Collections.reverse(all);
            for (UserPracticeAttempt a : all) {
                PracticeQuestion q = a.getQuestion();
                out.append(a.getUser().getId()).append(',')
                        .append(q.getTopic().getId()).append(',')
                        .append(q.getId()).append(',')
                        .append(q.getDifficulty() == null ? "MEDIUM" : q.getDifficulty().name()).append(',')
                        .append(Boolean.TRUE.equals(a.getCorrect())).append(',')
                        .append(a.getTimeTakenSeconds() == null ? 0 : a.getTimeTakenSeconds()).append(',')
                        .append(a.getAttemptedAt()).append('\n');
            }
            return null;
        });
        return out.toString();
    }

    // ==== feature building =================================================================================

    private static final class Snapshot {
        final Map<String, Object> features;              // null when the learner has never practised
        final List<Map<String, Object>> weakTopics;
        final long due;

        Snapshot(Map<String, Object> features, List<Map<String, Object>> weakTopics, long due) {
            this.features = features;
            this.weakTopics = weakTopics;
            this.due = due;
        }
    }

    private static final class Cohort {
        final List<Object> features = new ArrayList<Object>();
        final Map<String, Map<String, Object>> featuresByKey = new HashMap<String, Map<String, Object>>();
        final Map<String, AppUser> byKey = new HashMap<String, AppUser>();
    }

    private Snapshot snapshot(final AppUser user, final Instant now) {
        return tx.execute(status -> {
            List<UserPracticeAttempt> recent = attempts.findByUserIdAndAttemptedAtAfter(
                    user.getId(), now.minus(WINDOW_DAYS, ChronoUnit.DAYS));
            List<UserTopicMastery> rows = mastery.findByUserId(user.getId());
            long due = adaptive.dueCount(user.getId());
            Map<String, Object> features = features(String.valueOf(user.getId()), recent, rows, due, now);

            List<UserTopicMastery> sorted = new ArrayList<UserTopicMastery>(rows);
            Collections.sort(sorted, new Comparator<UserTopicMastery>() {
                @Override
                public int compare(UserTopicMastery a, UserTopicMastery b) {
                    return Double.compare(a.getMasteryProbability(), b.getMasteryProbability());
                }
            });
            List<Map<String, Object>> weak = new ArrayList<Map<String, Object>>();
            for (UserTopicMastery m : sorted) {
                if (m.getAttemptCount() <= 0) continue;
                if (weak.size() >= 5) break;
                Map<String, Object> t = new LinkedHashMap<String, Object>();
                t.put("topicId", m.getTopic().getId());
                t.put("topic", m.getTopic().getName());
                t.put("subject", m.getTopic().getSubject().getName());
                t.put("masteryPercent", (int) Math.round(m.getMasteryProbability() * 100));
                t.put("attempts", m.getAttemptCount());
                weak.add(t);
            }
            return new Snapshot(features, weak, due);
        });
    }

    private Cohort cohort() {
        return tx.execute(status -> {
            Instant now = Instant.now();
            Map<Long, List<UserPracticeAttempt>> attemptsByUser = new HashMap<Long, List<UserPracticeAttempt>>();
            for (UserPracticeAttempt a : attempts.findByAttemptedAtAfter(now.minus(WINDOW_DAYS, ChronoUnit.DAYS))) {
                Long id = a.getUser().getId();
                if (!attemptsByUser.containsKey(id)) attemptsByUser.put(id, new ArrayList<UserPracticeAttempt>());
                attemptsByUser.get(id).add(a);
            }
            Map<Long, List<UserTopicMastery>> masteryByUser = new HashMap<Long, List<UserTopicMastery>>();
            for (UserTopicMastery m : mastery.findAll()) {
                Long id = m.getUser().getId();
                if (!masteryByUser.containsKey(id)) masteryByUser.put(id, new ArrayList<UserTopicMastery>());
                masteryByUser.get(id).add(m);
            }
            Map<Long, Long> dueByUser = new HashMap<Long, Long>();
            for (UserReviewSchedule s : reviews.findByDueAtLessThanEqual(now)) {
                Long id = s.getUser().getId();
                Long current = dueByUser.get(id);
                dueByUser.put(id, current == null ? 1L : current + 1L);
            }

            Cohort cohort = new Cohort();
            for (AppUser u : users.findAll()) {
                List<UserPracticeAttempt> recent = attemptsByUser.get(u.getId());
                List<UserTopicMastery> rows = masteryByUser.get(u.getId());
                Long due = dueByUser.get(u.getId());
                Map<String, Object> f = features(String.valueOf(u.getId()),
                        recent == null ? Collections.<UserPracticeAttempt>emptyList() : recent,
                        rows == null ? Collections.<UserTopicMastery>emptyList() : rows,
                        due == null ? 0L : due, now);
                if (f == null) continue; // never practised: nothing to say about engagement yet
                cohort.features.add(f);
                cohort.featuresByKey.put(String.valueOf(u.getId()), f);
                cohort.byKey.put(String.valueOf(u.getId()), u);
                if (cohort.features.size() >= MAX_LEARNERS) break;
            }
            return cohort;
        });
    }

    /**
     * The ten numbers the ML models expect (see ml-service/app/schemas_ai.py LearnerFeatures). Returns null when the
     * learner has no activity at all, because engagement can not be judged from nothing.
     */
    private Map<String, Object> features(String key, List<UserPracticeAttempt> recent, List<UserTopicMastery> rows,
                                         long due, Instant now) {
        Instant last = null;
        for (UserPracticeAttempt a : recent) {
            if (last == null || a.getAttemptedAt().isAfter(last)) last = a.getAttemptedAt();
        }
        for (UserTopicMastery m : rows) {
            if (m.getLastAttemptAt() != null && (last == null || m.getLastAttemptAt().isAfter(last))) last = m.getLastAttemptAt();
        }
        if (last == null) return null;

        LocalDate today = now.atZone(zone).toLocalDate();
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        int attempts7 = 0, correct7 = 0, correct30 = 0, secondsCount = 0;
        long secondsTotal = 0;
        Set<LocalDate> days = new HashSet<LocalDate>();
        for (UserPracticeAttempt a : recent) {
            boolean ok = Boolean.TRUE.equals(a.getCorrect());
            if (ok) correct30++;
            if (a.getAttemptedAt().isAfter(weekAgo)) {
                attempts7++;
                if (ok) correct7++;
            }
            days.add(a.getAttemptedAt().atZone(zone).toLocalDate());
            int seconds = a.getTimeTakenSeconds() == null ? 0 : a.getTimeTakenSeconds();
            if (seconds > 0) {
                secondsTotal += Math.min(seconds, 300);
                secondsCount++;
            }
        }
        int attempts30 = recent.size();

        int activeDays14 = 0;
        for (int i = 0; i < 14; i++) if (days.contains(today.minusDays(i))) activeDays14++;

        int streak = 0;
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }

        long daysSince = Math.min(60L, Math.max(0L, ChronoUnit.DAYS.between(last.atZone(zone).toLocalDate(), today)));
        double avgMastery = 0.20;
        if (!rows.isEmpty()) {
            double sum = 0;
            for (UserTopicMastery m : rows) sum += m.getMasteryProbability();
            avgMastery = sum / rows.size();
        }

        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("key", key);
        f.put("daysSinceLast", daysSince);
        f.put("attempts7d", attempts7);
        f.put("attempts30d", attempts30);
        f.put("accuracy7d", attempts7 == 0 ? 0.0 : (double) correct7 / attempts7);
        f.put("accuracy30d", attempts30 == 0 ? 0.0 : (double) correct30 / attempts30);
        f.put("activeDays14", activeDays14);
        f.put("streak", streak);
        f.put("avgMastery", avgMastery);
        f.put("dueReviews", due);
        f.put("avgSeconds", secondsCount == 0 ? 40.0 : (double) secondsTotal / secondsCount);
        return f;
    }

    /** Risk result for one learner's features, or null when there are no features or the service is down. */
    private Map<String, Object> riskFor(Map<String, Object> features) {
        if (features == null) return null;
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("learners", Collections.singletonList(features));
        Map<String, Object> result = ml.callFast("/risk/predict", body);
        if (result == null || !(result.get("results") instanceof List) || ((List<?>) result.get("results")).isEmpty()) return null;
        Object first = ((List<?>) result.get("results")).get(0);
        if (!(first instanceof Map)) return null;
        Map<?, ?> row = (Map<?, ?>) first;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("riskScore", row.get("riskScore"));
        out.put("level", row.get("level"));
        out.put("reasons", row.get("reasons"));
        out.put("engine", result.get("engine"));
        return out;
    }

    // ==== small helpers ====================================================================================

    private static Map<String, Object> summary(int high, int medium, int low) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("learners", high + medium + low);
        s.put("high", high);
        s.put("medium", medium);
        s.put("low", low);
        return s;
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static String clip(String value, int limit) {
        String v = value == null ? "" : value;
        return v.length() <= limit ? v : v.substring(0, limit);
    }

    private static String firstName(AppUser user) {
        String first = user.getFirstName();
        if (first != null && !first.trim().isEmpty()) return first.trim();
        String name = user.getName() == null ? "" : user.getName().trim();
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }
}

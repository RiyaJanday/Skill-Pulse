package com.skillpulse.ai;

import com.skillpulse.adaptive.AdaptiveLearningService;
import com.skillpulse.adaptive.UserTopicMastery;
import com.skillpulse.adaptive.UserTopicMasteryRepository;
import com.skillpulse.auth.AppUser;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.practice.PracticeTopic;
import com.skillpulse.practice.PracticeTopicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Job-description skill-gap analysis. Gathers the learner's real mastery per practised topic and the platform's topic
 * names from the database, then asks the Python ml-service (LLM) to extract the job's skills and match them. The
 * ml-service recomputes every status from the real mastery numbers, so this class only moves data.
 *
 * Database reads happen in one short transaction; the ML call happens OUTSIDE it. Returns null when the ML service
 * is unavailable and the caller shows a friendly message.
 */
@Service
public class SkillGapService {
    private static final int MIN_CHARS = 40;
    private static final int MAX_CHARS = 6000;
    private static final int MAX_PLATFORM_TOPICS = 120;

    private final UserTopicMasteryRepository mastery;
    private final PracticeTopicRepository topics;
    private final AdaptiveLearningService adaptive;
    private final MlServiceClient ml;
    private final TransactionTemplate tx;

    public SkillGapService(UserTopicMasteryRepository mastery, PracticeTopicRepository topics,
                           AdaptiveLearningService adaptive, MlServiceClient ml,
                           PlatformTransactionManager txManager) {
        this.mastery = mastery;
        this.topics = topics;
        this.adaptive = adaptive;
        this.ml = ml;
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        this.tx = template;
    }

    public Map<String, Object> analyze(final AppUser user, String jobDescription) {
        String text = jobDescription == null ? "" : jobDescription.trim();
        if (text.length() < MIN_CHARS) {
            throw new IllegalArgumentException("Paste the job description first (at least a couple of sentences).");
        }
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);

        final List<Object> learnerTopics = new ArrayList<Object>();
        final List<Object> platformTopics = new ArrayList<Object>();
        tx.execute(status -> {
            for (UserTopicMastery m : mastery.findByUserId(user.getId())) {
                if (m.getAttemptCount() <= 0) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("topic", m.getTopic().getName());
                row.put("subject", m.getTopic().getSubject().getName());
                row.put("masteryPercent", (int) Math.round(adaptive.effectiveMastery(m) * 100));
                learnerTopics.add(row);
            }
            for (PracticeTopic t : topics.findAll()) {
                if (platformTopics.size() >= MAX_PLATFORM_TOPICS) break;
                platformTopics.add(t.getName());
            }
            return null;
        });

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("jobDescription", text);
        body.put("learnerTopics", learnerTopics);
        body.put("platformTopics", platformTopics);
        Map<String, Object> result = ml.callSlow("/skills/gap", body);
        if (result == null) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>(result);
        out.put("practisedTopics", learnerTopics.size());
        return out;
    }
}

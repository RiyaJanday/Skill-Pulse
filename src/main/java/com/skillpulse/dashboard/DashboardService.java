package com.skillpulse.dashboard;

import com.skillpulse.auth.AppUser;
import com.skillpulse.adaptive.AdaptiveLearningService;
import com.skillpulse.practice.PracticeQuestion;
import com.skillpulse.practice.PracticeQuestionRepository;
import com.skillpulse.practice.PracticeSubject;
import com.skillpulse.practice.PracticeSubjectRepository;
import com.skillpulse.practice.UserPracticeAttempt;
import com.skillpulse.practice.UserPracticeAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DashboardService {
    private final PracticeSubjectRepository subjects;
    private final PracticeQuestionRepository questions;
    private final UserPracticeAttemptRepository attempts;
    private final AdaptiveLearningService adaptive;

    public DashboardService(PracticeSubjectRepository subjects,
                            PracticeQuestionRepository questions,
                            UserPracticeAttemptRepository attempts,
                            AdaptiveLearningService adaptive) {
        this.subjects = subjects;
        this.questions = questions;
        this.attempts = attempts;
        this.adaptive = adaptive;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(AppUser user) {
        List<SkillSummary> skills;
        try {
            skills = skills(user);
        } catch (RuntimeException ex) {
            skills = skills();
        }

        int total = skills.size();
        int atRisk = 0;
        int sum = 0;
        for (SkillSummary skill : skills) {
            sum += skill.getScore();
            if (skill.getScore() < 60) {
                atRisk++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("user", user.getName());
        result.put("overallHealth", total == 0 ? 0 : Math.round(sum * 1.0 / total));
        result.put("skillsTracked", total);
        result.put("skillsNeedingAttention", atRisk);
        result.put("practiceStreakDays", safePracticeStreakDays(user));
        result.put("practiceMinutesThisWeek", safePracticeMinutesThisWeek(user));
        result.put("mistakesToReview", mistakesToReview(user));
        result.put("achievements", achievements(user, skills));
        result.put("todayDayIndex", LocalDate.now(ZoneId.systemDefault()).getDayOfWeek().getValue() % 7);
        result.put("skills", skills);
        return result;
    }

    public List<SkillSummary> skills() {
        List<SkillSummary> skills = new ArrayList<SkillSummary>();
        for (PracticeSubject subject : subjects.findAllByOrderByDisplayOrderAsc()) {
            skills.add(new SkillSummary(subject.getName(), 35, "DECAYING", "Start practice to establish your baseline."));
        }
        return skills;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(AppUser user) {
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("hh:mm a");
        List<UserPracticeAttempt> userAttempts = attempts.findByUserIdOrderByAttemptedAtDesc(user.getId());
        List<List<UserPracticeAttempt>> sessions = new ArrayList<List<UserPracticeAttempt>>();

        for (UserPracticeAttempt attempt : userAttempts) {
            List<UserPracticeAttempt> session = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
            if (!canJoinAssessmentSession(session, attempt)) {
                session = new ArrayList<UserPracticeAttempt>();
                sessions.add(session);
            }
            session.add(attempt);
        }

        Map<LocalDate, List<Map<String, Object>>> grouped = new LinkedHashMap<LocalDate, List<Map<String, Object>>>();

        for (List<UserPracticeAttempt> session : sessions) {
            UserPracticeAttempt latest = session.get(0);
            PracticeQuestion question = latest.getQuestion();
            LocalDate date = latest.getAttemptedAt().atZone(zone).toLocalDate();
            if (!grouped.containsKey(date)) {
                grouped.put(date, new ArrayList<Map<String, Object>>());
            }

            int correct = 0;
            int timeTakenSeconds = 0;
            for (UserPracticeAttempt attempt : session) {
                if (Boolean.TRUE.equals(attempt.getCorrect())) correct++;
                timeTakenSeconds += attempt.getTimeTakenSeconds();
            }
            int totalQuestions = (int) questions.findByTopicIdAndActiveTrueOrderByIdAsc(question.getTopic().getId()).size();
            int score = totalQuestions == 0 ? 0 : (int) Math.round(correct * 100.0 / totalQuestions);

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", latest.getId());
            row.put("time", latest.getAttemptedAt().atZone(zone).format(timeFormatter));
            row.put("subject", question.getTopic().getSubject().getName());
            row.put("topic", question.getTopic().getName());
            row.put("answeredCount", session.size());
            row.put("questionCount", totalQuestions);
            row.put("correctCount", correct);
            row.put("score", score);
            row.put("passed", score >= 80);
            row.put("timeTakenSeconds", timeTakenSeconds);
            grouped.get(date).add(row);
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<LocalDate, List<Map<String, Object>>> entry : grouped.entrySet()) {
            int scoreTotal = 0;
            for (Map<String, Object> row : entry.getValue()) {
                scoreTotal += ((Number) row.get("score")).intValue();
            }
            Map<String, Object> day = new LinkedHashMap<String, Object>();
            day.put("date", entry.getKey().format(dateFormatter));
            day.put("moduleCount", entry.getValue().size());
            day.put("averageScore", entry.getValue().isEmpty() ? 0 : Math.round(scoreTotal * 1.0 / entry.getValue().size()));
            day.put("modules", entry.getValue());
            result.add(day);
        }
        return result;
    }

    private boolean canJoinAssessmentSession(List<UserPracticeAttempt> session, UserPracticeAttempt attempt) {
        if (session == null || session.isEmpty() || session.size() >= 15) return false;
        UserPracticeAttempt oldest = session.get(session.size() - 1);
        Long sessionTopicId = session.get(0).getQuestion().getTopic().getId();
        Long attemptTopicId = attempt.getQuestion().getTopic().getId();
        if (!sessionTopicId.equals(attemptTopicId)) return false;
        long gapMinutes = Math.abs(Duration.between(attempt.getAttemptedAt(), oldest.getAttemptedAt()).toMinutes());
        return gapMinutes <= 10;
    }

    private List<SkillSummary> skills(AppUser user) {
        List<PracticeSubject> subjectList = subjects.findAllByOrderByDisplayOrderAsc();
        List<UserPracticeAttempt> userAttempts = attempts.findByUserIdOrderByAttemptedAtDesc(user.getId());
        Map<Long, List<UserPracticeAttempt>> attemptsBySubject = new HashMap<Long, List<UserPracticeAttempt>>();

        for (UserPracticeAttempt attempt : userAttempts) {
            Long subjectId = attempt.getQuestion().getTopic().getSubject().getId();
            if (!attemptsBySubject.containsKey(subjectId)) {
                attemptsBySubject.put(subjectId, new ArrayList<UserPracticeAttempt>());
            }
            attemptsBySubject.get(subjectId).add(attempt);
        }

        List<SkillSummary> result = new ArrayList<SkillSummary>();
        for (PracticeSubject subject : subjectList) {
            List<UserPracticeAttempt> subjectAttempts = attemptsBySubject.get(subject.getId());
            if (subjectAttempts == null || subjectAttempts.isEmpty()) {
                result.add(new SkillSummary(subject.getName(), 35, "DECAYING",
                        "No attempts yet. Practice one topic to create your baseline."));
            } else {
                int score = calculateHealth(subject, subjectAttempts);
                result.add(new SkillSummary(subject.getName(), score, status(score), recommendation(subject, score)));
            }
        }
        return result;
    }

    private int calculateHealth(PracticeSubject subject, List<UserPracticeAttempt> subjectAttempts) {
        int correct = 0;
        int difficultyPoints = 0;
        Set<Long> uniqueQuestions = new HashSet<Long>();
        Instant latest = Instant.EPOCH;

        for (UserPracticeAttempt attempt : subjectAttempts) {
            if (Boolean.TRUE.equals(attempt.getCorrect())) {
                correct++;
            }
            uniqueQuestions.add(attempt.getQuestion().getId());
            difficultyPoints += difficultyPoints(attempt.getQuestion().getDifficulty());
            if (attempt.getAttemptedAt().isAfter(latest)) {
                latest = attempt.getAttemptedAt();
            }
        }

        int totalQuestions = (int) questions.countByTopicSubjectIdAndActiveTrue(subject.getId());
        double accuracyScore = (correct * 100.0 / subjectAttempts.size()) * 0.70;
        double coverageScore = totalQuestions == 0 ? 0 : Math.min(100.0, uniqueQuestions.size() * 100.0 / totalQuestions) * 0.15;
        double difficultyScore = Math.min(10.0, difficultyPoints * 1.0 / subjectAttempts.size());
        double recencyScore = recencyScore(latest);
        return (int) Math.max(0, Math.min(100, Math.round(accuracyScore + coverageScore + difficultyScore + recencyScore)));
    }

    private int difficultyPoints(PracticeQuestion.Difficulty difficulty) {
        if (PracticeQuestion.Difficulty.HARD.equals(difficulty)) return 10;
        if (PracticeQuestion.Difficulty.MEDIUM.equals(difficulty)) return 7;
        return 4;
    }

    private double recencyScore(Instant latest) {
        long days = Duration.between(latest, Instant.now()).toDays();
        if (days <= 7) return 10;
        if (days <= 14) return 7;
        if (days <= 30) return 4;
        return 0;
    }

    private String status(int score) {
        if (score >= 80) return "STRONG";
        if (score >= 60) return "STABLE";
        return "DECAYING";
    }

    private String recommendation(PracticeSubject subject, int score) {
        if (score >= 80) return "Great retention. Keep practicing mixed medium and hard questions.";
        if (score >= 60) return "Stable foundation. Add a few timed questions to improve confidence.";
        return "Focus on " + subject.getName() + " basics and repeat missed questions this week.";
    }

    private String cleanPrompt(String prompt, String topicName) {
        Matcher forTopic = Pattern.compile("^(Easy|Medium|Hard) practice \\d+ for (.+?): (.+)$", Pattern.CASE_INSENSITIVE)
                .matcher(prompt);
        if (forTopic.matches()) {
            return capitalize(forTopic.group(3).replace("this topic", topicName));
        }

        Matcher inSubject = Pattern.compile("^(Easy|Medium|Hard) practice \\d+ in .+?: (.+)$", Pattern.CASE_INSENSITIVE)
                .matcher(prompt);
        if (inSubject.matches()) {
            return capitalize(inSubject.group(2));
        }

        return prompt;
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.substring(0, 1).toUpperCase() + text.substring(1);
    }

    private int practiceStreakDays(AppUser user) {
        List<UserPracticeAttempt> userAttempts = attempts.findByUserIdOrderByAttemptedAtDesc(user.getId());
        if (userAttempts.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.systemDefault();
        Set<LocalDate> practiceDates = new HashSet<LocalDate>();
        for (UserPracticeAttempt attempt : userAttempts) {
            practiceDates.add(attempt.getAttemptedAt().atZone(zone).toLocalDate());
        }

        LocalDate today = LocalDate.now(zone);
        int streak = 0;
        while (practiceDates.contains(today.minusDays(streak))) {
            streak++;
        }
        return streak;
    }

    private int safePracticeStreakDays(AppUser user) {
        try {
            return practiceStreakDays(user);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private int safePracticeMinutesThisWeek(AppUser user) {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(7));
            int totalSeconds = 0;
            for (UserPracticeAttempt attempt : attempts.findByUserIdOrderByAttemptedAtDesc(user.getId())) {
                if (attempt.getAttemptedAt().isBefore(cutoff)) break;
                totalSeconds += Math.max(0, attempt.getTimeTakenSeconds());
            }
            return Math.round(totalSeconds / 60.0f);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private int mistakesToReview(AppUser user) {
        return (int) Math.min(Integer.MAX_VALUE, adaptive.dueCount(user.getId()));
    }

    private List<Map<String, Object>> achievements(AppUser user, List<SkillSummary> skills) {
        List<UserPracticeAttempt> userAttempts = attempts.findByUserIdOrderByAttemptedAtDesc(user.getId());
        int correct = 0;
        int totalSeconds = 0;
        Set<LocalDate> activeDays = new HashSet<LocalDate>();
        for (UserPracticeAttempt attempt : userAttempts) {
            if (Boolean.TRUE.equals(attempt.getCorrect())) correct++;
            totalSeconds += Math.max(0, attempt.getTimeTakenSeconds());
            activeDays.add(attempt.getAttemptedAt().atZone(ZoneId.systemDefault()).toLocalDate());
        }

        int passedModules = 0;
        Set<String> passedTopics = new HashSet<String>();
        for (Map<String, Object> day : history(user)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modules = (List<Map<String, Object>>) day.get("modules");
            for (Map<String, Object> module : modules) {
                if (Boolean.TRUE.equals(module.get("passed"))) {
                    passedTopics.add(String.valueOf(module.get("subject")) + "|" + String.valueOf(module.get("topic")));
                }
            }
        }
        passedModules = passedTopics.size();

        int strongSkills = 0;
        for (SkillSummary skill : skills) if (skill.getScore() >= 80) strongSkills++;
        int streak = safePracticeStreakDays(user);
        int averageSeconds = userAttempts.isEmpty() ? 0 : Math.round(totalSeconds * 1.0f / userAttempts.size());

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(achievement("first-step", "First Step", "Complete your first assessment question.", "footprints", userAttempts.size() >= 1, Math.min(1, userAttempts.size()), 1));
        result.add(achievement("module-master", "Module Master", "Pass a module with at least 80%.", "award", passedModules >= 1, Math.min(1, passedModules), 1));
        result.add(achievement("on-a-roll", "On a Roll", "Maintain a seven-day practice streak.", "flame", streak >= 7, Math.min(7, streak), 7));
        result.add(achievement("century-club", "Century Club", "Answer 100 questions correctly.", "trophy", correct >= 100, Math.min(100, correct), 100));
        result.add(achievement("quick-thinker", "Quick Thinker", "Average 30 seconds or less across 15 answers.", "timer", userAttempts.size() >= 15 && averageSeconds <= 30, Math.min(15, userAttempts.size()), 15));
        result.add(achievement("strong-foundation", "Strong Foundation", "Build at least one skill to Strong status.", "shield-check", strongSkills >= 1, Math.min(1, strongSkills), 1));
        return result;
    }

    private Map<String, Object> achievement(String id, String title, String description, String icon,
                                            boolean earned, int progress, int target) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", id);
        item.put("title", title);
        item.put("description", description);
        item.put("icon", icon);
        item.put("earned", earned);
        item.put("progress", progress);
        item.put("target", target);
        return item;
    }
}

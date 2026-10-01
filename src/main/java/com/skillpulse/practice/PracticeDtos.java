package com.skillpulse.practice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PracticeDtos {
    public static class SubjectResponse {
        private Long id;
        private String slug;
        private String name;
        private String description;
        private long topicCount;
        private long questionCount;

        public SubjectResponse(PracticeSubject subject, long topicCount, long questionCount) {
            this.id = subject.getId();
            this.slug = subject.getSlug();
            this.name = subject.getName();
            this.description = subject.getDescription();
            this.topicCount = topicCount;
            this.questionCount = questionCount;
        }

        public Long getId() { return id; }
        public String getSlug() { return slug; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public long getTopicCount() { return topicCount; }
        public long getQuestionCount() { return questionCount; }
    }

    public static class TopicResponse {
        private Long id;
        private Long subjectId;
        private String name;
        private String description;
        private long questionCount;
        private int moduleOrder;
        private boolean locked;
        private boolean completed;
        private int score;
        private int requiredScore;
        private double masteryProbability;
        private int masteryAttemptCount;

        public TopicResponse(PracticeTopic topic, long questionCount) {
            this(topic, questionCount, false, false, 0, 80);
        }

        public TopicResponse(PracticeTopic topic, long questionCount, boolean locked,
                             boolean completed, int score, int requiredScore) {
            this.id = topic.getId();
            this.subjectId = topic.getSubject().getId();
            this.name = topic.getName();
            this.description = topic.getDescription();
            this.questionCount = questionCount;
            this.moduleOrder = topic.getDisplayOrder();
            this.locked = locked;
            this.completed = completed;
            this.score = score;
            this.requiredScore = requiredScore;
        }

        public Long getId() { return id; }
        public Long getSubjectId() { return subjectId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public long getQuestionCount() { return questionCount; }
        public int getModuleOrder() { return moduleOrder; }
        public boolean isLocked() { return locked; }
        public boolean isCompleted() { return completed; }
        public int getScore() { return score; }
        public int getRequiredScore() { return requiredScore; }
        public double getMasteryProbability() { return masteryProbability; }
        public void setMasteryProbability(double value) { masteryProbability = value; }
        public int getMasteryAttemptCount() { return masteryAttemptCount; }
        public void setMasteryAttemptCount(int value) { masteryAttemptCount = value; }
    }

    public static class QuestionResponse {
        private Long id;
        private Long topicId;
        private String difficulty;
        private String prompt;
        private List<OptionResponse> options = new ArrayList<OptionResponse>();

        public QuestionResponse(PracticeQuestion question, List<QuestionOption> options) {
            this.id = question.getId();
            this.topicId = question.getTopic().getId();
            this.difficulty = question.getDifficulty().name();
            this.prompt = cleanPrompt(question.getPrompt(), question.getTopic().getName());
            for (QuestionOption option : options) {
                this.options.add(new OptionResponse(option));
            }
        }

        private String cleanPrompt(String prompt, String topicName) {
            Matcher forTopic = Pattern.compile("^(Easy|Medium|Hard) practice \\d+ for (.+?): (.+)$", Pattern.CASE_INSENSITIVE)
                    .matcher(prompt);
            if (forTopic.matches()) {
                String text = forTopic.group(3).replace("this topic", topicName);
                return capitalize(text);
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

        public Long getId() { return id; }
        public Long getTopicId() { return topicId; }
        public String getDifficulty() { return difficulty; }
        public String getPrompt() { return prompt; }
        public List<OptionResponse> getOptions() { return options; }
    }

    public static class OptionResponse {
        private Long id;
        private String text;

        public OptionResponse(QuestionOption option) {
            this.id = option.getId();
            this.text = option.getOptionText();
        }

        public Long getId() { return id; }
        public String getText() { return text; }
    }

    public static class SubmitAttemptRequest {
        private String token;
        private Long questionId;
        private Long selectedOptionId;
        private Integer timeTakenSeconds;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }
        public Long getSelectedOptionId() { return selectedOptionId; }
        public void setSelectedOptionId(Long selectedOptionId) { this.selectedOptionId = selectedOptionId; }
        public Integer getTimeTakenSeconds() { return timeTakenSeconds; }
        public void setTimeTakenSeconds(Integer timeTakenSeconds) { this.timeTakenSeconds = timeTakenSeconds; }
    }

    public static class AttemptResponse {
        private boolean correct;
        private String explanation;
        private Long correctOptionId;
        private String correctOptionText;

        public AttemptResponse(boolean correct, String explanation, QuestionOption correctOption) {
            this.correct = correct;
            this.explanation = explanation;
            this.correctOptionId = correctOption.getId();
            this.correctOptionText = correctOption.getOptionText();
        }

        public boolean isCorrect() { return correct; }
        public String getExplanation() { return explanation; }
        public Long getCorrectOptionId() { return correctOptionId; }
        public String getCorrectOptionText() { return correctOptionText; }
    }

    public static class PlanResponse {
        private Long subjectId;
        private String subjectName;
        private String status;
        private int healthScore;
        private int dailyMinutes;
        private long questionCount;
        private long attemptedCount;
        private long assessmentQuestionCount;
        private List<PlanStepResponse> steps = new ArrayList<PlanStepResponse>();

        public PlanResponse(Long subjectId, String subjectName, String status, int healthScore,
                            int dailyMinutes, long questionCount, long attemptedCount,
                            long assessmentQuestionCount) {
            this.subjectId = subjectId;
            this.subjectName = subjectName;
            this.status = status;
            this.healthScore = healthScore;
            this.dailyMinutes = dailyMinutes;
            this.questionCount = questionCount;
            this.attemptedCount = attemptedCount;
            this.assessmentQuestionCount = assessmentQuestionCount;
        }

        public Long getSubjectId() { return subjectId; }
        public String getSubjectName() { return subjectName; }
        public String getStatus() { return status; }
        public int getHealthScore() { return healthScore; }
        public int getDailyMinutes() { return dailyMinutes; }
        public long getQuestionCount() { return questionCount; }
        public long getAttemptedCount() { return attemptedCount; }
        public long getAssessmentQuestionCount() { return assessmentQuestionCount; }
        public List<PlanStepResponse> getSteps() { return steps; }
    }

    public static class PlanStepResponse {
        private Long topicId;
        private String title;
        private String activity;
        private String goal;
        private int minutes;
        private int moduleOrder;
        private boolean locked;
        private boolean completed;
        private int score;
        private long questionCount;

        public PlanStepResponse(Long topicId, String title, String activity, String goal, int minutes) {
            this(topicId, title, activity, goal, minutes, 0, false, false, 0, 0);
        }

        public PlanStepResponse(Long topicId, String title, String activity, String goal, int minutes,
                                int moduleOrder, boolean locked, boolean completed, int score, long questionCount) {
            this.topicId = topicId;
            this.title = title;
            this.activity = activity;
            this.goal = goal;
            this.minutes = minutes;
            this.moduleOrder = moduleOrder;
            this.locked = locked;
            this.completed = completed;
            this.score = score;
            this.questionCount = questionCount;
        }

        public Long getTopicId() { return topicId; }
        public String getTitle() { return title; }
        public String getActivity() { return activity; }
        public String getGoal() { return goal; }
        public int getMinutes() { return minutes; }
        public int getModuleOrder() { return moduleOrder; }
        public boolean isLocked() { return locked; }
        public boolean isCompleted() { return completed; }
        public int getScore() { return score; }
        public long getQuestionCount() { return questionCount; }
    }

    public static class AssessmentResponse {
        private Long subjectId;
        private String subjectName;
        private String title;
        private String instruction;
        private Long topicId;
        private String topicName;
        private int requiredScore = 80;
        private int timeLimitSeconds;
        private boolean reviewMode;
        private List<QuestionResponse> questions = new ArrayList<QuestionResponse>();

        public AssessmentResponse(PracticeSubject subject, List<QuestionResponse> questions) {
            this.subjectId = subject.getId();
            this.subjectName = subject.getName();
            this.title = subject.getName() + " Assessment";
            this.instruction = "Answer these mixed questions to update your skill health.";
            this.questions = questions;
            this.timeLimitSeconds = Math.max(300, questions.size() * 60);
        }

        public AssessmentResponse(PracticeTopic topic, List<QuestionResponse> questions) {
            this.subjectId = topic.getSubject().getId();
            this.subjectName = topic.getSubject().getName();
            this.topicId = topic.getId();
            this.topicName = topic.getName();
            this.title = topic.getSubject().getName() + " - Module " + topic.getDisplayOrder();
            this.instruction = topic.getName() + ": answer all 15 questions and score at least 80% to unlock the next module.";
            this.questions = questions;
            this.timeLimitSeconds = Math.max(300, questions.size() * 60);
        }

        public AssessmentResponse(String title, String instruction, List<QuestionResponse> questions, boolean reviewMode) {
            this.title = title;
            this.subjectName = "Mistake Review";
            this.instruction = instruction;
            this.questions = questions;
            this.reviewMode = reviewMode;
            this.timeLimitSeconds = Math.max(300, questions.size() * 60);
        }

        public Long getSubjectId() { return subjectId; }
        public String getSubjectName() { return subjectName; }
        public String getTitle() { return title; }
        public String getInstruction() { return instruction; }
        public Long getTopicId() { return topicId; }
        public String getTopicName() { return topicName; }
        public int getRequiredScore() { return requiredScore; }
        public int getTimeLimitSeconds() { return timeLimitSeconds; }
        public boolean isReviewMode() { return reviewMode; }
        public List<QuestionResponse> getQuestions() { return questions; }
    }
}

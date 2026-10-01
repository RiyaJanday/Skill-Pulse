package com.skillpulse.admin;

import java.util.ArrayList;
import java.util.List;

public class AdminQuestionDtos {
    public static class QuestionRequest {
        private Long topicId;
        private String difficulty;
        private String prompt;
        private String explanation;
        private List<String> options = new ArrayList<String>();
        private Integer correctIndex;
        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getExplanation() { return explanation; }
        public void setExplanation(String explanation) { this.explanation = explanation; }
        public List<String> getOptions() { return options; }
        public void setOptions(List<String> options) { this.options = options; }
        public Integer getCorrectIndex() { return correctIndex; }
        public void setCorrectIndex(Integer correctIndex) { this.correctIndex = correctIndex; }
    }
    public static class OptionRow {
        private final Long id;
        private final String text;
        private final boolean correct;
        public OptionRow(Long id, String text, boolean correct) { this.id=id; this.text=text; this.correct=correct; }
        public Long getId() { return id; }
        public String getText() { return text; }
        public boolean isCorrect() { return correct; }
    }
    public static class QuestionRow {
        private Long id, subjectId, topicId;
        private String subjectName, topicName, difficulty, prompt, explanation;
        private boolean active;
        private long attempts;
        private int accuracy, averageTimeSeconds;
        private final List<OptionRow> options = new ArrayList<OptionRow>();
        public Long getId(){return id;} public void setId(Long v){id=v;}
        public Long getSubjectId(){return subjectId;} public void setSubjectId(Long v){subjectId=v;}
        public Long getTopicId(){return topicId;} public void setTopicId(Long v){topicId=v;}
        public String getSubjectName(){return subjectName;} public void setSubjectName(String v){subjectName=v;}
        public String getTopicName(){return topicName;} public void setTopicName(String v){topicName=v;}
        public String getDifficulty(){return difficulty;} public void setDifficulty(String v){difficulty=v;}
        public String getPrompt(){return prompt;} public void setPrompt(String v){prompt=v;}
        public String getExplanation(){return explanation;} public void setExplanation(String v){explanation=v;}
        public boolean isActive(){return active;} public void setActive(boolean v){active=v;}
        public long getAttempts(){return attempts;} public void setAttempts(long v){attempts=v;}
        public int getAccuracy(){return accuracy;} public void setAccuracy(int v){accuracy=v;}
        public int getAverageTimeSeconds(){return averageTimeSeconds;} public void setAverageTimeSeconds(int v){averageTimeSeconds=v;}
        public List<OptionRow> getOptions(){return options;}
    }
}

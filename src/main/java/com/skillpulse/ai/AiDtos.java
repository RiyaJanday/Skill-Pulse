package com.skillpulse.ai;

/** Request bodies for the AI endpoints. Responses are plain maps passed through from the ml-service. */
public class AiDtos {
    public static class ExplainRequest {
        private Long questionId;
        private Long selectedOptionId;

        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }
        public Long getSelectedOptionId() { return selectedOptionId; }
        public void setSelectedOptionId(Long selectedOptionId) { this.selectedOptionId = selectedOptionId; }
    }

    public static class NudgeRequest {
        private Long userId;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    public static class GenerateRequest {
        private Long topicId;
        private String difficulty;
        private Integer count;

        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }
}

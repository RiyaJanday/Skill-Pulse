package com.skillpulse.chat;

/** Request bodies for the study-chat endpoints. Responses are plain maps. */
public class ChatDtos {
    /**
     * A new learner message. conversationId is null to start a new chat. There is deliberately NO history field:
     * the server builds the history from the messages it stored itself.
     */
    public static class SendRequest {
        private Long conversationId;
        private String message;

        public Long getConversationId() { return conversationId; }
        public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}

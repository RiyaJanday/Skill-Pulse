package com.skillpulse.chat;

/**
 * Thrown when a chat request must be refused for capacity reasons: 429 (this learner is sending too fast or already
 * has a reply being written) or 503 (the server is busy). GlobalExceptionHandler turns it into a JSON error.
 */
public class ChatLimitException extends RuntimeException {
    private final int status;
    private final long retryAfterSeconds;

    public ChatLimitException(int status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getStatus() { return status; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}

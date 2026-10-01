package com.skillpulse.config;

import java.time.Instant;
import java.util.Map;

public class ApiErrorResponse {
    private final Instant timestamp = Instant.now();
    private final int status;
    private final String code;
    private final String message;
    private final String path;
    private final Map<String, String> fieldErrors;

    public ApiErrorResponse(int status, String code, String message, String path,
                            Map<String, String> fieldErrors) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.path = path;
        this.fieldErrors = fieldErrors;
    }

    public Instant getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getPath() { return path; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}

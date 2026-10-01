package com.skillpulse.ai;

import com.skillpulse.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/** Learner-facing AI endpoints: study coach, answer explanations and the engagement check. */
@RestController
public class AiController {
    private final AuthService auth;
    private final AiInsightsService ai;

    public AiController(AuthService auth, AiInsightsService ai) {
        this.auth = auth;
        this.ai = ai;
    }

    @GetMapping("/api/ai/me/overview")
    public Map<String, Object> overview(@RequestParam(value = "token", required = false) String token) {
        return ai.overview(auth.requireUser(token));
    }

    // The study chat lives in com.skillpulse.chat (/api/chat/**). The old /api/ai/tutor/chat endpoint took its history
    // from the browser, which let a client forge earlier "assistant" turns, so it was removed.

    @PostMapping("/api/ai/tutor/explain")
    public ResponseEntity<Map<String, Object>> explain(@RequestParam(value = "token", required = false) String token,
                                                       @RequestBody AiDtos.ExplainRequest request) {
        return respond(ai.explain(auth.requireUser(token), request),
                "The AI explanation is unavailable right now. Use the reference explanation above.");
    }

    /** 200 with the ML answer, or 503 with a friendly message when the ML service could not answer. */
    static ResponseEntity<Map<String, Object>> respond(Map<String, Object> result, String unavailableMessage) {
        if (result == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Collections.<String, Object>singletonMap("message", unavailableMessage));
        }
        return ResponseEntity.ok(result);
    }
}

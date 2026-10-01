package com.skillpulse.ai;

import com.skillpulse.auth.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Administrator AI endpoints. /api/admin/** already requires the ADMIN role in SecurityConfig; requireAdmin adds a
 * second check so the rule still holds if the URL rules are ever changed.
 */
@RestController
public class AdminAiController {
    private static final String UNAVAILABLE = "The ML service is unavailable right now. Please try again shortly.";

    private final AuthService auth;
    private final AiInsightsService ai;

    public AdminAiController(AuthService auth, AiInsightsService ai) {
        this.auth = auth;
        this.ai = ai;
    }

    @GetMapping("/api/admin/ai/risk")
    public ResponseEntity<Map<String, Object>> risk(@RequestParam(value = "token", required = false) String token) {
        auth.requireAdmin(token);
        return AiController.respond(ai.cohortRisk(), UNAVAILABLE);
    }

    @GetMapping("/api/admin/ai/segments")
    public ResponseEntity<Map<String, Object>> segments(@RequestParam(value = "token", required = false) String token,
                                                        @RequestParam(value = "k", defaultValue = "4") int k) {
        auth.requireAdmin(token);
        return AiController.respond(ai.cohortSegments(k), UNAVAILABLE);
    }

    @GetMapping("/api/admin/ai/question-quality")
    public ResponseEntity<Map<String, Object>> questionQuality(@RequestParam(value = "token", required = false) String token) {
        auth.requireAdmin(token);
        return AiController.respond(ai.questionQuality(), UNAVAILABLE);
    }

    @PostMapping("/api/admin/ai/fit-bkt")
    public ResponseEntity<Map<String, Object>> fitBkt(@RequestParam(value = "token", required = false) String token) {
        auth.requireAdmin(token);
        return AiController.respond(ai.fitBkt(), UNAVAILABLE);
    }

    /** Anonymous answer history for retraining the models on real data (python train.py --attempts attempts.csv). */
    @GetMapping("/api/admin/ai/export-attempts.csv")
    public ResponseEntity<String> exportAttempts(@RequestParam(value = "token", required = false) String token) {
        auth.requireAdmin(token);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attempts.csv\"");
        return new ResponseEntity<String>(ai.exportAttemptsCsv(), headers, HttpStatus.OK);
    }

    @PostMapping("/api/admin/ai/nudge")
    public ResponseEntity<Map<String, Object>> nudge(@RequestParam(value = "token", required = false) String token,
                                                     @RequestBody AiDtos.NudgeRequest request) {
        auth.requireAdmin(token);
        return AiController.respond(ai.nudge(request == null ? null : request.getUserId()),
                "The AI writer is unavailable right now. Please try again in a moment.");
    }

    @PostMapping("/api/admin/ai/generate-questions")
    public ResponseEntity<Map<String, Object>> generate(@RequestParam(value = "token", required = false) String token,
                                                        @RequestBody AiDtos.GenerateRequest request) {
        auth.requireAdmin(token);
        return AiController.respond(ai.generateQuestions(request),
                "AI question drafting is unavailable right now. Please try again in a moment.");
    }
}

package com.skillpulse.ai;

import com.skillpulse.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** POST /api/ai/skill-gap: compares a pasted job description with the signed-in learner's practice. */
@RestController
public class SkillGapController {
    private final AuthService auth;
    private final SkillGapService service;

    public SkillGapController(AuthService auth, SkillGapService service) {
        this.auth = auth;
        this.service = service;
    }

    @PostMapping("/api/ai/skill-gap")
    public ResponseEntity<Map<String, Object>> skillGap(@RequestBody SkillGapRequest request) {
        return AiController.respond(
                service.analyze(auth.requireUser(null), request == null ? null : request.getJobDescription()),
                "The skill-gap analysis is unavailable right now. Please try again in a moment.");
    }

    public static class SkillGapRequest {
        private String jobDescription;

        public String getJobDescription() { return jobDescription; }
        public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }
    }
}

package com.skillpulse.integration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thin pass-through to the Python ML service, keeping the existing /api/ml/* URLs working. */
@RestController
public class MlProxyController {
    private final MlServiceClient ml;

    public MlProxyController(MlServiceClient ml) {
        this.ml = ml;
    }

    @GetMapping("/api/ml/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ready", true);
        result.put("mlService", ml.isUp() ? "up" : "down");
        return result;
    }

    @PostMapping("/api/ml/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<String, Object>(request);
        body.remove("token"); // never forward legacy credentials to another service
        Map<String, Object> result = ml.analyze(body);
        if (result == null) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("message", "The ML service is unavailable right now. Please try again shortly.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }
        return ResponseEntity.ok(result);
    }
}

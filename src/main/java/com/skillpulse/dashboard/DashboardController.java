package com.skillpulse.dashboard;

import com.skillpulse.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
public class DashboardController {
    private final AuthService authService;
    private final DashboardService dashboardService;

    public DashboardController(AuthService authService, DashboardService dashboardService) {
        this.authService = authService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/dashboard/summary")
    public ResponseEntity<?> summary(@RequestParam("token") String token) {
        return authService.findByToken(token)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(dashboardService.summary(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Collections.singletonMap("message", "Session expired. Please sign in again.")));
    }

    @GetMapping("/api/dashboard/history")
    public ResponseEntity<?> history(@RequestParam("token") String token) {
        return authService.findByToken(token)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(dashboardService.history(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Collections.singletonMap("message", "Session expired. Please sign in again.")));
    }

    @GetMapping("/api/skills")
    public Object skills() {
        return dashboardService.skills();
    }
}

package com.skillpulse.timetable;

import com.skillpulse.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Weekly timetable endpoints. /api/timetable/** requires a signed-in learner (see SecurityConfig). */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController {
    private final TimetableService service;
    private final AuthService auth;

    public TimetableController(TimetableService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> view() {
        return ResponseEntity.ok(service.view(auth.requireUser(null)));
    }

    /** "Target done": ticks every task of the day and marks it complete. */
    @PostMapping("/days/{dayId}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable Long dayId) {
        return ResponseEntity.ok(service.complete(auth.requireUser(null), dayId));
    }

    /** Minutes available today, or "can't study today". Rebalances with a deterministic rule. */
    @PostMapping("/days/{dayId}/adjust")
    public ResponseEntity<Map<String, Object>> adjust(@PathVariable Long dayId, @RequestBody AdjustRequest request) {
        return ResponseEntity.ok(service.adjust(auth.requireUser(null), dayId,
                request.getMinutesAvailable(), Boolean.TRUE.equals(request.getCantStudyToday())));
    }

    public static class AdjustRequest {
        private Integer minutesAvailable;
        private Boolean cantStudyToday;

        public Integer getMinutesAvailable() { return minutesAvailable; }
        public void setMinutesAvailable(Integer minutesAvailable) { this.minutesAvailable = minutesAvailable; }
        public Boolean getCantStudyToday() { return cantStudyToday; }
        public void setCantStudyToday(Boolean cantStudyToday) { this.cantStudyToday = cantStudyToday; }
    }
}

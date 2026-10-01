package com.skillpulse.notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;
import java.util.Map;

@RestController
public class NotificationController {
    private final NotificationPreferenceService service;
    public NotificationController(NotificationPreferenceService service) { this.service = service; }
    @GetMapping("/api/notifications/preferences")
    public NotificationDtos.PreferenceResponse get(@RequestParam("token") String token) { return service.get(token); }
    @PutMapping("/api/notifications/preferences")
    public NotificationDtos.PreferenceResponse update(@RequestParam("token") String token,
            @RequestBody NotificationDtos.PreferenceRequest request) { return service.update(token, request); }
}

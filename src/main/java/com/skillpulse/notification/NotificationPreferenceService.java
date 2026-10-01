package com.skillpulse.notification;

import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPreferenceService {
    private final AuthService auth;
    private final NotificationPreferenceRepository preferences;
    public NotificationPreferenceService(AuthService auth, NotificationPreferenceRepository preferences) {
        this.auth = auth; this.preferences = preferences;
    }
    @Transactional public NotificationDtos.PreferenceResponse get(String token) {
        return new NotificationDtos.PreferenceResponse(forUser(auth.requireUser(token)));
    }
    @Transactional public NotificationDtos.PreferenceResponse update(String token, NotificationDtos.PreferenceRequest request) {
        NotificationPreference value = forUser(auth.requireUser(token));
        if (request == null) throw new IllegalArgumentException("Notification preferences are required.");
        if (request.getSkillDecayAlerts() != null) value.setSkillDecayAlerts(request.getSkillDecayAlerts());
        if (request.getDailyPracticeReminders() != null) value.setDailyPracticeReminders(request.getDailyPracticeReminders());
        if (request.getWeeklyProgressReport() != null) value.setWeeklyProgressReport(request.getWeeklyProgressReport());
        if (request.getStreakNotifications() != null) value.setStreakNotifications(request.getStreakNotifications());
        return new NotificationDtos.PreferenceResponse(preferences.save(value));
    }
    @Transactional public NotificationPreference forUser(AppUser user) {
        return preferences.findByUserId(user.getId()).orElseGet(() -> {
            NotificationPreference value = new NotificationPreference();
            value.setUser(user);
            return preferences.save(value);
        });
    }
}

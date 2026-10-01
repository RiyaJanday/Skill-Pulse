package com.skillpulse.notification;

import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.UserRepository;
import com.skillpulse.dashboard.DashboardService;
import com.skillpulse.dashboard.SkillSummary;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.practice.UserPracticeAttempt;
import com.skillpulse.practice.UserPracticeAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private final UserRepository users;
    private final UserPracticeAttemptRepository attempts;
    private final NotificationPreferenceService preferenceService;
    private final NotificationPreferenceRepository preferences;
    private final NotificationMailService mail;
    private final DashboardService dashboard;
    private final MlServiceClient ml;
    private final boolean aiWeeklyNote;
    private final ZoneId zone;

    public NotificationScheduler(UserRepository users, UserPracticeAttemptRepository attempts,
            NotificationPreferenceService preferenceService, NotificationPreferenceRepository preferences,
            NotificationMailService mail, DashboardService dashboard, MlServiceClient ml,
            @Value("${skillpulse.notifications.ai-weekly-note:true}") boolean aiWeeklyNote,
            @Value("${skillpulse.notifications.zone:Asia/Kolkata}") String zoneId) {
        this.users = users; this.attempts = attempts; this.preferenceService = preferenceService;
        this.preferences = preferences; this.mail = mail; this.dashboard = dashboard; this.ml = ml;
        this.aiWeeklyNote = aiWeeklyNote; this.zone = ZoneId.of(zoneId);
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "${skillpulse.notifications.zone:Asia/Kolkata}")
    public void dailyPracticeReminders() {
        LocalDate today = LocalDate.now(zone);
        for (AppUser user : users.findAll()) safely(() -> {
            NotificationPreference value = preferenceService.forUser(user);
            if (!Boolean.TRUE.equals(value.getDailyPracticeReminders()) || today.equals(value.getLastDailyReminderDate())) return;
            if (!practiceDates(user).contains(today)) {
                mail.send(user, "Your SkillPulse practice is waiting", greeting(user)
                        + "\n\nYou have not completed an assessment today. A short practice session will keep your skill health moving forward.\n\n- SkillPulse");
                value.setLastDailyReminderDate(today); preferences.save(value);
            }
        });
    }

    @Scheduled(cron = "0 0 20 * * *", zone = "${skillpulse.notifications.zone:Asia/Kolkata}")
    public void streakWarnings() {
        LocalDate today = LocalDate.now(zone);
        for (AppUser user : users.findAll()) safely(() -> {
            NotificationPreference value = preferenceService.forUser(user);
            if (!Boolean.TRUE.equals(value.getStreakNotifications()) || today.equals(value.getLastStreakNotificationDate())) return;
            Set<LocalDate> dates = practiceDates(user);
            if (!dates.contains(today) && dates.contains(today.minusDays(1))) {
                int streak = streakEndingOn(dates, today.minusDays(1));
                mail.send(user, "Protect your " + streak + "-day SkillPulse streak", greeting(user)
                        + "\n\nYour " + streak + "-day practice streak is about to break. Complete one assessment today to keep it active.\n\n- SkillPulse");
                value.setLastStreakNotificationDate(today); preferences.save(value);
            }
        });
    }

    @Scheduled(cron = "0 15 20 * * *", zone = "${skillpulse.notifications.zone:Asia/Kolkata}")
    public void skillDecayAlerts() {
        LocalDate today = LocalDate.now(zone);
        for (AppUser user : users.findAll()) safely(() -> {
            NotificationPreference value = preferenceService.forUser(user);
            if (!Boolean.TRUE.equals(value.getSkillDecayAlerts()) || today.equals(value.getLastDecayAlertDate())) return;
            @SuppressWarnings("unchecked") List<SkillSummary> skills = (List<SkillSummary>) dashboard.summary(user).get("skills");
            StringBuilder atRisk = new StringBuilder();
            for (SkillSummary skill : skills) if (skill.getScore() < 60) {
                if (atRisk.length() > 0) atRisk.append("\n");
                atRisk.append("- ").append(skill.getName()).append(": ").append(skill.getScore()).append("%");
            }
            if (atRisk.length() > 0) {
                mail.send(user, "SkillPulse skills need attention", greeting(user)
                        + "\n\nThese skills are currently below 60% health:\n" + atRisk
                        + "\n\nOpen your practice plan to strengthen them.\n\n- SkillPulse");
                value.setLastDecayAlertDate(today); preferences.save(value);
            }
        });
    }

    @Scheduled(cron = "0 0 8 * * MON", zone = "${skillpulse.notifications.zone:Asia/Kolkata}")
    public void weeklyProgressReports() {
        LocalDate today = LocalDate.now(zone);
        // One-element array so the lambda can switch the AI note off for the rest of this run after a failure.
        final boolean[] aiAvailable = {aiWeeklyNote};
        for (AppUser user : users.findAll()) safely(() -> {
            NotificationPreference value = preferenceService.forUser(user);
            if (!Boolean.TRUE.equals(value.getWeeklyProgressReport()) || today.equals(value.getLastWeeklyReportDate())) return;
            Map<String, Object> summary = dashboard.summary(user);
            String coachNote = "";
            if (aiAvailable[0]) {
                coachNote = weeklyCoachNote(user, summary);
                // If the AI could not answer, skip it for everybody else this week instead of waiting per learner.
                if (coachNote.isEmpty()) aiAvailable[0] = false;
            }
            mail.send(user, "Your weekly SkillPulse progress", greeting(user) + "\n\nHere is your weekly snapshot:\n"
                    + "- Overall skill health: " + summary.get("overallHealth") + "%\n"
                    + "- Practice streak: " + summary.get("practiceStreakDays") + " days\n"
                    + "- Practice time: " + summary.get("practiceMinutesThisWeek") + " minutes\n"
                    + "- Skills needing attention: " + summary.get("skillsNeedingAttention")
                    + (coachNote.isEmpty() ? "" : "\n\nA note from your coach:\n" + coachNote)
                    + "\n\nKeep learning consistently.\n\n- SkillPulse");
            value.setLastWeeklyReportDate(today); preferences.save(value);
        });
    }

    /**
     * A short AI-written note for the weekly email (ml-service /coach/weekly-note). The numbers in the email stay
     * exactly as the dashboard computed them; the AI only adds words. Returns "" when the ML service is unavailable.
     */
    private String weeklyCoachNote(AppUser user, Map<String, Object> summary) {
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("name", user.getName());
            body.put("overallHealth", summary.get("overallHealth"));
            body.put("streakDays", summary.get("practiceStreakDays"));
            body.put("minutesThisWeek", summary.get("practiceMinutesThisWeek"));
            body.put("skillsNeedingAttention", summary.get("skillsNeedingAttention"));
            Map<String, Object> reply = ml.callSlow("/coach/weekly-note", body);
            Object note = reply == null ? null : reply.get("note");
            return note instanceof String ? ((String) note).trim() : "";
        } catch (RuntimeException ex) {
            log.warn("Weekly coach note skipped: {}", ex.toString());
            return "";
        }
    }

    private Set<LocalDate> practiceDates(AppUser user) {
        Set<LocalDate> dates = new HashSet<LocalDate>();
        for (UserPracticeAttempt attempt : attempts.findByUserIdOrderByAttemptedAtDesc(user.getId()))
            dates.add(attempt.getAttemptedAt().atZone(zone).toLocalDate());
        return dates;
    }
    private int streakEndingOn(Set<LocalDate> dates, LocalDate date) {
        int streak = 0; while (dates.contains(date.minusDays(streak))) streak++; return streak;
    }
    private String greeting(AppUser user) { return "Hello " + user.getName() + ","; }
    private void safely(Runnable task) {
        try { task.run(); } catch (RuntimeException ex) { log.error("Notification delivery failed", ex); }
    }
}

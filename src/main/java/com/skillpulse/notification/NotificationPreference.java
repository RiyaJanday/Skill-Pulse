package com.skillpulse.notification;

import com.skillpulse.auth.AppUser;
import javax.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;
    @Column(nullable = false) private Boolean skillDecayAlerts = true;
    @Column(nullable = false) private Boolean dailyPracticeReminders = true;
    @Column(nullable = false) private Boolean weeklyProgressReport = false;
    @Column(nullable = false) private Boolean streakNotifications = true;
    private LocalDate lastDecayAlertDate;
    private LocalDate lastDailyReminderDate;
    private LocalDate lastWeeklyReportDate;
    private LocalDate lastStreakNotificationDate;

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser value) { user = value; }
    public Boolean getSkillDecayAlerts() { return skillDecayAlerts; }
    public void setSkillDecayAlerts(Boolean value) { skillDecayAlerts = value; }
    public Boolean getDailyPracticeReminders() { return dailyPracticeReminders; }
    public void setDailyPracticeReminders(Boolean value) { dailyPracticeReminders = value; }
    public Boolean getWeeklyProgressReport() { return weeklyProgressReport; }
    public void setWeeklyProgressReport(Boolean value) { weeklyProgressReport = value; }
    public Boolean getStreakNotifications() { return streakNotifications; }
    public void setStreakNotifications(Boolean value) { streakNotifications = value; }
    public LocalDate getLastDecayAlertDate() { return lastDecayAlertDate; }
    public void setLastDecayAlertDate(LocalDate value) { lastDecayAlertDate = value; }
    public LocalDate getLastDailyReminderDate() { return lastDailyReminderDate; }
    public void setLastDailyReminderDate(LocalDate value) { lastDailyReminderDate = value; }
    public LocalDate getLastWeeklyReportDate() { return lastWeeklyReportDate; }
    public void setLastWeeklyReportDate(LocalDate value) { lastWeeklyReportDate = value; }
    public LocalDate getLastStreakNotificationDate() { return lastStreakNotificationDate; }
    public void setLastStreakNotificationDate(LocalDate value) { lastStreakNotificationDate = value; }
}

package com.skillpulse.notification;

public class NotificationDtos {
    public static class PreferenceRequest {
        private Boolean skillDecayAlerts;
        private Boolean dailyPracticeReminders;
        private Boolean weeklyProgressReport;
        private Boolean streakNotifications;
        public Boolean getSkillDecayAlerts() { return skillDecayAlerts; }
        public void setSkillDecayAlerts(Boolean value) { skillDecayAlerts = value; }
        public Boolean getDailyPracticeReminders() { return dailyPracticeReminders; }
        public void setDailyPracticeReminders(Boolean value) { dailyPracticeReminders = value; }
        public Boolean getWeeklyProgressReport() { return weeklyProgressReport; }
        public void setWeeklyProgressReport(Boolean value) { weeklyProgressReport = value; }
        public Boolean getStreakNotifications() { return streakNotifications; }
        public void setStreakNotifications(Boolean value) { streakNotifications = value; }
    }
    public static class PreferenceResponse {
        private final boolean skillDecayAlerts;
        private final boolean dailyPracticeReminders;
        private final boolean weeklyProgressReport;
        private final boolean streakNotifications;
        public PreferenceResponse(NotificationPreference value) {
            skillDecayAlerts = Boolean.TRUE.equals(value.getSkillDecayAlerts());
            dailyPracticeReminders = Boolean.TRUE.equals(value.getDailyPracticeReminders());
            weeklyProgressReport = Boolean.TRUE.equals(value.getWeeklyProgressReport());
            streakNotifications = Boolean.TRUE.equals(value.getStreakNotifications());
        }
        public boolean isSkillDecayAlerts() { return skillDecayAlerts; }
        public boolean isDailyPracticeReminders() { return dailyPracticeReminders; }
        public boolean isWeeklyProgressReport() { return weeklyProgressReport; }
        public boolean isStreakNotifications() { return streakNotifications; }
    }
}

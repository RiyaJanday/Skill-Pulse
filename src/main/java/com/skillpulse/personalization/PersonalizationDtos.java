package com.skillpulse.personalization;

import javax.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;

public class PersonalizationDtos {
    public static class PreferenceRequest {
        @NotBlank private String explanationStyle;
        @NotBlank @Size(max=300) private String learningGoal;
        @Min(10) @Max(180) private Integer dailyMinutes;
        @NotBlank private String preferredDifficulty;
        @NotBlank @Size(max=40) private String language;
        @NotBlank @Size(max=120) private String selectedSubject;
        public String getExplanationStyle(){return explanationStyle;} public void setExplanationStyle(String v){explanationStyle=v;}
        public String getLearningGoal(){return learningGoal;} public void setLearningGoal(String v){learningGoal=v;}
        public Integer getDailyMinutes(){return dailyMinutes;} public void setDailyMinutes(Integer v){dailyMinutes=v;}
        public String getPreferredDifficulty(){return preferredDifficulty;} public void setPreferredDifficulty(String v){preferredDifficulty=v;}
        public String getLanguage(){return language;} public void setLanguage(String v){language=v;}
        public String getSelectedSubject(){return selectedSubject;} public void setSelectedSubject(String v){selectedSubject=v;}
    }
    public static class PreferenceResponse {
        private String explanationStyle,learningGoal,preferredDifficulty,language,selectedSubject; private int dailyMinutes;
        public PreferenceResponse(){} public PreferenceResponse(LearningPreference p){explanationStyle=p.getExplanationStyle();learningGoal=p.getLearningGoal();dailyMinutes=p.getDailyMinutes();preferredDifficulty=p.getPreferredDifficulty();language=p.getLanguage();selectedSubject=p.getSelectedSubject();}
        public String getExplanationStyle(){return explanationStyle;} public String getLearningGoal(){return learningGoal;}
        public int getDailyMinutes(){return dailyMinutes;} public String getPreferredDifficulty(){return preferredDifficulty;} public String getLanguage(){return language;}
        public String getSelectedSubject(){return selectedSubject;}
    }
    public static class PlanDay {
        private int day; private String focus,objective,explanationStyle,difficulty; private int minutes; private List<String> activities=new ArrayList<String>();
        public int getDay(){return day;} public void setDay(int v){day=v;} public String getFocus(){return focus;} public void setFocus(String v){focus=v;}
        public String getObjective(){return objective;} public void setObjective(String v){objective=v;} public String getExplanationStyle(){return explanationStyle;} public void setExplanationStyle(String v){explanationStyle=v;}
        public String getDifficulty(){return difficulty;} public void setDifficulty(String v){difficulty=v;} public int getMinutes(){return minutes;} public void setMinutes(int v){minutes=v;}
        public List<String> getActivities(){return activities;} public void setActivities(List<String> v){activities=v;}
    }
    public static class PlanResponse {
        private String summary,primaryWeakness,engine; private List<String> strengths=new ArrayList<String>(); private List<PlanDay> days=new ArrayList<PlanDay>();
        public String getSummary(){return summary;} public void setSummary(String v){summary=v;} public String getPrimaryWeakness(){return primaryWeakness;} public void setPrimaryWeakness(String v){primaryWeakness=v;}
        public String getEngine(){return engine;} public void setEngine(String v){engine=v;} public List<String> getStrengths(){return strengths;} public void setStrengths(List<String> v){strengths=v;}
        public List<PlanDay> getDays(){return days;} public void setDays(List<PlanDay> v){days=v;}
    }
}

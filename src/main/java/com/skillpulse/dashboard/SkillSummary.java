package com.skillpulse.dashboard;

public class SkillSummary {
    private String name;
    private int score;
    private String status;
    private String recommendation;

    public SkillSummary() {
    }

    public SkillSummary(String name, int score, String status, String recommendation) {
        this.name = name;
        this.score = score;
        this.status = status;
        this.recommendation = recommendation;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
}

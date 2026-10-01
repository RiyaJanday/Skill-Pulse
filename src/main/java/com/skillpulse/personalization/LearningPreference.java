package com.skillpulse.personalization;

import com.skillpulse.auth.AppUser;
import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "learning_preferences", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class LearningPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(nullable = false) private String explanationStyle = "EXAMPLE_FIRST";
    @Column(nullable = false) private String learningGoal = "Build strong practical skills";
    @Column(nullable = false) private Integer dailyMinutes = 25;
    @Column(nullable = false) private String preferredDifficulty = "ADAPTIVE";
    @Column(nullable = false) private String language = "English";
    @Column private String selectedSubject = "Java / Spring Boot";
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    public Long getId(){return id;} public AppUser getUser(){return user;} public void setUser(AppUser v){user=v;}
    public String getExplanationStyle(){return explanationStyle;} public void setExplanationStyle(String v){explanationStyle=v;}
    public String getLearningGoal(){return learningGoal;} public void setLearningGoal(String v){learningGoal=v;}
    public Integer getDailyMinutes(){return dailyMinutes;} public void setDailyMinutes(Integer v){dailyMinutes=v;}
    public String getPreferredDifficulty(){return preferredDifficulty;} public void setPreferredDifficulty(String v){preferredDifficulty=v;}
    public String getLanguage(){return language;} public void setLanguage(String v){language=v;}
    public String getSelectedSubject(){return selectedSubject==null||selectedSubject.trim().isEmpty()?"Java / Spring Boot":selectedSubject;} public void setSelectedSubject(String v){selectedSubject=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
}

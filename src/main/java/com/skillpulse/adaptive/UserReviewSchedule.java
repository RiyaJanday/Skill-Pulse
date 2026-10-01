package com.skillpulse.adaptive;
import com.skillpulse.auth.AppUser; import com.skillpulse.practice.PracticeQuestion;
import javax.persistence.*; import java.time.Instant;
@Entity @Table(name="user_review_schedule",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","question_id"}))
public class UserReviewSchedule {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id") private AppUser user;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="question_id") private PracticeQuestion question;
 @Column(nullable=false) private int repetitions=0,intervalDays=0,lastQuality=0; @Column(nullable=false) private double easeFactor=2.5; private Instant lastReviewedAt; @Column(nullable=false) private Instant dueAt=Instant.now(),updatedAt=Instant.now();
 public Long getId(){return id;} public AppUser getUser(){return user;} public void setUser(AppUser v){user=v;} public PracticeQuestion getQuestion(){return question;} public void setQuestion(PracticeQuestion v){question=v;} public int getRepetitions(){return repetitions;} public void setRepetitions(int v){repetitions=v;} public int getIntervalDays(){return intervalDays;} public void setIntervalDays(int v){intervalDays=v;} public int getLastQuality(){return lastQuality;} public void setLastQuality(int v){lastQuality=v;} public double getEaseFactor(){return easeFactor;} public void setEaseFactor(double v){easeFactor=v;} public Instant getLastReviewedAt(){return lastReviewedAt;} public void setLastReviewedAt(Instant v){lastReviewedAt=v;} public Instant getDueAt(){return dueAt;} public void setDueAt(Instant v){dueAt=v;} public void setUpdatedAt(Instant v){updatedAt=v;}
}

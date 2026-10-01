package com.skillpulse.adaptive;
import com.skillpulse.auth.AppUser; import com.skillpulse.practice.PracticeTopic;
import javax.persistence.*; import java.time.Instant;
@Entity @Table(name="user_topic_mastery",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","topic_id"}))
public class UserTopicMastery {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id") private AppUser user;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="topic_id") private PracticeTopic topic;
 @Column(nullable=false) private double masteryProbability=.20,priorProbability=.20,learnProbability=.12,guessProbability=.20,slipProbability=.10;
 @Column(nullable=false) private int attemptCount=0,correctCount=0; private Instant lastAttemptAt; @Column(nullable=false) private Instant updatedAt=Instant.now();
 @Column(name="ml_synced",nullable=false,columnDefinition="boolean default true") private boolean mlSynced=true;
 @Version @Column(nullable=false) private long version=0L;
 public Long getId(){return id;} public AppUser getUser(){return user;} public void setUser(AppUser v){user=v;} public PracticeTopic getTopic(){return topic;} public void setTopic(PracticeTopic v){topic=v;}
 public double getMasteryProbability(){return masteryProbability;} public void setMasteryProbability(double v){masteryProbability=v;} public double getPriorProbability(){return priorProbability;} public double getLearnProbability(){return learnProbability;} public double getGuessProbability(){return guessProbability;} public double getSlipProbability(){return slipProbability;}
 public int getAttemptCount(){return attemptCount;} public void setAttemptCount(int v){attemptCount=v;} public int getCorrectCount(){return correctCount;} public void setCorrectCount(int v){correctCount=v;} public Instant getLastAttemptAt(){return lastAttemptAt;} public void setLastAttemptAt(Instant v){lastAttemptAt=v;} public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
 public boolean isMlSynced(){return mlSynced;} public void setMlSynced(boolean v){mlSynced=v;}
 public void setPriorProbability(double v){priorProbability=v;} public void setLearnProbability(double v){learnProbability=v;} public void setGuessProbability(double v){guessProbability=v;} public void setSlipProbability(double v){slipProbability=v;}
}

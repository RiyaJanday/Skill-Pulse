package com.skillpulse.personalization;
import com.skillpulse.practice.PracticeTopic; import javax.persistence.*; import java.time.Instant;
@Entity @Table(name="personalized_plan_tasks") public class PersonalizedPlanTask {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="plan_day_id") private PersonalizedPlanDay planDay; @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="topic_id") private PracticeTopic topic; @Column(nullable=false,length=1000) private String description; @Column(nullable=false) private int requiredAttempts=1,completedAttempts=0; @Column(nullable=false) private boolean completed; private Instant completedAt;
 /** Minutes this task is expected to take. Nullable on purpose so the column can be added to an existing table; the timetable fills it in on first use. */
 @Column(name="planned_minutes") private Integer plannedMinutes;
 /** How many times the timetable moved this task to another day. Nullable for the same reason. */
 @Column(name="reschedule_count") private Integer rescheduleCount;
 public Long getId(){return id;} public PersonalizedPlanDay getPlanDay(){return planDay;} public void setPlanDay(PersonalizedPlanDay v){planDay=v;} public PracticeTopic getTopic(){return topic;} public void setTopic(PracticeTopic v){topic=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;} public int getRequiredAttempts(){return requiredAttempts;} public void setRequiredAttempts(int v){requiredAttempts=v;} public int getCompletedAttempts(){return completedAttempts;} public void setCompletedAttempts(int v){completedAttempts=v;} public boolean isCompleted(){return completed;} public void setCompleted(boolean v){completed=v;} public void setCompletedAt(Instant v){completedAt=v;}
 public Integer getPlannedMinutes(){return plannedMinutes;} public void setPlannedMinutes(Integer v){plannedMinutes=v;} public int getRescheduleCount(){return rescheduleCount==null?0:rescheduleCount;} public void setRescheduleCount(Integer v){rescheduleCount=v;}
}

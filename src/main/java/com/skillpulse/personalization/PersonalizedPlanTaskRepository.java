package com.skillpulse.personalization; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface PersonalizedPlanTaskRepository extends JpaRepository<PersonalizedPlanTask,Long>{List<PersonalizedPlanTask> findByPlanDayId(Long dayId);}

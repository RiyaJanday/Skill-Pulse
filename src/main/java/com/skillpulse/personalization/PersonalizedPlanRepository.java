package com.skillpulse.personalization; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface PersonalizedPlanRepository extends JpaRepository<PersonalizedPlan,Long>{Optional<PersonalizedPlan> findFirstByUserIdOrderByCreatedAtDesc(Long userId);List<PersonalizedPlan> findByUserIdAndActiveTrue(Long userId);}

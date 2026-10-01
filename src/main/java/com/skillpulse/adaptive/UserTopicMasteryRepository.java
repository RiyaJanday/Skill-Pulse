package com.skillpulse.adaptive;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface UserTopicMasteryRepository extends JpaRepository<UserTopicMastery,Long>{Optional<UserTopicMastery> findByUserIdAndTopicId(Long userId,Long topicId);List<UserTopicMastery> findByUserId(Long userId);List<UserTopicMastery> findByTopicId(Long topicId);List<UserTopicMastery> findByUserIdAndTopicSubjectNameOrderByMasteryProbabilityAsc(Long userId,String subjectName);}

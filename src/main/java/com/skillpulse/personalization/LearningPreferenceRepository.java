package com.skillpulse.personalization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface LearningPreferenceRepository extends JpaRepository<LearningPreference, Long> {
    Optional<LearningPreference> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}

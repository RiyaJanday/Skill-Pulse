package com.skillpulse.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPracticeAttemptRepository extends JpaRepository<UserPracticeAttempt, Long> {
    List<UserPracticeAttempt> findByUserIdOrderByAttemptedAtDesc(Long userId);
    List<UserPracticeAttempt> findByUserIdAndQuestionTopicIdOrderByAttemptedAtDesc(Long userId, Long topicId);
    long countByUserIdAndQuestionTopicSubjectId(Long userId, Long subjectId);
    long countByUserIdAndQuestionTopicSubjectIdAndCorrectTrue(Long userId, Long subjectId);
    void deleteByUserId(Long userId);

    // AI analytics (learner engagement features, question calibration, explanation guard)
    List<UserPracticeAttempt> findByAttemptedAtAfter(java.time.Instant since);
    List<UserPracticeAttempt> findByUserIdAndAttemptedAtAfter(Long userId, java.time.Instant since);
    boolean existsByUserIdAndQuestionId(Long userId, Long questionId);
    org.springframework.data.domain.Page<UserPracticeAttempt> findAllByOrderByIdDesc(org.springframework.data.domain.Pageable pageable);
}

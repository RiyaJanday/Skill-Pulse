package com.skillpulse.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PracticeQuestionRepository extends JpaRepository<PracticeQuestion, Long> {
    List<PracticeQuestion> findByTopicIdOrderByIdAsc(Long topicId);
    List<PracticeQuestion> findByTopicSubjectIdOrderByIdAsc(Long subjectId);
    long countByTopicSubjectId(Long subjectId);
    List<PracticeQuestion> findByTopicIdAndActiveTrueOrderByIdAsc(Long topicId);
    List<PracticeQuestion> findByTopicSubjectIdAndActiveTrueOrderByIdAsc(Long subjectId);
    long countByTopicSubjectIdAndActiveTrue(Long subjectId);
}

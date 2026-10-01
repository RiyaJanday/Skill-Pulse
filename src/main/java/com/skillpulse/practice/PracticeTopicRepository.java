package com.skillpulse.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PracticeTopicRepository extends JpaRepository<PracticeTopic, Long> {
    List<PracticeTopic> findBySubjectIdOrderByDisplayOrderAsc(Long subjectId);
    long countBySubjectId(Long subjectId);
}

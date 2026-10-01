package com.skillpulse.practice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PracticeSubjectRepository extends JpaRepository<PracticeSubject, Long> {
    List<PracticeSubject> findAllByOrderByDisplayOrderAsc();
    Optional<PracticeSubject> findBySlug(String slug);
}

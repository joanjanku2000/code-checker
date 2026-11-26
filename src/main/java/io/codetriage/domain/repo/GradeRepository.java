package io.codetriage.domain.repo;

import io.codetriage.domain.Grade;
import io.codetriage.domain.GradeVerdict;
import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GradeRepository extends JpaRepository<Grade, Long> {

    Optional<Grade> findBySubmission(StudentSubmission submission);

    List<Grade> findBySubmissionLabOrderBySubmissionStudentNameAsc(Lab lab);

    long countBySubmissionLabAndVerdict(Lab lab, GradeVerdict verdict);
}

package io.codetriage.domain.repo;

import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentSubmissionRepository extends JpaRepository<StudentSubmission, Long> {

    List<StudentSubmission> findByLabAndSupersededFalseOrderByStudentNameAsc(Lab lab);

    List<StudentSubmission> findByLabOrderBySubmittedAtDesc(Lab lab);

    Optional<StudentSubmission> findFirstByLabAndStudentNameIgnoreCaseAndSupersededFalse(
            Lab lab, String studentName);

    long countByLabAndSupersededFalse(Lab lab);
}

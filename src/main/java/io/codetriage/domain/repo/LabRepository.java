package io.codetriage.domain.repo;

import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LabRepository extends JpaRepository<Lab, Long> {

    Optional<Lab> findByNumberAndStudentGroup(Integer number, StudentGroup studentGroup);

    List<Lab> findAllByOrderByStudentGroupAscNumberAsc();

    List<Lab> findByStudentGroupOrderByNumberAsc(StudentGroup studentGroup);

    List<Lab> findByOpenTrueOrderByStudentGroupAscNumberAsc();
}

package io.codetriage.domain.repo;

import io.codetriage.domain.Lab;
import io.codetriage.domain.TriageRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriageRunRepository extends JpaRepository<TriageRun, Long> {
    List<TriageRun> findByLabOrderByStartedAtDesc(Lab lab);
    Optional<TriageRun> findFirstByLabOrderByStartedAtDesc(Lab lab);
}

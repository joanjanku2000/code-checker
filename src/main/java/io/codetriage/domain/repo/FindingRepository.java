package io.codetriage.domain.repo;

import io.codetriage.domain.Finding;
import io.codetriage.domain.TriageRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingRepository extends JpaRepository<Finding, Long> {
    List<Finding> findByRunOrderByTopReferenceSimilarityDesc(TriageRun run);
    void deleteByRun(TriageRun run);
}

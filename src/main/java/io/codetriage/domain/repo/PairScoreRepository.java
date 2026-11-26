package io.codetriage.domain.repo;

import io.codetriage.domain.PairKind;
import io.codetriage.domain.PairScore;
import io.codetriage.domain.TriageRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PairScoreRepository extends JpaRepository<PairScore, Long> {
    List<PairScore> findByRunAndKindOrderBySimilarityDesc(TriageRun run, PairKind kind);
    List<PairScore> findByRun(TriageRun run);
}

package io.codetriage.domain.repo;

import io.codetriage.domain.AiVariant;
import io.codetriage.domain.Lab;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiVariantRepository extends JpaRepository<AiVariant, Long> {
    List<AiVariant> findByLabOrderByIdAsc(Lab lab);
    long countByLab(Lab lab);
    void deleteByLab(Lab lab);
}

package io.codetriage.domain;

import jakarta.persistence.*;

/**
 * Every pairwise comparison JPlag produced, stored verbatim. Keeping the full
 * matrix is what makes a run reproducible and auditable after the fact.
 */
@Entity
@Table(name = "pair_score", indexes = {
        @Index(name = "idx_pair_run", columnList = "run_id"),
        @Index(name = "idx_pair_run_kind", columnList = "run_id,kind")
})
public class PairScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private TriageRun run;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PairKind kind;

    @Column(nullable = false, length = 120)
    private String leftName;

    @Column(nullable = false, length = 120)
    private String rightName;

    /** JPlag average similarity, 0.0-1.0. */
    private double similarity;

    /** JPlag maximal similarity, 0.0-1.0. Catches a short answer embedded in a long one. */
    private double maximalSimilarity;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TriageRun getRun() { return run; }
    public void setRun(TriageRun run) { this.run = run; }

    public PairKind getKind() { return kind; }
    public void setKind(PairKind kind) { this.kind = kind; }

    public String getLeftName() { return leftName; }
    public void setLeftName(String leftName) { this.leftName = leftName; }

    public String getRightName() { return rightName; }
    public void setRightName(String rightName) { this.rightName = rightName; }

    public double getSimilarity() { return similarity; }
    public void setSimilarity(double similarity) { this.similarity = similarity; }

    public double getMaximalSimilarity() { return maximalSimilarity; }
    public void setMaximalSimilarity(double maximalSimilarity) { this.maximalSimilarity = maximalSimilarity; }
}

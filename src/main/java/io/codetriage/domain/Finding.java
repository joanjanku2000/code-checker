package io.codetriage.domain;

import jakarta.persistence.*;

/**
 * The per-student summary of a run. Deliberately records numbers and reasons,
 * never a verdict: whether something happened is the lecturer's call, made by
 * looking at the code and talking to the student.
 */
@Entity
@Table(name = "finding", indexes = @Index(name = "idx_finding_run", columnList = "run_id"))
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER on purpose: the report templates read through these, and
    // open-in-view is disabled, so LAZY would throw once the session closed.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "run_id")
    private TriageRun run;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "submission_id")
    private StudentSubmission submission;

    private double topReferenceSimilarity;

    @Column(length = 120)
    private String topReferenceName;

    private Long topReferenceVariantId;

    private double topPeerSimilarity;

    @Column(length = 150)
    private String topPeerStudentName;

    private Long topPeerSubmissionId;

    /** Modified z-score of this student's reference similarity within the cohort. */
    private Double referenceZScore;

    @Column(nullable = false)
    private boolean needsReview = false;

    /** Human-readable, comma-separated reasons. Shown as-is on the report. */
    @Column(length = 500)
    private String reviewReasons = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public TriageRun getRun() { return run; }
    public void setRun(TriageRun run) { this.run = run; }

    public StudentSubmission getSubmission() { return submission; }
    public void setSubmission(StudentSubmission submission) { this.submission = submission; }

    public double getTopReferenceSimilarity() { return topReferenceSimilarity; }
    public void setTopReferenceSimilarity(double v) { this.topReferenceSimilarity = v; }

    public String getTopReferenceName() { return topReferenceName; }
    public void setTopReferenceName(String v) { this.topReferenceName = v; }

    public Long getTopReferenceVariantId() { return topReferenceVariantId; }
    public void setTopReferenceVariantId(Long v) { this.topReferenceVariantId = v; }

    public double getTopPeerSimilarity() { return topPeerSimilarity; }
    public void setTopPeerSimilarity(double v) { this.topPeerSimilarity = v; }

    public String getTopPeerStudentName() { return topPeerStudentName; }
    public void setTopPeerStudentName(String v) { this.topPeerStudentName = v; }

    public Long getTopPeerSubmissionId() { return topPeerSubmissionId; }
    public void setTopPeerSubmissionId(Long v) { this.topPeerSubmissionId = v; }

    public Double getReferenceZScore() { return referenceZScore; }
    public void setReferenceZScore(Double v) { this.referenceZScore = v; }

    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }

    public String getReviewReasons() { return reviewReasons; }
    public void setReviewReasons(String reviewReasons) { this.reviewReasons = reviewReasons; }

    @Transient
    public int getTopReferencePercent() { return (int) Math.round(topReferenceSimilarity * 100); }

    @Transient
    public int getTopPeerPercent() { return (int) Math.round(topPeerSimilarity * 100); }
}

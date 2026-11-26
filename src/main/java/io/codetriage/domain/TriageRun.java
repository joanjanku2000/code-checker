package io.codetriage.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "triage_run", indexes = @Index(name = "idx_run_lab", columnList = "lab_id"))
public class TriageRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lab_id")
    private Lab lab;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status = RunStatus.PENDING;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    private Instant finishedAt;

    private int submissionCount;
    private int referenceCount;

    /**
     * Median similarity between two generated reference solutions. This is the
     * floor: how alike two independent AI answers to this task are anyway. A
     * student at or below this number is unremarkable.
     */
    private Double referenceBaseline;

    private Double studentMedian;
    private Double studentMad;

    @Column(length = 500)
    private String jplagReportPath;

    @Lob
    @Column(length = 8000)
    private String errorMessage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Lab getLab() { return lab; }
    public void setLab(Lab lab) { this.lab = lab; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public int getSubmissionCount() { return submissionCount; }
    public void setSubmissionCount(int submissionCount) { this.submissionCount = submissionCount; }

    public int getReferenceCount() { return referenceCount; }
    public void setReferenceCount(int referenceCount) { this.referenceCount = referenceCount; }

    public Double getReferenceBaseline() { return referenceBaseline; }
    public void setReferenceBaseline(Double referenceBaseline) { this.referenceBaseline = referenceBaseline; }

    public Double getStudentMedian() { return studentMedian; }
    public void setStudentMedian(Double studentMedian) { this.studentMedian = studentMedian; }

    public Double getStudentMad() { return studentMad; }
    public void setStudentMad(Double studentMad) { this.studentMad = studentMad; }

    public String getJplagReportPath() { return jplagReportPath; }
    public void setJplagReportPath(String jplagReportPath) { this.jplagReportPath = jplagReportPath; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}

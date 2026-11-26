package io.codetriage.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "student_submission", indexes = {
        @Index(name = "idx_sub_lab", columnList = "lab_id"),
        @Index(name = "idx_sub_lab_active", columnList = "lab_id,superseded")
})
public class StudentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String studentName;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lab_id")
    private Lab lab;

    @Column(length = 260)
    private String originalFilename;

    /** Path to the stored archive, kept verbatim as the evidence of record. */
    @Column(length = 500)
    private String archivePath;

    /** Directory holding the extracted .java files that similarity actually reads. */
    @Column(length = 500)
    private String sourceDir;

    @Column(length = 64)
    private String sha256;

    private int sourceFileCount;

    private long archiveBytes;

    @Column(nullable = false)
    private Instant submittedAt = Instant.now();

    /** True once the same student submits again for the same lab. */
    @Column(nullable = false)
    private boolean superseded = false;

    /** Set when the submission arrived after the deadline. Recorded, never auto-rejected. */
    @Column(nullable = false)
    private boolean late = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public Lab getLab() { return lab; }
    public void setLab(Lab lab) { this.lab = lab; }

    /**
     * Derived, not stored. The lab already carries the group, and duplicating it
     * on the submission is how the two end up disagreeing. Templates that read
     * submission.studentGroup keep working unchanged.
     */
    @Transient
    public StudentGroup getStudentGroup() { return lab == null ? null : lab.getStudentGroup(); }

    @Transient
    public Integer getLabNumber() { return lab == null ? null : lab.getNumber(); }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getArchivePath() { return archivePath; }
    public void setArchivePath(String archivePath) { this.archivePath = archivePath; }

    public String getSourceDir() { return sourceDir; }
    public void setSourceDir(String sourceDir) { this.sourceDir = sourceDir; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public int getSourceFileCount() { return sourceFileCount; }
    public void setSourceFileCount(int sourceFileCount) { this.sourceFileCount = sourceFileCount; }

    public long getArchiveBytes() { return archiveBytes; }
    public void setArchiveBytes(long archiveBytes) { this.archiveBytes = archiveBytes; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public boolean isSuperseded() { return superseded; }
    public void setSuperseded(boolean superseded) { this.superseded = superseded; }

    public boolean isLate() { return late; }
    public void setLate(boolean late) { this.late = late; }

    /** Stable, filesystem-safe identity used as the JPlag submission folder name. */
    @Transient
    public String jplagName() {
        return "stu_" + id;
    }
}

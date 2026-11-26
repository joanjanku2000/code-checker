package io.codetriage.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One lab assignment for one group.
 *
 * Lab 3 for INF - 2A and Lab 3 for KOMPJUTERIKE are separate rows: separate
 * assignment sheets, deadlines, reference corpora and triage runs. A surrogate
 * key rather than a composite one, because every other table points at a lab and
 * a single Long is far less to carry around.
 */
@Entity
@Table(name = "lab", uniqueConstraints = @UniqueConstraint(
        name = "uk_lab_number_group", columnNames = {"lab_number", "student_group"}))
public class Lab {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 1-5. Named column explicitly: "number" is a reserved word in several engines. */
    @Column(name = "lab_number", nullable = false)
    private Integer number;

    @Enumerated(EnumType.STRING)
    @Column(name = "student_group", nullable = false, length = 40)
    private StudentGroup studentGroup;

    @Column(nullable = false)
    private String title = "";

    @Lob
    @Column(length = 20000)
    private String specification = "";

    /** Original uploaded assignment file, kept as handed out. */
    @Column(length = 260)
    private String assignmentFilename;

    @Column(length = 500)
    private String assignmentPath;

    /**
     * Text extracted from the uploaded assignment. Drives both reference
     * generation and rubric grading, so it is stored rather than re-extracted.
     */
    @Lob
    @Column(length = 100000)
    private String assignmentText;

    /** Boilerplate you handed out. Excluded from similarity so it cannot inflate scores. */
    @Lob
    @Column(length = 20000)
    private String starterCode = "";

    @Column(nullable = false)
    private boolean open = false;

    private Instant deadline;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }

    public StudentGroup getStudentGroup() { return studentGroup; }
    public void setStudentGroup(StudentGroup studentGroup) { this.studentGroup = studentGroup; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSpecification() { return specification; }
    public void setSpecification(String specification) { this.specification = specification; }

    public String getAssignmentFilename() { return assignmentFilename; }
    public void setAssignmentFilename(String v) { this.assignmentFilename = v; }

    public String getAssignmentPath() { return assignmentPath; }
    public void setAssignmentPath(String v) { this.assignmentPath = v; }

    public String getAssignmentText() { return assignmentText; }
    public void setAssignmentText(String v) { this.assignmentText = v; }

    public String getStarterCode() { return starterCode; }
    public void setStarterCode(String starterCode) { this.starterCode = starterCode; }

    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }

    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }

    /**
     * The text the model actually sees. The uploaded assignment wins; the pasted
     * box is the fallback and the manual override.
     */
    @Transient
    public String effectiveTaskText() {
        if (assignmentText != null && !assignmentText.isBlank()) {
            return assignmentText;
        }
        return specification == null ? "" : specification;
    }

    @Transient
    public boolean hasTaskText() {
        return !effectiveTaskText().isBlank();
    }

    @Transient
    public boolean isAcceptingSubmissions() {
        if (!open) return false;
        return deadline == null || Instant.now().isBefore(deadline);
    }

    /** "Lab 3 · INF - 2A" */
    @Transient
    public String getDisplayName() {
        return "Lab " + number + " · " + (studentGroup == null ? "?" : studentGroup.getLabel());
    }

    /** Filesystem- and URL-safe, e.g. "3-INF_2A". */
    @Transient
    public String getSlug() {
        return number + "-" + (studentGroup == null ? "NONE" : studentGroup.name());
    }
}

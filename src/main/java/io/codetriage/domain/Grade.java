package io.codetriage.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The grade for one submission. Admin-only; nothing here is ever rendered on a
 * student-facing page.
 *
 * Three things are kept deliberately separate:
 *
 *   verdict          what the lecturer decided. This is the grade.
 *   suggestedVerdict what the rubric assessment proposed. Advisory.
 *   compileOk        an objective fact about the code.
 *
 * Similarity does not appear here at all, and that is the point. A triage flag
 * is a reason to look at someone's work, never an input to their mark; wiring
 * the two together would turn an unprovable signal into a penalty.
 */
@Entity
@Table(name = "grade", indexes = {
        @Index(name = "idx_grade_submission", columnList = "submission_id", unique = true)
})
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "submission_id", unique = true)
    private StudentSubmission submission;

    // --- the lecturer's decision ---

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GradeVerdict verdict = GradeVerdict.UNGRADED;

    @Column(length = 1500)
    private String lecturerNote;

    private Instant gradedAt;

    // --- objective check ---

    /** null when the check has not run. */
    private Boolean compileOk;

    private Integer compileErrorCount;

    @Lob
    @Column(length = 20000)
    private String compileOutput;

    // --- advisory assessment ---

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GradeVerdict suggestedVerdict;

    @Lob
    @Column(length = 8000)
    private String suggestionReasoning;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "grade_criterion", joinColumns = @JoinColumn(name = "grade_id"))
    @OrderColumn(name = "position")
    private List<GradeCriterion> criteria = new ArrayList<>();

    private Instant assessedAt;

    @Column(length = 1000)
    private String assessmentError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public StudentSubmission getSubmission() { return submission; }
    public void setSubmission(StudentSubmission submission) { this.submission = submission; }

    public GradeVerdict getVerdict() { return verdict; }
    public void setVerdict(GradeVerdict verdict) { this.verdict = verdict; }

    public String getLecturerNote() { return lecturerNote; }
    public void setLecturerNote(String lecturerNote) { this.lecturerNote = lecturerNote; }

    public Instant getGradedAt() { return gradedAt; }
    public void setGradedAt(Instant gradedAt) { this.gradedAt = gradedAt; }

    public Boolean getCompileOk() { return compileOk; }
    public void setCompileOk(Boolean compileOk) { this.compileOk = compileOk; }

    public Integer getCompileErrorCount() { return compileErrorCount; }
    public void setCompileErrorCount(Integer compileErrorCount) { this.compileErrorCount = compileErrorCount; }

    public String getCompileOutput() { return compileOutput; }
    public void setCompileOutput(String compileOutput) { this.compileOutput = compileOutput; }

    public GradeVerdict getSuggestedVerdict() { return suggestedVerdict; }
    public void setSuggestedVerdict(GradeVerdict suggestedVerdict) { this.suggestedVerdict = suggestedVerdict; }

    public String getSuggestionReasoning() { return suggestionReasoning; }
    public void setSuggestionReasoning(String suggestionReasoning) { this.suggestionReasoning = suggestionReasoning; }

    public List<GradeCriterion> getCriteria() { return criteria; }
    public void setCriteria(List<GradeCriterion> criteria) { this.criteria = criteria; }

    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant assessedAt) { this.assessedAt = assessedAt; }

    public String getAssessmentError() { return assessmentError; }
    public void setAssessmentError(String assessmentError) { this.assessmentError = assessmentError; }

    @Transient
    public boolean isDecided() {
        return verdict != null && verdict != GradeVerdict.UNGRADED;
    }

    /** True when the lecturer decided the opposite of what was suggested. */
    @Transient
    public boolean isOverridden() {
        return isDecided() && suggestedVerdict != null && verdict != suggestedVerdict;
    }

    @Transient
    public int getCriteriaMetCount() {
        return (int) criteria.stream().filter(GradeCriterion::isMet).count();
    }
}

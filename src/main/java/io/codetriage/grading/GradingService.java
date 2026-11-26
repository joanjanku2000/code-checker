package io.codetriage.grading;

import io.codetriage.domain.*;
import io.codetriage.domain.repo.GradeRepository;
import io.codetriage.domain.repo.LabRepository;
import io.codetriage.domain.repo.StudentSubmissionRepository;
import io.codetriage.triage.JobRegistry;
import io.codetriage.util.SourceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Runs the automated half of grading, and records the lecturer's decision.
 *
 * Two independent inputs are gathered per submission: whether it compiles (a
 * fact) and how it reads against the assignment (an opinion). Neither sets the
 * grade. {@link #decide} is the only method that writes a verdict, and it is
 * only ever called from a lecturer's click.
 */
@Service
public class GradingService {

    private static final Logger log = LoggerFactory.getLogger(GradingService.class);

    private final GradeRepository grades;
    private final StudentSubmissionRepository submissions;
    private final LabRepository labs;
    private final CompileChecker compileChecker;
    private final RubricAssessor assessor;
    private final SourceReader sourceReader;
    private final JobRegistry jobs;

    public GradingService(GradeRepository grades, StudentSubmissionRepository submissions,
                          LabRepository labs, CompileChecker compileChecker,
                          RubricAssessor assessor, SourceReader sourceReader,
                          JobRegistry jobs) {
        this.grades = grades;
        this.submissions = submissions;
        this.labs = labs;
        this.compileChecker = compileChecker;
        this.assessor = assessor;
        this.sourceReader = sourceReader;
        this.jobs = jobs;
    }

    public static String jobKey(Lab lab) {
        return "grading-" + lab.getId();
    }

    public List<Grade> gradesFor(Lab lab) {
        return grades.findBySubmissionLabOrderBySubmissionStudentNameAsc(lab);
    }

    public Optional<Grade> gradeFor(StudentSubmission submission) {
        return grades.findBySubmission(submission);
    }

    public long countVerdict(Lab lab, GradeVerdict verdict) {
        return grades.countBySubmissionLabAndVerdict(lab, verdict);
    }

    /**
     * The lecturer's decision. The only path that sets a verdict.
     */
    @Transactional
    public Grade decide(long submissionId, GradeVerdict verdict, String note) {
        StudentSubmission submission = submissions.findById(submissionId).orElseThrow();
        Grade grade = grades.findBySubmission(submission).orElseGet(() -> {
            Grade fresh = new Grade();
            fresh.setSubmission(submission);
            return fresh;
        });
        grade.setVerdict(verdict == null ? GradeVerdict.UNGRADED : verdict);
        grade.setLecturerNote(note == null || note.isBlank() ? null : note.strip());
        grade.setGradedAt(verdict == GradeVerdict.UNGRADED ? null : Instant.now());
        return grades.save(grade);
    }

    /**
     * Runs the checks for a single submission, synchronously.
     *
     * Deliberately not async: the lecturer clicked this on one student and is
     * waiting for the answer, so a job they then have to poll for is worse than
     * a few seconds of spinner. The compile check is immediate; the rubric call
     * is the slow part, usually five to twenty seconds.
     *
     * @return a short outcome to show the lecturer
     */
    @Transactional
    public String checkSubmission(long submissionId, boolean includeAssessment) {
        StudentSubmission submission = submissions.findById(submissionId).orElseThrow();
        Lab lab = submission.getLab();

        boolean assess = includeAssessment && assessor.isAvailable() && lab.hasTaskText();
        String skipped = null;
        if (includeAssessment && !assess) {
            skipped = !lab.hasTaskText()
                    ? " Rubric assessment skipped: no assignment text."
                    : " Rubric assessment skipped: no API key.";
        }

        try {
            checkOne(lab, submission, assess);
        } catch (Exception e) {
            // checkOne has already recorded the failure on the grade itself.
            log.warn("Checks failed for submission {}: {}", submissionId, e.getMessage());
            return submission.getStudentName() + ": checks ran, but the assessment failed — "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        }

        Grade grade = grades.findBySubmission(submission).orElse(null);
        StringBuilder out = new StringBuilder(submission.getStudentName() + ": ");
        if (grade == null || grade.getCompileOk() == null) {
            out.append("compile check not attempted");
        } else {
            out.append(grade.getCompileOk() ? "compiles" : "does not compile");
        }
        if (grade != null && grade.getSuggestedVerdict() != null) {
            out.append(", suggested ").append(grade.getSuggestedVerdict().getLabel());
        }
        out.append(". The verdict is still yours.");
        if (skipped != null) {
            out.append(skipped);
        }
        return out.toString();
    }

    @Async("jobExecutor")
    public void runAsync(Long labId, boolean includeAssessment) {
        Lab lab = labs.findById(labId).orElse(null);
        if (lab == null) {
            return;
        }
        String key = jobKey(lab);

        List<StudentSubmission> active =
                submissions.findByLabAndSupersededFalseOrderByStudentNameAsc(lab);

        jobs.start(key, "Checking submissions for " + lab.getDisplayName(),
                Math.max(1, active.size()));

        boolean assess = includeAssessment && assessor.isAvailable() && lab.hasTaskText();
        if (includeAssessment && !assess) {
            log.info("Rubric assessment skipped for {}: apiKey={}, hasTaskText={}",
                    lab.getDisplayName(), assessor.isAvailable(), lab.hasTaskText());
        }

        int done = 0;
        int assessmentFailures = 0;

        for (StudentSubmission submission : active) {
            done++;
            jobs.progress(key, submission.getStudentName(), done);
            try {
                checkOne(lab, submission, assess);
            } catch (Exception e) {
                assessmentFailures++;
                log.warn("Grading checks failed for submission {}: {}",
                        submission.getId(), e.getMessage());
            }
        }

        StringBuilder summary = new StringBuilder("Checked " + active.size() + " submissions.");
        if (!assess) {
            if (!lab.hasTaskText()) {
                summary.append(" Rubric assessment skipped: no assignment text uploaded.");
            } else if (!assessor.isAvailable()) {
                summary.append(" Rubric assessment skipped: no API key.");
            } else {
                summary.append(" Compile check only.");
            }
        }
        if (assessmentFailures > 0) {
            summary.append(" ").append(assessmentFailures).append(" had problems.");
        }
        summary.append(" Verdicts are still yours to set.");
        jobs.finish(key, summary.toString());
    }

    // Not @Transactional: called from runAsync within the same bean, so a proxy
    // would not apply. Each save carries its own transaction.
    void checkOne(Lab lab, StudentSubmission submission, boolean assess) throws Exception {
        Grade grade = grades.findBySubmission(submission).orElseGet(() -> {
            Grade fresh = new Grade();
            fresh.setSubmission(submission);
            return fresh;
        });

        // --- objective: does it build ---
        CompileOutcome outcome = compileChecker.check(
                submission.getSourceDir() == null ? null : Path.of(submission.getSourceDir()));
        grade.setCompileOk(outcome.attempted() ? outcome.success() : null);
        grade.setCompileErrorCount(outcome.attempted() ? outcome.errorCount() : null);
        grade.setCompileOutput(outcome.diagnostics());
        grade = grades.save(grade);

        if (!assess) {
            return;
        }

        // --- advisory: how it reads against the assignment ---
        try {
            String code = sourceReader.read(submission.getSourceDir());
            RubricAssessor.Assessment assessment = assessor.assess(lab.effectiveTaskText(), code);

            grade.getCriteria().clear();
            grade.getCriteria().addAll(assessment.criteria());
            grade.setSuggestedVerdict(assessment.suggestedVerdict());
            grade.setSuggestionReasoning(assessment.reasoning());
            grade.setAssessedAt(Instant.now());
            grade.setAssessmentError(null);

        } catch (Exception e) {
            grade.setSuggestedVerdict(null);
            grade.setSuggestionReasoning(null);
            grade.setAssessedAt(null);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            grade.setAssessmentError(message.length() > 900 ? message.substring(0, 900) : message);
            grades.save(grade);
            throw e;
        }
        grades.save(grade);
    }
}

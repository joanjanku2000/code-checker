package io.codetriage.web;

import io.codetriage.assignment.AssignmentTextExtractor;
import io.codetriage.config.AppProperties;
import io.codetriage.config.StorageLayout;
import io.codetriage.corpus.CorpusService;
import io.codetriage.domain.*;
import io.codetriage.domain.repo.*;
import io.codetriage.grading.GradingService;
import io.codetriage.triage.JobRegistry;
import io.codetriage.triage.TriageService;
import io.codetriage.util.FileOps;
import io.codetriage.util.SourceReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecturer area. Every lab is addressed as (number, group), because Lab 3 for
 * INF - 2A and Lab 3 for KOMPJUTERIKE are separate things with separate
 * assignments, corpora, runs and grades.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final ZoneId ZONE = ZoneId.of("Europe/Tirane");

    private final LabRepository labs;
    private final StudentSubmissionRepository submissions;
    private final AiVariantRepository variants;
    private final TriageRunRepository runs;
    private final FindingRepository findings;
    private final PairScoreRepository pairs;
    private final CorpusService corpus;
    private final TriageService triage;
    private final JobRegistry jobs;
    private final AppProperties props;
    private final GradingService grading;
    private final AssignmentTextExtractor assignmentExtractor;
    private final SourceReader sourceReader;
    private final StorageLayout storage;

    public AdminController(LabRepository labs, StudentSubmissionRepository submissions,
                           AiVariantRepository variants, TriageRunRepository runs,
                           FindingRepository findings, PairScoreRepository pairs,
                           CorpusService corpus, TriageService triage,
                           JobRegistry jobs, AppProperties props,
                           GradingService grading,
                           AssignmentTextExtractor assignmentExtractor,
                           SourceReader sourceReader, StorageLayout storage) {
        this.labs = labs;
        this.submissions = submissions;
        this.variants = variants;
        this.runs = runs;
        this.findings = findings;
        this.pairs = pairs;
        this.corpus = corpus;
        this.triage = triage;
        this.jobs = jobs;
        this.props = props;
        this.grading = grading;
        this.assignmentExtractor = assignmentExtractor;
        this.sourceReader = sourceReader;
        this.storage = storage;
    }

    private Lab lab(int number, StudentGroup group) {
        return labs.findByNumberAndStudentGroup(number, group)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Lab " + number + " for " + group.getLabel()));
    }

    private String labUrl(Lab lab) {
        return "redirect:/admin/labs/" + lab.getNumber() + "/" + lab.getStudentGroup().name();
    }

    // ---------------------------------------------------------------
    //  Dashboard
    // ---------------------------------------------------------------

    @GetMapping
    public String dashboard(Model model) {
        Map<StudentGroup, List<LabSummary>> byGroup = new LinkedHashMap<>();
        for (StudentGroup group : StudentGroup.values()) {
            List<LabSummary> summaries = new ArrayList<>();
            for (Lab lab : labs.findByStudentGroupOrderByNumberAsc(group)) {
                summaries.add(new LabSummary(
                        lab,
                        submissions.countByLabAndSupersededFalse(lab),
                        variants.countByLab(lab),
                        runs.findFirstByLabOrderByStartedAtDesc(lab).orElse(null),
                        grading.countVerdict(lab, GradeVerdict.PASS),
                        grading.countVerdict(lab, GradeVerdict.NOT_PASS)));
            }
            byGroup.put(group, summaries);
        }
        model.addAttribute("byGroup", byGroup);
        model.addAttribute("llmConfigured", props.getLlm().isConfigured());
        return "admin/dashboard";
    }

    // ---------------------------------------------------------------
    //  One lab
    // ---------------------------------------------------------------

    @GetMapping("/labs/{number}/{group}")
    public String lab(@PathVariable int number, @PathVariable StudentGroup group, Model model) {
        Lab lab = lab(number, group);
        model.addAttribute("lab", lab);
        model.addAttribute("deadlineLocal", lab.getDeadline() == null ? ""
                : LocalDateTime.ofInstant(lab.getDeadline(), ZONE).withSecond(0).withNano(0).toString());
        model.addAttribute("submissions", submissions.findByLabOrderBySubmittedAtDesc(lab));
        model.addAttribute("activeCount", submissions.countByLabAndSupersededFalse(lab));
        model.addAttribute("variantCount", variants.countByLab(lab));
        model.addAttribute("runs", runs.findByLabOrderByStartedAtDesc(lab));
        model.addAttribute("corpusJob", jobs.get(CorpusService.jobKey(lab)));
        model.addAttribute("triageJob", jobs.get(TriageService.jobKey(lab)));
        model.addAttribute("gradingJob", jobs.get(GradingService.jobKey(lab)));
        model.addAttribute("passCount", grading.countVerdict(lab, GradeVerdict.PASS));
        model.addAttribute("notPassCount", grading.countVerdict(lab, GradeVerdict.NOT_PASS));
        model.addAttribute("llmConfigured", props.getLlm().isConfigured());
        model.addAttribute("acceptedAssignmentTypes",
                String.join(", ", AssignmentTextExtractor.ACCEPTED));
        model.addAttribute("allGroups", StudentGroup.values());
        return "admin/lab";
    }

    @PostMapping("/labs/{number}/{group}")
    public String saveLab(@PathVariable int number, @PathVariable StudentGroup group,
                          @RequestParam String title,
                          @RequestParam(required = false) String specification,
                          @RequestParam(required = false) String starterCode,
                          @RequestParam(required = false) String deadline,
                          @RequestParam(defaultValue = "false") boolean open,
                          RedirectAttributes flash) {
        Lab lab = lab(number, group);
        lab.setTitle(title == null || title.isBlank() ? "Laboratori " + number : title.trim());
        lab.setSpecification(specification == null ? "" : specification);
        lab.setStarterCode(starterCode == null ? "" : starterCode);
        lab.setOpen(open);
        if (deadline == null || deadline.isBlank()) {
            lab.setDeadline(null);
        } else {
            try {
                lab.setDeadline(LocalDateTime.parse(deadline).atZone(ZONE).toInstant());
            } catch (RuntimeException e) {
                flash.addFlashAttribute("error", "Could not read that deadline; it was left unchanged.");
            }
        }
        labs.save(lab);
        flash.addFlashAttribute("message", lab.getDisplayName() + " saved.");
        return labUrl(lab);
    }

    /** Copies this lab's task text and starter code to the same lab of other groups. */
    @PostMapping("/labs/{number}/{group}/copy-to-groups")
    public String copyToGroups(@PathVariable int number, @PathVariable StudentGroup group,
                               @RequestParam(required = false) List<StudentGroup> targets,
                               RedirectAttributes flash) {
        Lab source = lab(number, group);
        if (targets == null || targets.isEmpty()) {
            flash.addFlashAttribute("error", "Pick at least one group to copy to.");
            return labUrl(source);
        }
        int copied = 0;
        for (StudentGroup target : targets) {
            if (target == group) continue;
            Lab other = labs.findByNumberAndStudentGroup(number, target).orElse(null);
            if (other == null) continue;
            other.setTitle(source.getTitle());
            other.setSpecification(source.getSpecification());
            other.setStarterCode(source.getStarterCode());
            other.setAssignmentText(source.getAssignmentText());
            labs.save(other);
            copied++;
        }
        flash.addFlashAttribute("message", "Task text copied to " + copied + " other group(s). "
                + "Deadlines, open state and reference corpora were not copied — those stay per group.");
        return labUrl(source);
    }

    // ---------------------------------------------------------------
    //  Assignment sheet
    // ---------------------------------------------------------------

    @PostMapping("/labs/{number}/{group}/assignment")
    public String uploadAssignment(@PathVariable int number, @PathVariable StudentGroup group,
                                   @RequestParam("assignment") MultipartFile file,
                                   RedirectAttributes flash) {
        Lab lab = lab(number, group);

        if (file == null || file.isEmpty()) {
            flash.addFlashAttribute("error", "Choose a file first.");
            return labUrl(lab);
        }
        String originalName = file.getOriginalFilename() == null
                ? "assignment" : file.getOriginalFilename();
        if (!AssignmentTextExtractor.isAccepted(originalName)) {
            flash.addFlashAttribute("error", "Use one of: "
                    + String.join(", ", AssignmentTextExtractor.ACCEPTED));
            return labUrl(lab);
        }

        try {
            Path dir = storage.ensure(storage.assignment(lab));
            FileOps.deleteTree(dir);
            Files.createDirectories(dir);

            String safeName = FileOps.slug(stripExtension(originalName), 60)
                    + extensionOf(originalName);
            Path target = dir.resolve(safeName);
            file.transferTo(target);

            String text = assignmentExtractor.extract(target, originalName);

            lab.setAssignmentFilename(originalName);
            lab.setAssignmentPath(target.toString());
            lab.setAssignmentText(text);
            labs.save(lab);

            flash.addFlashAttribute("message", "Assignment uploaded. "
                    + text.length() + " characters of text extracted — check it reads correctly "
                    + "before generating references.");

        } catch (IOException e) {
            flash.addFlashAttribute("error", "Could not read that file: " + e.getMessage());
        }
        return labUrl(lab);
    }

    @GetMapping("/labs/{number}/{group}/assignment")
    @ResponseBody
    public ResponseEntity<FileSystemResource> downloadAssignment(
            @PathVariable int number, @PathVariable StudentGroup group) {
        Lab lab = lab(number, group);
        if (lab.getAssignmentPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(lab.getAssignmentPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + lab.getAssignmentFilename() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    @PostMapping("/labs/{number}/{group}/assignment/clear")
    public String clearAssignment(@PathVariable int number, @PathVariable StudentGroup group,
                                  RedirectAttributes flash) throws IOException {
        Lab lab = lab(number, group);
        FileOps.deleteTree(storage.assignment(lab));
        lab.setAssignmentFilename(null);
        lab.setAssignmentPath(null);
        lab.setAssignmentText(null);
        labs.save(lab);
        flash.addFlashAttribute("message", "Assignment removed.");
        return labUrl(lab);
    }

    // ---------------------------------------------------------------
    //  Corpus and triage
    // ---------------------------------------------------------------

    @PostMapping("/labs/{number}/{group}/corpus")
    public String generateCorpus(@PathVariable int number, @PathVariable StudentGroup group,
                                 @RequestParam(defaultValue = "3") int repeats,
                                 RedirectAttributes flash) {
        Lab lab = lab(number, group);
        if (jobs.isRunning(CorpusService.jobKey(lab))) {
            flash.addFlashAttribute("error", "Generation is already running for this lab.");
            return labUrl(lab);
        }
        corpus.generateAsync(lab.getId(), Math.max(1, Math.min(repeats, 6)));
        flash.addFlashAttribute("message", "Generating reference solutions. This takes a few minutes.");
        return labUrl(lab);
    }

    @PostMapping("/labs/{number}/{group}/corpus/clear")
    public String clearCorpus(@PathVariable int number, @PathVariable StudentGroup group,
                              RedirectAttributes flash) throws IOException {
        Lab lab = lab(number, group);
        corpus.clear(lab);
        flash.addFlashAttribute("message", "Reference corpus cleared.");
        return labUrl(lab);
    }

    @PostMapping("/labs/{number}/{group}/triage")
    public String runTriage(@PathVariable int number, @PathVariable StudentGroup group,
                            RedirectAttributes flash) {
        Lab lab = lab(number, group);
        if (jobs.isRunning(TriageService.jobKey(lab))) {
            flash.addFlashAttribute("error", "A triage run is already in progress for this lab.");
            return labUrl(lab);
        }
        triage.runAsync(lab.getId());
        flash.addFlashAttribute("message", "Triage run started.");
        return labUrl(lab);
    }

    @GetMapping("/runs/{runId}")
    public String run(@PathVariable long runId, Model model) {
        TriageRun run = runs.findById(runId).orElseThrow();
        List<Finding> all = findings.findByRunOrderByTopReferenceSimilarityDesc(run);
        model.addAttribute("run", run);
        model.addAttribute("flagged", all.stream().filter(Finding::isNeedsReview).toList());
        model.addAttribute("clear", all.stream().filter(f -> !f.isNeedsReview()).toList());
        model.addAttribute("topPeerPairs", pairs
                .findByRunAndKindOrderBySimilarityDesc(run, PairKind.STUDENT_STUDENT)
                .stream().limit(15).toList());
        model.addAttribute("thresholds", props.getSimilarity());
        model.addAttribute("hasReport", run.getJplagReportPath() != null
                && Files.exists(Path.of(run.getJplagReportPath())));
        return "admin/run";
    }

    @GetMapping("/findings/{findingId}")
    public String compare(@PathVariable long findingId, Model model) throws IOException {
        Finding finding = findings.findById(findingId).orElseThrow();
        StudentSubmission submission = finding.getSubmission();

        model.addAttribute("finding", finding);
        model.addAttribute("submission", submission);
        model.addAttribute("studentCode", sourceReader.read(submission.getSourceDir()));
        model.addAttribute("grade", grading.gradeFor(submission).orElse(null));
        model.addAttribute("verdicts", GradeVerdict.values());

        if (finding.getTopReferenceVariantId() != null) {
            variants.findById(finding.getTopReferenceVariantId())
                    .ifPresent(v -> model.addAttribute("variant", v));
        }
        if (finding.getTopPeerSubmissionId() != null) {
            submissions.findById(finding.getTopPeerSubmissionId()).ifPresent(peer -> {
                model.addAttribute("peer", peer);
                try {
                    model.addAttribute("peerCode", sourceReader.read(peer.getSourceDir()));
                } catch (IOException ignored) {
                    // leave it out
                }
            });
        }
        return "admin/compare";
    }

    // ---------------------------------------------------------------
    //  Grading
    // ---------------------------------------------------------------

    @PostMapping("/labs/{number}/{group}/grade")
    public String runGrading(@PathVariable int number, @PathVariable StudentGroup group,
                             @RequestParam(defaultValue = "true") boolean assess,
                             RedirectAttributes flash) {
        Lab lab = lab(number, group);
        if (jobs.isRunning(GradingService.jobKey(lab))) {
            flash.addFlashAttribute("error", "Grading checks are already running for this lab.");
            return labUrl(lab);
        }
        grading.runAsync(lab.getId(), assess);
        flash.addFlashAttribute("message",
                "Running checks. Verdicts remain yours to set afterwards.");
        return labUrl(lab);
    }

    @GetMapping("/labs/{number}/{group}/grades")
    public String grades(@PathVariable int number, @PathVariable StudentGroup group,
                         @RequestParam(defaultValue = "undecided") String show,
                         Model model) {
        Lab lab = lab(number, group);
        List<StudentSubmission> active = submissions.findByLabAndSupersededFalseOrderByStudentNameAsc(lab);

        List<GradeRow> all = new ArrayList<>();
        for (StudentSubmission submission : active) {
            all.add(new GradeRow(submission,
                    grading.gradeFor(submission).orElse(null),
                    latestFindingFor(lab, submission)));
        }

        // Defaults to the undecided pile, so deciding one takes it off the list
        // and the page works as a queue that empties.
        List<GradeRow> rows = switch (show) {
            case "pass" -> all.stream().filter(r -> r.verdictIs(GradeVerdict.PASS)).toList();
            case "notpass" -> all.stream().filter(r -> r.verdictIs(GradeVerdict.NOT_PASS)).toList();
            case "all" -> all;
            default -> all.stream().filter(r -> !r.isDecided()).toList();
        };

        long passCount = all.stream().filter(r -> r.verdictIs(GradeVerdict.PASS)).count();
        long notPassCount = all.stream().filter(r -> r.verdictIs(GradeVerdict.NOT_PASS)).count();

        model.addAttribute("lab", lab);
        model.addAttribute("rows", rows);
        model.addAttribute("show", show);
        model.addAttribute("totalCount", all.size());
        model.addAttribute("undecidedCount", all.size() - passCount - notPassCount);
        model.addAttribute("passCount", passCount);
        model.addAttribute("notPassCount", notPassCount);
        model.addAttribute("verdicts", GradeVerdict.values());
        model.addAttribute("gradingJob", jobs.get(GradingService.jobKey(lab)));
        model.addAttribute("llmConfigured", props.getLlm().isConfigured());
        return "admin/grades";
    }

    /** Runs compile and rubric checks for one submission, on demand. */
    @PostMapping("/submissions/{id}/check")
    public String checkSubmission(@PathVariable long id,
                                  @RequestParam(defaultValue = "true") boolean assess,
                                  @RequestParam(required = false) String back,
                                  RedirectAttributes flash) {
        StudentSubmission submission = submissions.findById(id).orElseThrow();
        try {
            flash.addFlashAttribute("message", grading.checkSubmission(id, assess));
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", "Checks failed: " + e.getMessage());
        }
        if (back != null && back.startsWith("/admin/")) {
            return "redirect:" + back;
        }
        return gradesUrl(submission.getLab());
    }

    @PostMapping("/grades/{submissionId}/decide")
    public String decide(@PathVariable long submissionId,
                         @RequestParam String verdict,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String back,
                         @RequestParam(required = false) String show,
                         RedirectAttributes flash) {
        StudentSubmission submission = submissions.findById(submissionId).orElseThrow();
        Lab lab = submission.getLab();
        GradeVerdict parsed;
        try {
            parsed = GradeVerdict.valueOf(verdict);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "Unknown verdict.");
            return gradesUrl(lab, show);
        }
        grading.decide(submissionId, parsed, note);
        flash.addFlashAttribute("message",
                submission.getStudentName() + ": " + parsed.getLabel() + " recorded.");

        if (back != null && back.startsWith("/admin/")) {
            return "redirect:" + back;
        }
        return gradesUrl(lab, show);
    }

    private String gradesUrl(Lab lab) {
        return "redirect:/admin/labs/" + lab.getNumber() + "/"
                + lab.getStudentGroup().name() + "/grades";
    }

    /** Round-trips the current filter so a decision does not bounce you to another tab. */
    private String gradesUrl(Lab lab, String show) {
        return gradesUrl(lab) + (show == null || show.isBlank() ? "" : "?show=" + show);
    }

    @GetMapping("/labs/{number}/{group}/grades.csv")
    @ResponseBody
    public ResponseEntity<byte[]> exportGrades(@PathVariable int number,
                                               @PathVariable StudentGroup group) {
        Lab lab = lab(number, group);
        StringBuilder csv = new StringBuilder();
        csv.append("student,group,lab,submitted_at,late,compiles,compile_errors,"
                + "suggested,verdict,lecturer_note\n");

        for (StudentSubmission submission :
                submissions.findByLabAndSupersededFalseOrderByStudentNameAsc(lab)) {
            Grade grade = grading.gradeFor(submission).orElse(null);
            csv.append(String.join(",",
                    quote(submission.getStudentName()),
                    quote(lab.getStudentGroup().getLabel()),
                    String.valueOf(lab.getNumber()),
                    quote(String.valueOf(submission.getSubmittedAt())),
                    String.valueOf(submission.isLate()),
                    grade == null || grade.getCompileOk() == null
                            ? "" : String.valueOf(grade.getCompileOk()),
                    grade == null || grade.getCompileErrorCount() == null
                            ? "" : String.valueOf(grade.getCompileErrorCount()),
                    quote(grade == null || grade.getSuggestedVerdict() == null
                            ? "" : grade.getSuggestedVerdict().name()),
                    quote(grade == null ? "UNGRADED" : grade.getVerdict().name()),
                    quote(grade == null ? "" : grade.getLecturerNote())
            )).append('\n');
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"grades-lab" + number + "-"
                                + group.name() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------
    //  Downloads and exports
    // ---------------------------------------------------------------

    @GetMapping("/runs/{runId}/export.csv")
    @ResponseBody
    public ResponseEntity<byte[]> exportCsv(@PathVariable long runId) {
        TriageRun run = runs.findById(runId).orElseThrow();
        StringBuilder csv = new StringBuilder();
        csv.append("student,group,lab,submitted_at,late,source_files,"
                + "reference_similarity,reference_variant,peer_similarity,peer_student,"
                + "z_score,needs_review,reasons\n");

        for (Finding f : findings.findByRunOrderByTopReferenceSimilarityDesc(run)) {
            StudentSubmission s = f.getSubmission();
            csv.append(String.join(",",
                    quote(s.getStudentName()),
                    quote(s.getStudentGroup() == null ? "" : s.getStudentGroup().getLabel()),
                    String.valueOf(s.getLabNumber()),
                    quote(String.valueOf(s.getSubmittedAt())),
                    String.valueOf(s.isLate()),
                    String.valueOf(s.getSourceFileCount()),
                    String.format("%.4f", f.getTopReferenceSimilarity()),
                    quote(f.getTopReferenceName()),
                    String.format("%.4f", f.getTopPeerSimilarity()),
                    quote(f.getTopPeerStudentName()),
                    f.getReferenceZScore() == null ? "" : String.format("%.2f", f.getReferenceZScore()),
                    String.valueOf(f.isNeedsReview()),
                    quote(f.getReviewReasons())
            )).append('\n');
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"triage-run-" + runId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/runs/{runId}/report.zip")
    @ResponseBody
    public ResponseEntity<FileSystemResource> jplagReport(@PathVariable long runId) {
        TriageRun run = runs.findById(runId).orElseThrow();
        if (run.getJplagReportPath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(run.getJplagReportPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"jplag-run-" + runId + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    @GetMapping("/submissions/{id}/archive")
    @ResponseBody
    public ResponseEntity<FileSystemResource> archive(@PathVariable long id) {
        StudentSubmission submission = submissions.findById(id).orElseThrow();
        if (submission.getArchivePath() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(submission.getArchivePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String filename = FileOps.slug(submission.getStudentName(), 40)
                + "-lab" + submission.getLabNumber() + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /**
     * The similarity flag from this lab's most recent completed run, shown on the
     * grading page as context only. Displayed beside the grade, never folded into it.
     */
    private Finding latestFindingFor(Lab lab, StudentSubmission submission) {
        return runs.findByLabOrderByStartedAtDesc(lab).stream()
                .filter(r -> r.getStatus() == RunStatus.DONE)
                .findFirst()
                .flatMap(run -> findings.findByRunOrderByTopReferenceSimilarityDesc(run).stream()
                        .filter(f -> f.getSubmission().getId().equals(submission.getId()))
                        .findFirst())
                .orElse(null);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String quote(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    /** One row of the grading table. */
    public static final class GradeRow {
        private final StudentSubmission submission;
        private final Grade grade;
        private final Finding finding;

        GradeRow(StudentSubmission submission, Grade grade, Finding finding) {
            this.submission = submission;
            this.grade = grade;
            this.finding = finding;
        }

        public StudentSubmission getSubmission() { return submission; }
        public Grade getGrade() { return grade; }
        public Finding getFinding() { return finding; }

        public boolean isFlaggedForSimilarity() {
            return finding != null && finding.isNeedsReview();
        }

        public boolean isDecided() {
            return grade != null && grade.isDecided();
        }

        public boolean verdictIs(GradeVerdict verdict) {
            return grade != null && grade.getVerdict() == verdict;
        }

        public boolean isChecked() {
            return grade != null && grade.getCompileOk() != null;
        }
    }

    /** One row of the dashboard. */
    public static final class LabSummary {
        private final Lab lab;
        private final long submissionCount;
        private final long variantCount;
        private final TriageRun lastRun;
        private final long passCount;
        private final long notPassCount;

        LabSummary(Lab lab, long submissionCount, long variantCount, TriageRun lastRun,
                   long passCount, long notPassCount) {
            this.lab = lab;
            this.submissionCount = submissionCount;
            this.variantCount = variantCount;
            this.lastRun = lastRun;
            this.passCount = passCount;
            this.notPassCount = notPassCount;
        }

        public Lab getLab() { return lab; }
        public long getSubmissionCount() { return submissionCount; }
        public long getVariantCount() { return variantCount; }
        public TriageRun getLastRun() { return lastRun; }
        public long getPassCount() { return passCount; }
        public long getNotPassCount() { return notPassCount; }
    }
}

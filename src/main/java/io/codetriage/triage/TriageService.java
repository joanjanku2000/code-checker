package io.codetriage.triage;

import io.codetriage.config.AppProperties;
import io.codetriage.config.StorageLayout;
import io.codetriage.domain.*;
import io.codetriage.domain.repo.*;
import io.codetriage.intake.SubmissionService;
import io.codetriage.similarity.JPlagRunner;
import io.codetriage.similarity.RawPair;
import io.codetriage.util.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Runs one triage pass over a lab.
 *
 * What comes out is a ranked review queue and the numbers behind it. What does
 * not come out is a verdict. Every threshold here decides only whether a human
 * looks at something.
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);

    private final LabRepository labs;
    private final StudentSubmissionRepository submissions;
    private final AiVariantRepository variants;
    private final TriageRunRepository runs;
    private final PairScoreRepository pairs;
    private final FindingRepository findings;
    private final JPlagRunner jplag;
    private final SubmissionService submissionService;
    private final StorageLayout storage;
    private final AppProperties props;
    private final JobRegistry jobs;

    public TriageService(LabRepository labs, StudentSubmissionRepository submissions,
                         AiVariantRepository variants, TriageRunRepository runs,
                         PairScoreRepository pairs, FindingRepository findings,
                         JPlagRunner jplag, SubmissionService submissionService,
                         StorageLayout storage, AppProperties props, JobRegistry jobs) {
        this.labs = labs;
        this.submissions = submissions;
        this.variants = variants;
        this.runs = runs;
        this.pairs = pairs;
        this.findings = findings;
        this.jplag = jplag;
        this.submissionService = submissionService;
        this.storage = storage;
        this.props = props;
        this.jobs = jobs;
    }

    public static String jobKey(Lab lab) {
        return "triage-" + lab.getId();
    }

    @Async("jobExecutor")
    public void runAsync(Long labId) {
        Lab lab = labs.findById(labId).orElse(null);
        if (lab == null) {
            return;
        }
        String key = jobKey(lab);
        jobs.start(key, "Triage for " + lab.getDisplayName(), 5);

        TriageRun run = new TriageRun();
        run.setLab(lab);
        run.setStatus(RunStatus.RUNNING);
        run = runs.save(run);

        try {
            List<StudentSubmission> active =
                    submissions.findByLabAndSupersededFalseOrderByStudentNameAsc(lab);
            List<AiVariant> references = variants.findByLabOrderByIdAsc(lab);

            if (active.size() < 2) {
                throw new IllegalStateException(
                        "At least two submissions are needed to compare anything. Found " + active.size() + ".");
            }

            run.setSubmissionCount(active.size());
            run.setReferenceCount(references.size());
            runs.save(run);

            jobs.progress(key, "Staging " + active.size() + " submissions and "
                    + references.size() + " reference solutions", 1);
            Path submissionsRoot = stage(lab, run.getId(), active, references);

            jobs.progress(key, "Preparing starter code", 2);
            Path baseCode = submissionService.materialiseBaseCode(lab);

            jobs.progress(key, "Comparing (this is the slow part)", 3);
            Path reportZip = storage.runReport(lab, run.getId());
            List<RawPair> rawPairs = jplag.compare(
                    submissionsRoot, baseCode,
                    props.getSimilarity().getMinimumTokenMatch(),
                    reportZip);

            jobs.progress(key, "Scoring " + rawPairs.size() + " comparisons", 4);
            score(run, rawPairs, active);

            if (Files.exists(reportZip)) {
                run.setJplagReportPath(reportZip.toString());
            }
            run.setStatus(RunStatus.DONE);
            run.setFinishedAt(Instant.now());
            runs.save(run);

            jobs.finish(key, "Done. " + rawPairs.size() + " comparisons scored.");

        } catch (Exception e) {
            log.error("Triage run failed for {}", lab.getDisplayName(), e);
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setErrorMessage(e.getMessage() == null ? e.toString() : e.getMessage());
            runs.save(run);
            jobs.fail(key, run.getErrorMessage());
        }
    }

    /**
     * JPlag wants one directory per submission under a single root, so students
     * and reference solutions are copied in side by side. Putting them in one
     * run is what yields the reference-to-reference baseline for free.
     */
    private Path stage(Lab lab, long runId,
                       List<StudentSubmission> active, List<AiVariant> references) throws Exception {
        Path root = storage.runSubmissions(lab, runId);
        FileOps.deleteTree(root);
        Files.createDirectories(root);

        for (StudentSubmission submission : active) {
            if (submission.getSourceDir() == null) continue;
            Path source = Path.of(submission.getSourceDir());
            if (Files.isDirectory(source)) {
                FileOps.copyTree(source, root.resolve(submission.jplagName()));
            }
        }
        for (AiVariant variant : references) {
            if (variant.getSourceDir() == null) continue;
            Path source = Path.of(variant.getSourceDir());
            if (Files.isDirectory(source)) {
                FileOps.copyTree(source, root.resolve(variant.jplagName()));
            }
        }
        return root;
    }

    private void score(TriageRun run, List<RawPair> rawPairs, List<StudentSubmission> active) {

        Map<Long, StudentSubmission> byId = new HashMap<>();
        for (StudentSubmission s : active) {
            byId.put(s.getId(), s);
        }

        Map<Long, Double> topReference = new HashMap<>();
        Map<Long, String> topReferenceName = new HashMap<>();
        Map<Long, Long> topReferenceId = new HashMap<>();
        Map<Long, Double> topPeer = new HashMap<>();
        Map<Long, Long> topPeerId = new HashMap<>();
        Set<Long> compared = new HashSet<>();
        List<Double> referenceToReference = new ArrayList<>();

        List<PairScore> toPersist = new ArrayList<>();

        for (RawPair pair : rawPairs) {
            boolean leftIsStudent = pair.leftName().startsWith("stu_");
            boolean rightIsStudent = pair.rightName().startsWith("stu_");

            PairKind kind;
            if (leftIsStudent && rightIsStudent) {
                kind = PairKind.STUDENT_STUDENT;
            } else if (!leftIsStudent && !rightIsStudent) {
                kind = PairKind.REFERENCE_REFERENCE;
            } else {
                kind = PairKind.STUDENT_REFERENCE;
            }

            PairScore score = new PairScore();
            score.setRun(run);
            score.setKind(kind);
            score.setLeftName(pair.leftName());
            score.setRightName(pair.rightName());
            score.setSimilarity(pair.similarity());
            score.setMaximalSimilarity(pair.maximalSimilarity());
            toPersist.add(score);

            // The metric used for triage is the maximum of average and maximal
            // similarity: a short copied answer buried in a long file shows up in
            // the maximal figure but barely moves the average.
            double metric = Math.max(pair.similarity(), pair.maximalSimilarity());

            switch (kind) {
                case REFERENCE_REFERENCE -> referenceToReference.add(metric);
                case STUDENT_REFERENCE -> {
                    Long studentId = parseStudentId(leftIsStudent ? pair.leftName() : pair.rightName());
                    String refName = leftIsStudent ? pair.rightName() : pair.leftName();
                    if (studentId != null) {
                        compared.add(studentId);
                        if (metric > topReference.getOrDefault(studentId, -1.0)) {
                            topReference.put(studentId, metric);
                            topReferenceName.put(studentId, refName);
                            topReferenceId.put(studentId, parseReferenceId(refName));
                        }
                    }
                }
                case STUDENT_STUDENT -> {
                    Long leftId = parseStudentId(pair.leftName());
                    Long rightId = parseStudentId(pair.rightName());
                    if (leftId != null && rightId != null) {
                        compared.add(leftId);
                        compared.add(rightId);
                        if (metric > topPeer.getOrDefault(leftId, -1.0)) {
                            topPeer.put(leftId, metric);
                            topPeerId.put(leftId, rightId);
                        }
                        if (metric > topPeer.getOrDefault(rightId, -1.0)) {
                            topPeer.put(rightId, metric);
                            topPeerId.put(rightId, leftId);
                        }
                    }
                }
            }
        }
        pairs.saveAll(toPersist);

        // The convergence floor: how alike two independent model answers are anyway.
        Double baseline = median(referenceToReference);
        run.setReferenceBaseline(baseline);

        double[] studentReferenceScores = active.stream()
                .mapToDouble(s -> topReference.getOrDefault(s.getId(), 0.0))
                .toArray();
        CohortStats stats = CohortStats.of(studentReferenceScores);
        run.setStudentMedian(stats.median());
        run.setStudentMad(stats.dispersion());

        Map<String, List<StudentSubmission>> byHash = new HashMap<>();
        for (StudentSubmission s : active) {
            if (s.getSha256() != null) {
                byHash.computeIfAbsent(s.getSha256(), k -> new ArrayList<>()).add(s);
            }
        }

        AppProperties.Similarity cfg = props.getSimilarity();
        List<Finding> results = new ArrayList<>();

        for (StudentSubmission submission : active) {
            Long id = submission.getId();
            double refScore = topReference.getOrDefault(id, 0.0);
            double peerScore = topPeer.getOrDefault(id, 0.0);
            double z = stats.modifiedZScore(refScore);

            Finding finding = new Finding();
            finding.setRun(run);
            finding.setSubmission(submission);
            finding.setTopReferenceSimilarity(refScore);
            finding.setTopReferenceName(topReferenceName.get(id));
            finding.setTopReferenceVariantId(topReferenceId.get(id));
            finding.setTopPeerSimilarity(peerScore);
            finding.setReferenceZScore(z);

            Long peerId = topPeerId.get(id);
            if (peerId != null && byId.containsKey(peerId)) {
                StudentSubmission peer = byId.get(peerId);
                finding.setTopPeerSubmissionId(peer.getId());
                finding.setTopPeerStudentName(peer.getStudentName()
                        + " (" + peer.getStudentGroup().getLabel() + ")");
            }

            List<String> reasons = new ArrayList<>();

            // Byte-identical uploads. Not similarity at all, just the same file.
            List<StudentSubmission> sameArchive = byHash.getOrDefault(submission.getSha256(), List.of());
            if (sameArchive.size() > 1) {
                String others = sameArchive.stream()
                        .filter(s -> !s.getId().equals(id))
                        .map(StudentSubmission::getStudentName)
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
                reasons.add("byte-identical archive to " + others);
            }

            if (!compared.contains(id)) {
                // JPlag produced no comparison at all: usually unparseable code.
                reasons.add("no comparable Java code found, check this one by hand");
            }

            if (peerScore >= cfg.getAbsoluteReviewThreshold()) {
                reasons.add("peer similarity " + percent(peerScore));
            }
            if (refScore >= cfg.getAbsoluteReviewThreshold()) {
                reasons.add("reference similarity " + percent(refScore));
            }
            // Only meaningful if it also clears the floor two model answers hit anyway.
            boolean clearsBaseline = baseline == null || refScore > baseline;
            if (!stats.tooSmallToJudge() && z >= cfg.getOutlierZThreshold() && clearsBaseline) {
                reasons.add(String.format("stands apart from the cohort (z=%.1f)", z));
            }

            finding.setNeedsReview(!reasons.isEmpty());
            finding.setReviewReasons(String.join("; ", reasons));
            results.add(finding);
        }

        findings.saveAll(results);
    }

    private static Long parseStudentId(String jplagName) {
        try {
            return Long.parseLong(jplagName.substring("stu_".length()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "ref_42_bare" -> 42 */
    private static Long parseReferenceId(String jplagName) {
        try {
            String rest = jplagName.substring("ref_".length());
            int underscore = rest.indexOf('_');
            return Long.parseLong(underscore < 0 ? rest : rest.substring(0, underscore));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return (n % 2 == 1) ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }
}

package io.codetriage.intake;

import io.codetriage.config.StorageLayout;
import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentSubmission;
import io.codetriage.domain.repo.StudentSubmissionRepository;
import io.codetriage.util.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    private static final int MAX_NAME_LENGTH = 120;

    private final StudentSubmissionRepository submissions;
    private final SafeZipExtractor extractor;
    private final StorageLayout storage;

    public SubmissionService(StudentSubmissionRepository submissions,
                             SafeZipExtractor extractor,
                             StorageLayout storage) {
        this.submissions = submissions;
        this.extractor = extractor;
        this.storage = storage;
    }

    /**
     * Accepts one student upload. Either the whole thing lands (archive stored,
     * sources extracted, row written) or nothing does.
     */
    public StudentSubmission accept(Lab lab, String rawName, MultipartFile upload) {

        String studentName = normaliseName(rawName);
        if (studentName.isEmpty()) {
            throw new UnsafeArchiveException("Please enter your full name.");
        }
        if (upload == null || upload.isEmpty()) {
            throw new UnsafeArchiveException("Please attach a .zip file.");
        }
        String originalFilename = Optional.ofNullable(upload.getOriginalFilename()).orElse("upload.zip");
        if (!originalFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new UnsafeArchiveException("The solution must be a .zip file.");
        }

        StudentSubmission submission = new StudentSubmission();
        submission.setStudentName(studentName);
        submission.setLab(lab);
        submission.setOriginalFilename(trim(originalFilename, 250));
        submission.setSubmittedAt(Instant.now());
        submission.setLate(lab.getDeadline() != null && Instant.now().isAfter(lab.getDeadline()));
        submission.setArchiveBytes(upload.getSize());
        submission = submissions.save(submission);

        Path archivePath = null;
        Path sourceDir = null;
        try {
            Path archiveDir = storage.ensure(storage.archives(lab));
            archivePath = archiveDir.resolve("sub-" + submission.getId() + ".zip");

            String sha256 = storeAndHash(upload, archivePath);
            submission.setArchivePath(archivePath.toString());
            submission.setSha256(sha256);

            sourceDir = storage.sources(lab).resolve(submission.jplagName());
            FileOps.deleteTree(sourceDir);
            ExtractionResult extraction = extractor.extract(archivePath, sourceDir);

            submission.setSourceDir(sourceDir.toString());
            submission.setSourceFileCount(extraction.getSourceFilesExtracted());

            supersedeEarlier(lab, submission);
            return submissions.save(submission);

        } catch (UnsafeArchiveException e) {
            rollback(submission, archivePath, sourceDir);
            throw e;
        } catch (IOException e) {
            log.error("Failed to store submission for {}", lab.getDisplayName(), e);
            rollback(submission, archivePath, sourceDir);
            throw new UnsafeArchiveException("The upload could not be saved. Please try again.");
        }
    }

    /** Streams to disk while hashing, so the archive is never held in memory. */
    private String storeAndHash(MultipartFile upload, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = upload.getInputStream();
                 DigestInputStream in = new DigestInputStream(raw, digest);
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /** The lab already pins the group, so only the name has to match. */
    private void supersedeEarlier(Lab lab, StudentSubmission current) {
        List<StudentSubmission> active =
                submissions.findByLabAndSupersededFalseOrderByStudentNameAsc(lab);
        for (StudentSubmission other : active) {
            if (other.getId().equals(current.getId())) {
                continue;
            }
            boolean sameStudent = normaliseName(other.getStudentName())
                    .equalsIgnoreCase(normaliseName(current.getStudentName()));
            if (sameStudent) {
                other.setSuperseded(true);
                submissions.save(other);
            }
        }
    }

    private void rollback(StudentSubmission submission, Path archivePath, Path sourceDir) {
        try {
            if (sourceDir != null) FileOps.deleteTree(sourceDir);
            if (archivePath != null) Files.deleteIfExists(archivePath);
        } catch (IOException ignored) {
            // best effort
        }
        try {
            submissions.delete(submission);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Collapses whitespace and strips control characters. */
    public static String normaliseName(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return trim(cleaned, MAX_NAME_LENGTH);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Copies the lab's starter code to disk so JPlag can discount it. */
    public Path materialiseBaseCode(Lab lab) throws IOException {
        Path dir = storage.baseCode(lab);
        FileOps.deleteTree(dir);
        if (lab.getStarterCode() == null || lab.getStarterCode().isBlank()) {
            return null;
        }
        Files.createDirectories(dir);
        var files = io.codetriage.corpus.JavaSourceSplitter.split(
                io.codetriage.corpus.JavaSourceSplitter.extractCode(lab.getStarterCode()));
        for (var file : files) {
            Files.writeString(dir.resolve(file.fileName()), file.content());
        }
        return files.isEmpty() ? null : dir;
    }
}

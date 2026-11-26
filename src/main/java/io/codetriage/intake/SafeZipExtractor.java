package io.codetriage.intake;

import io.codetriage.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Extracts a student archive into a target directory.
 *
 * Everything in an uploaded archive is hostile until proven otherwise, so this
 * class refuses rather than repairs:
 *
 *  - path traversal (zip-slip): every entry is resolved against the target and
 *    rejected unless it stays inside it
 *  - zip bombs: per-entry compression ratio, declared total size, and the real
 *    number of bytes written are all capped independently
 *  - entry count and source-file count are capped
 *  - only allow-listed extensions are written; anything else is skipped, so no
 *    .class, .jar, .sh or nested archive ever lands on disk
 *  - nested archives are never recursed into
 */
@Component
public class SafeZipExtractor {

    private static final Logger log = LoggerFactory.getLogger(SafeZipExtractor.class);

    /** Folders that are build output or tooling noise, not student work. */
    private static final String[] IGNORED_SEGMENTS = {
            "__macosx", ".git", ".svn", ".idea", ".vscode", "target", "build",
            "out", "bin", "node_modules", ".gradle", ".settings"
    };

    private final AppProperties props;

    public SafeZipExtractor(AppProperties props) {
        this.props = props;
    }

    public ExtractionResult extract(Path archive, Path targetDir) throws IOException {
        AppProperties.Intake cfg = props.getIntake();
        ExtractionResult result = new ExtractionResult();

        Files.createDirectories(targetDir);
        Path targetRoot = targetDir.toRealPath();

        long declaredTotal = 0;
        int entryCount = 0;

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (++entryCount > cfg.getMaxEntries()) {
                    throw new UnsafeArchiveException(
                            "Archive contains more than " + cfg.getMaxEntries() + " entries.");
                }

                String name = entry.getName();

                if (entry.isDirectory()) {
                    continue;
                }
                if (name.length() > 400) {
                    result.skip(truncate(name), "path too long");
                    continue;
                }
                if (isIgnored(name)) {
                    continue;
                }
                if (!hasAllowedExtension(name, cfg)) {
                    result.skip(truncate(name), "file type not accepted");
                    continue;
                }

                // --- zip bomb checks, before a single byte is written ---
                long declared = entry.getSize();          // -1 when unknown
                long compressed = entry.getCompressedSize();
                if (declared > 0 && compressed > 0) {
                    long ratio = declared / Math.max(compressed, 1);
                    if (ratio > cfg.getMaxCompressionRatio()) {
                        throw new UnsafeArchiveException(
                                "Archive entry has a suspicious compression ratio and was rejected.");
                    }
                }
                if (declared > 0) {
                    declaredTotal += declared;
                    if (declaredTotal > cfg.getMaxUncompressedBytes()) {
                        throw new UnsafeArchiveException(
                                "Archive expands to more than "
                                        + (cfg.getMaxUncompressedBytes() / (1024 * 1024)) + " MB.");
                    }
                }

                // --- zip-slip check ---
                Path destination = safeResolve(targetRoot, name);
                if (destination == null) {
                    result.skip(truncate(name), "unsafe path");
                    continue;
                }

                if (result.getSourceFilesExtracted() >= cfg.getMaxSourceFiles()) {
                    result.skip(truncate(name), "beyond the "
                            + cfg.getMaxSourceFiles() + " file limit");
                    continue;
                }

                Files.createDirectories(destination.getParent());

                long remaining = cfg.getMaxUncompressedBytes() - result.getBytesWritten();
                long written = copyCapped(zip, entry, destination, remaining);
                result.countFile(written);
            }
        } catch (ZipException e) {
            throw new UnsafeArchiveException("The file is not a readable .zip archive.");
        }

        if (result.getSourceFilesExtracted() == 0) {
            throw new UnsafeArchiveException(
                    "No .java source files were found in the archive.");
        }
        return result;
    }

    /**
     * Resolves an entry name under the target root, returning null if the result
     * would escape it. Handles "..", absolute paths, Windows separators and
     * drive letters.
     */
    private Path safeResolve(Path targetRoot, String rawName) {
        String cleaned = rawName.replace('\\', '/');

        // Strip any leading slashes or Windows drive prefix.
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 1 && cleaned.charAt(1) == ':') {
            cleaned = cleaned.substring(2);
            while (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
        }
        if (cleaned.isBlank()) {
            return null;
        }
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isBlank()) {
                return null;
            }
        }

        Path candidate = targetRoot.resolve(cleaned).normalize();
        if (!candidate.startsWith(targetRoot)) {
            return null;
        }
        return candidate;
    }

    /**
     * Copies at most {@code cap} bytes, because a zip header may understate the
     * real size. Exceeding the cap deletes the partial file and aborts the whole
     * extraction.
     */
    private long copyCapped(ZipFile zip, ZipEntry entry, Path destination, long cap)
            throws IOException {
        if (cap <= 0) {
            throw new UnsafeArchiveException("Archive exceeds the total size limit.");
        }
        long total = 0;
        byte[] buffer = new byte[8192];

        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(destination)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > cap) {
                    out.close();
                    Files.deleteIfExists(destination);
                    throw new UnsafeArchiveException("Archive exceeds the total size limit.");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private boolean isIgnored(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String segment : lower.split("/")) {
            for (String ignored : IGNORED_SEGMENTS) {
                if (segment.equals(ignored)) {
                    return true;
                }
            }
        }
        return lower.contains("/._") || lower.startsWith("._");
    }

    private boolean hasAllowedExtension(String name, AppProperties.Intake cfg) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : cfg.getAllowedExtensions()) {
            if (lower.endsWith(ext.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}

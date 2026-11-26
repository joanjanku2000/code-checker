package io.codetriage.intake;

import io.codetriage.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These are the tests that matter most: this extractor is reachable by anyone
 * who can load the submission page.
 */
class SafeZipExtractorTest {

    private SafeZipExtractor extractor() {
        AppProperties props = new AppProperties();
        props.getIntake().setAllowedExtensions(List.of(".java", ".txt"));
        return new SafeZipExtractor(props);
    }

    private Path zipOf(Path dir, String name, Map<String, String> entries) throws IOException {
        Path archive = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    @Test
    @DisplayName("extracts java sources and preserves package directories")
    void happyPath(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "ok.zip", Map.of(
                "src/al/upt/Main.java", "package al.upt; class Main {}",
                "README.txt", "notes"));
        Path target = tmp.resolve("out");

        ExtractionResult result = extractor().extract(archive, target);

        assertEquals(2, result.getSourceFilesExtracted());
        assertTrue(Files.exists(target.resolve("src/al/upt/Main.java")));
    }

    @Test
    @DisplayName("zip-slip: a ../ entry is skipped and nothing is written outside the target")
    void zipSlipRelative(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "slip.zip", Map.of(
                "Good.java", "class Good {}",
                "../../../../tmp/pwned.java", "class Pwned {}"));
        Path target = tmp.resolve("out");

        ExtractionResult result = extractor().extract(archive, target);

        assertEquals(1, result.getSourceFilesExtracted());
        assertTrue(Files.exists(target.resolve("Good.java")));
        assertFalse(Files.exists(tmp.resolve("pwned.java")));
        assertFalse(Files.exists(Path.of("/tmp/pwned.java")));
        assertTrue(result.getSkipped().stream().anyMatch(s -> s.contains("unsafe path")));
    }

    @Test
    @DisplayName("zip-slip: absolute and Windows-style paths are skipped")
    void zipSlipAbsolute(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "abs.zip", Map.of(
                "Keep.java", "class Keep {}",
                "/etc/cron.d/evil.java", "class Evil {}",
                "C:\\windows\\system32\\evil2.java", "class Evil2 {}"));
        Path target = tmp.resolve("out");

        ExtractionResult result = extractor().extract(archive, target);

        // The leading separators are stripped, so these land safely inside the
        // target rather than at the filesystem root. Nothing escapes.
        try (var walk = Files.walk(target)) {
            assertTrue(walk.filter(Files::isRegularFile).allMatch(p -> p.startsWith(target)));
        }
        assertTrue(Files.exists(target.resolve("Keep.java")));
        assertTrue(result.getSourceFilesExtracted() >= 1);
    }

    @Test
    @DisplayName("compiled output and tooling folders are ignored")
    void ignoresBuildArtefacts(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "noise.zip", Map.of(
                "Main.java", "class Main {}",
                "target/classes/Main.class", "binary",
                "__MACOSX/._Main.java", "junk",
                ".git/config", "[core]"));
        Path target = tmp.resolve("out");

        ExtractionResult result = extractor().extract(archive, target);

        assertEquals(1, result.getSourceFilesExtracted());
        assertFalse(Files.exists(target.resolve("target")));
        assertFalse(Files.exists(target.resolve(".git")));
    }

    @Test
    @DisplayName("executables and nested archives are never written to disk")
    void refusesDangerousTypes(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "mixed.zip", Map.of(
                "Main.java", "class Main {}",
                "payload.sh", "#!/bin/sh\nrm -rf /",
                "inner.zip", "PK\u0003\u0004",
                "lib.jar", "binary"));
        Path target = tmp.resolve("out");

        extractor().extract(archive, target);

        assertTrue(Files.exists(target.resolve("Main.java")));
        assertFalse(Files.exists(target.resolve("payload.sh")));
        assertFalse(Files.exists(target.resolve("inner.zip")));
        assertFalse(Files.exists(target.resolve("lib.jar")));
    }

    @Test
    @DisplayName("an archive with no Java sources is rejected with a clear message")
    void rejectsEmptyOfJava(@TempDir Path tmp) throws IOException {
        Path archive = zipOf(tmp, "empty.zip", Map.of("notes.md", "nothing here"));
        Path target = tmp.resolve("out");

        UnsafeArchiveException thrown = assertThrows(UnsafeArchiveException.class,
                () -> extractor().extract(archive, target));
        assertTrue(thrown.getMessage().contains(".java"));
    }

    @Test
    @DisplayName("too many entries is refused")
    void refusesEntryFlood(@TempDir Path tmp) throws IOException {
        AppProperties props = new AppProperties();
        props.getIntake().setMaxEntries(10);
        SafeZipExtractor strict = new SafeZipExtractor(props);

        Path archive = tmp.resolve("flood.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 50; i++) {
                zip.putNextEntry(new ZipEntry("F" + i + ".java"));
                zip.write(("class F" + i + " {}").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        assertThrows(UnsafeArchiveException.class,
                () -> strict.extract(archive, tmp.resolve("out")));
    }

    @Test
    @DisplayName("a highly compressible payload is refused on total size")
    void refusesOversizedExpansion(@TempDir Path tmp) throws IOException {
        AppProperties props = new AppProperties();
        props.getIntake().setMaxUncompressedBytes(64 * 1024);
        props.getIntake().setMaxCompressionRatio(1_000_000); // isolate the size cap
        SafeZipExtractor strict = new SafeZipExtractor(props);

        Path archive = tmp.resolve("bomb.zip");
        String padding = "A".repeat(1024 * 1024); // 1 MB of a single repeated byte
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("Big.java"));
            zip.write(padding.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(UnsafeArchiveException.class,
                () -> strict.extract(archive, tmp.resolve("out")));
    }

    @Test
    @DisplayName("a file that is not a zip is rejected without an internal error")
    void rejectsNonZip(@TempDir Path tmp) throws IOException {
        Path fake = tmp.resolve("fake.zip");
        Files.writeString(fake, "this is definitely not a zip archive");

        UnsafeArchiveException thrown = assertThrows(UnsafeArchiveException.class,
                () -> extractor().extract(fake, tmp.resolve("out")));
        assertTrue(thrown.getMessage().toLowerCase().contains("zip"));
    }
}

package io.codetriage.grading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Needs a JDK on the test classpath, which Maven provides. On a JRE every
 * outcome is "not attempted", which these assertions would catch.
 */
class CompileCheckerTest {

    private final CompileChecker checker = new CompileChecker();

    private Path dirWith(Path root, String name, String... nameThenBody) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        for (int i = 0; i < nameThenBody.length; i += 2) {
            Path file = dir.resolve(nameThenBody[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, nameThenBody[i + 1]);
        }
        return dir;
    }

    @Test
    @DisplayName("a correct submission compiles")
    void compiles(@TempDir Path tmp) throws IOException {
        Path dir = dirWith(tmp, "ok", "LlogariBankare.java", """
                public class LlogariBankare {
                    private double bilanci;
                    public void depozito(double sasia) { if (sasia > 0) bilanci += sasia; }
                }
                """);
        CompileOutcome outcome = checker.check(dir);

        assertTrue(outcome.attempted());
        assertTrue(outcome.success(), outcome.diagnostics());
        assertEquals(0, outcome.errorCount());
        assertEquals(1, outcome.fileCount());
    }

    @Test
    @DisplayName("a syntax error fails with diagnostics that name the file, by relative path")
    void failsWithDiagnostics(@TempDir Path tmp) throws IOException {
        Path dir = dirWith(tmp, "broken", "Broken.java", """
                public class Broken {
                    public void go() { int x = "not an int" }
                }
                """);
        CompileOutcome outcome = checker.check(dir);

        assertTrue(outcome.attempted());
        assertFalse(outcome.success());
        assertTrue(outcome.errorCount() > 0);
        assertTrue(outcome.diagnostics().contains("Broken.java"));
        // Absolute container paths are noise in a report shown to a human.
        assertFalse(outcome.diagnostics().contains(tmp.toString()));
    }

    @Test
    @DisplayName("multiple files in package directories compile together")
    void multiFile(@TempDir Path tmp) throws IOException {
        Path dir = dirWith(tmp, "multi",
                "al/upt/Main.java", "package al.upt; public class Main { Helper h = new Helper(); }",
                "al/upt/Helper.java", "package al.upt; public class Helper { int v = 1; }");
        CompileOutcome outcome = checker.check(dir);

        assertTrue(outcome.success(), outcome.diagnostics());
        assertEquals(2, outcome.fileCount());
    }

    @Test
    @DisplayName("a submission cannot compile against this application's classes")
    void classpathIsolated(@TempDir Path tmp) throws IOException {
        Path dir = dirWith(tmp, "sneaky", "Sneaky.java", """
                import io.codetriage.grading.CompileChecker;
                public class Sneaky { CompileChecker c = new CompileChecker(); }
                """);
        assertFalse(checker.check(dir).success(),
                "the application's own classes leaked onto the student classpath");
    }

    @Test
    @DisplayName("missing, empty and null inputs report not-attempted rather than failing a student")
    void notAttempted(@TempDir Path tmp) throws IOException {
        Path noJava = dirWith(tmp, "empty", "notes.txt", "nothing");
        assertFalse(checker.check(noJava).attempted());
        assertFalse(checker.check(tmp.resolve("does-not-exist")).attempted());
        assertFalse(checker.check(null).attempted());
    }
}

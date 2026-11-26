package io.codetriage.grading;

import io.codetriage.util.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Compiles a submission and reports whether it builds.
 *
 * This runs javac in-process through javax.tools. It does not execute student
 * code, and that distinction is the whole reason grading stops here: running an
 * unknown student's program on the VPS would be arbitrary code execution on a
 * host that also holds every other student's work. Compiling is safe, provided
 * annotation processing is off — a processor is code, and javac would run it. So
 * -proc:none is not a tidiness flag, it is the boundary.
 *
 * The classpath is also left empty on purpose, so a submission cannot compile
 * against this application's own classes.
 *
 * Requires a JDK at runtime. On a JRE, getSystemJavaCompiler() returns null and
 * this reports "not attempted" rather than failing the student.
 */
@Component
public class CompileChecker {

    private static final Logger log = LoggerFactory.getLogger(CompileChecker.class);
    private static final int MAX_DIAGNOSTIC_LINES = 40;

    public CompileOutcome check(Path sourceDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.warn("No system Java compiler. Running on a JRE instead of a JDK?");
            return CompileOutcome.notAttempted(
                    "No Java compiler available in this runtime (a JDK is required).");
        }
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return CompileOutcome.notAttempted("Source directory is missing.");
        }

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return CompileOutcome.notAttempted("Could not read the source directory.");
        }

        if (sources.isEmpty()) {
            return CompileOutcome.notAttempted("No .java files to compile.");
        }

        Path outputDir = null;
        try {
            outputDir = Files.createTempDirectory("codetriage-compile-");

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, null)) {

                Iterable<? extends JavaFileObject> units =
                        fileManager.getJavaFileObjectsFromFiles(
                                sources.stream().map(Path::toFile).toList());

                List<String> options = List.of(
                        "-proc:none",          // never run annotation processors: they are code
                        "-nowarn",
                        "-classpath", "",      // no access to this application's classes
                        "-d", outputDir.toString());

                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fileManager, diagnostics, options, null, units);

                boolean success = Boolean.TRUE.equals(task.call());

                int errors = 0;
                List<String> lines = new ArrayList<>();
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        errors++;
                    }
                    if (lines.size() < MAX_DIAGNOSTIC_LINES) {
                        lines.add(format(diagnostic, sourceDir));
                    }
                }
                if (diagnostics.getDiagnostics().size() > lines.size()) {
                    lines.add("... " + (diagnostics.getDiagnostics().size() - lines.size())
                            + " more");
                }

                return new CompileOutcome(true, success, errors, sources.size(),
                        lines.isEmpty() ? "Compiled cleanly." : String.join("\n", lines));
            }

        } catch (IOException e) {
            return CompileOutcome.notAttempted("Compile check failed to start: " + e.getMessage());
        } catch (RuntimeException e) {
            // A sufficiently broken source file can make javac itself throw.
            return CompileOutcome.notAttempted("The compiler could not process these files: "
                    + e.getClass().getSimpleName());
        } finally {
            if (outputDir != null) {
                try {
                    FileOps.deleteTree(outputDir);
                } catch (IOException ignored) {
                    // temp dir; best effort
                }
            }
        }
    }

    /** Relative paths only: absolute container paths are noise in a report. */
    private String format(Diagnostic<? extends JavaFileObject> diagnostic, Path sourceDir) {
        String where = "";
        if (diagnostic.getSource() != null) {
            Path path = Path.of(diagnostic.getSource().getName());
            Path relative = path.startsWith(sourceDir) ? sourceDir.relativize(path) : path.getFileName();
            where = relative + ":" + diagnostic.getLineNumber() + ": ";
        }
        return where + diagnostic.getKind().toString().toLowerCase(Locale.ENGLISH)
                + ": " + diagnostic.getMessage(Locale.ENGLISH);
    }
}

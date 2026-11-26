package io.codetriage.config;

import io.codetriage.domain.Lab;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Single source of truth for where anything lives on disk. */
@Component
public class StorageLayout {

    private final Path root;

    public StorageLayout(AppProperties props) {
        this.root = Paths.get(props.getDataDir()).toAbsolutePath().normalize();
    }

    public Path root() { return root; }

    /** e.g. data/labs/lab-3-INF_2A — readable when you are poking around on the VPS. */
    public Path labDir(Lab lab) { return root.resolve("labs").resolve("lab-" + lab.getSlug()); }

    public Path archives(Lab lab) { return labDir(lab).resolve("archives"); }

    public Path sources(Lab lab) { return labDir(lab).resolve("sources"); }

    public Path references(Lab lab) { return labDir(lab).resolve("references"); }

    public Path baseCode(Lab lab) { return labDir(lab).resolve("basecode"); }

    public Path assignment(Lab lab) { return labDir(lab).resolve("assignment"); }

    public Path runDir(Lab lab, long runId) { return labDir(lab).resolve("runs").resolve("run-" + runId); }

    public Path runSubmissions(Lab lab, long runId) { return runDir(lab, runId).resolve("submissions"); }

    public Path runReport(Lab lab, long runId) { return runDir(lab, runId).resolve("jplag-report.zip"); }

    public Path ensure(Path path) throws IOException {
        Files.createDirectories(path);
        return path;
    }
}

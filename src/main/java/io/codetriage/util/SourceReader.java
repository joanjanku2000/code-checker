package io.codetriage.util;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Concatenates a submission's .java files for display and for assessment. */
@Component
public class SourceReader {

    private static final int MAX_CHARS = 200_000;

    public String read(String dir) throws IOException {
        if (dir == null) {
            return "";
        }
        return read(Path.of(dir));
    }

    public String read(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                if (sb.length() > MAX_CHARS) {
                    sb.append("\n// [truncated]\n");
                    break;
                }
                sb.append("// ---- ").append(root.relativize(file)).append(" ----\n");
                sb.append(Files.readString(file)).append("\n\n");
            }
        }
        return sb.toString();
    }
}

package io.codetriage.corpus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a model's reply into compilable .java files.
 *
 * This matters more than it looks. JPlag parses Java with javac, and javac
 * rejects a public type that is not in a file of its own name. A reply holding
 * three classes written to one Solution.java simply fails to parse, the
 * reference silently drops out of the corpus, and the whole comparison quietly
 * gets weaker. So each top-level type is given its own correctly named file.
 *
 * Pure functions, no Spring: this is the part worth unit testing.
 */
public final class JavaSourceSplitter {

    private static final Pattern FENCE = Pattern.compile(
            "```[ \\t]*([A-Za-z0-9+#-]*)[ \\t]*\\r?\\n(.*?)```",
            Pattern.DOTALL);

    private static final Pattern TYPE_NAME = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    private static final Set<String> HEADER_KEYWORDS = Set.of("package", "import");

    private JavaSourceSplitter() {
    }

    /**
     * Pulls code out of a chat reply. Fenced blocks win; if there are none the
     * whole reply is treated as code.
     */
    public static String extractCode(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        Matcher m = FENCE.matcher(reply);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String lang = m.group(1) == null ? "" : m.group(1).trim().toLowerCase();
            // Keep java and unlabelled blocks; skip shell/output/text blocks.
            if (lang.isEmpty() || lang.equals("java") || lang.equals("jav")) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(m.group(2).stripTrailing());
            }
        }
        return sb.isEmpty() ? reply.strip() : sb.toString();
    }

    /**
     * Splits code into one file per top-level type, each carrying the shared
     * package and import header.
     */
    public static List<SourceFile> split(String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip();
        List<SourceFile> files = new ArrayList<>();
        if (code.isEmpty()) {
            return files;
        }

        int headerEnd = headerEndIndex(code);
        String header = code.substring(0, headerEnd).strip();
        String body = code.substring(headerEnd);

        List<String> chunks = topLevelChunks(body);
        if (chunks.isEmpty()) {
            // No braces at all, or unbalanced: keep it whole rather than lose it.
            files.add(new SourceFile("Main.java", code));
            return files;
        }

        Set<String> used = new HashSet<>();
        int anonymous = 0;

        for (String chunk : chunks) {
            String trimmed = chunk.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher nameMatch = TYPE_NAME.matcher(trimmed);
            String typeName = nameMatch.find() ? nameMatch.group(1) : "Fragment" + (++anonymous);

            String fileName = typeName + ".java";
            int suffix = 2;
            while (!used.add(fileName)) {
                fileName = typeName + suffix++ + ".java";
            }

            String content = header.isEmpty() ? trimmed : header + "\n\n" + trimmed;
            files.add(new SourceFile(fileName, content + "\n"));
        }

        if (files.isEmpty()) {
            files.add(new SourceFile("Main.java", code));
        }
        return files;
    }

    /**
     * Index just past the leading package/import/comment block.
     */
    private static int headerEndIndex(String code) {
        int index = 0;
        int lastHeaderEnd = 0;
        boolean inBlockComment = false;

        while (index < code.length()) {
            int lineEnd = code.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = code.length();
            }
            String line = code.substring(index, lineEnd).strip();

            boolean isHeaderLine;
            if (inBlockComment) {
                isHeaderLine = true;
                if (line.contains("*/")) {
                    inBlockComment = false;
                }
            } else if (line.isEmpty() || line.startsWith("//")) {
                isHeaderLine = true;
            } else if (line.startsWith("/*")) {
                isHeaderLine = true;
                inBlockComment = !line.contains("*/");
            } else {
                String firstWord = line.split("[\\s;]+", 2)[0];
                isHeaderLine = HEADER_KEYWORDS.contains(firstWord);
            }

            if (!isHeaderLine) {
                break;
            }
            index = lineEnd + 1;
            // Only package/import lines actually extend the header we reuse.
            if (!inBlockComment) {
                String firstWord = line.isEmpty() ? "" : line.split("[\\s;]+", 2)[0];
                if (HEADER_KEYWORDS.contains(firstWord)) {
                    lastHeaderEnd = index;
                }
            }
        }
        return Math.min(lastHeaderEnd, code.length());
    }

    /**
     * Splits at top-level brace boundaries, ignoring braces inside comments,
     * string literals, text blocks and char literals.
     */
    private static List<String> topLevelChunks(String body) {
        List<String> chunks = new ArrayList<>();
        int depth = 0;
        int segmentStart = 0;
        int i = 0;
        int n = body.length();
        boolean sawAnyBrace = false;

        while (i < n) {
            char c = body.charAt(i);

            // line comment
            if (c == '/' && i + 1 < n && body.charAt(i + 1) == '/') {
                int nl = body.indexOf('\n', i);
                i = (nl < 0) ? n : nl + 1;
                continue;
            }
            // block comment
            if (c == '/' && i + 1 < n && body.charAt(i + 1) == '*') {
                int end = body.indexOf("*/", i + 2);
                i = (end < 0) ? n : end + 2;
                continue;
            }
            // text block
            if (c == '"' && i + 2 < n && body.charAt(i + 1) == '"' && body.charAt(i + 2) == '"') {
                int end = body.indexOf("\"\"\"", i + 3);
                i = (end < 0) ? n : end + 3;
                continue;
            }
            // string literal
            if (c == '"') {
                i++;
                while (i < n) {
                    char s = body.charAt(i);
                    if (s == '\\') {
                        i += 2;
                        continue;
                    }
                    if (s == '"' || s == '\n') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // char literal
            if (c == '\'') {
                i++;
                while (i < n) {
                    char s = body.charAt(i);
                    if (s == '\\') {
                        i += 2;
                        continue;
                    }
                    if (s == '\'' || s == '\n') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            if (c == '{') {
                depth++;
                sawAnyBrace = true;
            } else if (c == '}') {
                depth--;
                if (depth <= 0) {
                    chunks.add(body.substring(segmentStart, Math.min(i + 1, n)));
                    segmentStart = i + 1;
                    depth = 0;
                }
            }
            i++;
        }

        // Trailing text after the last closing brace, if it holds a declaration.
        if (segmentStart < n) {
            String tail = body.substring(segmentStart).strip();
            if (!tail.isEmpty() && TYPE_NAME.matcher(tail).find()) {
                chunks.add(tail);
            }
        }
        return sawAnyBrace ? chunks : List.of();
    }
}

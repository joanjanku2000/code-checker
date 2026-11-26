package io.codetriage.grading;

/**
 * Finds the first balanced JSON object in a model reply.
 *
 * Asking for "only JSON" mostly works, but not reliably: replies arrive wrapped
 * in ```json fences, prefaced with "Here is the assessment:", or followed by a
 * remark. Taking indexOf('{') to lastIndexOf('}') is the obvious approach and
 * breaks on any trailing prose containing a brace, so this scans for a properly
 * balanced object and ignores braces inside string literals.
 *
 * Pure string handling, no JSON library: this is about locating the object, not
 * interpreting it.
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    /** Returns the first balanced {...} object, or null if there is none. */
    public static String extractFirstObject(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        while (start >= 0) {
            String candidate = scanObject(reply, start);
            if (candidate != null) {
                return candidate;
            }
            start = reply.indexOf('{', start + 1);
        }
        return null;
    }

    private static String scanObject(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
                if (depth < 0) {
                    return null;
                }
            }
        }
        return null; // never closed
    }
}

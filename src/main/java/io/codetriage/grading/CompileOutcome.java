package io.codetriage.grading;

public record CompileOutcome(
        boolean attempted,
        boolean success,
        int errorCount,
        int fileCount,
        String diagnostics
) {
    public static CompileOutcome notAttempted(String reason) {
        return new CompileOutcome(false, false, 0, 0, reason);
    }
}

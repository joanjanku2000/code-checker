package io.codetriage.intake;

/** Thrown when an uploaded archive is malformed, oversized, or hostile. */
public class UnsafeArchiveException extends RuntimeException {
    public UnsafeArchiveException(String message) {
        super(message);
    }
}

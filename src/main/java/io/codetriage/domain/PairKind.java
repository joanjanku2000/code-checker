package io.codetriage.domain;

public enum PairKind {
    /** A student's work against a generated reference solution. */
    STUDENT_REFERENCE,
    /** Two students against each other. */
    STUDENT_STUDENT,
    /** Two reference solutions against each other: the convergence baseline. */
    REFERENCE_REFERENCE
}

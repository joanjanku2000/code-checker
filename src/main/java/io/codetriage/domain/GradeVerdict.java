package io.codetriage.domain;

public enum GradeVerdict {
    UNGRADED("Not graded"),
    PASS("Pass"),
    NOT_PASS("Not pass");

    private final String label;

    GradeVerdict(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

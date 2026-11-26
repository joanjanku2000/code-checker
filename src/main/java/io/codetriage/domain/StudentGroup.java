package io.codetriage.domain;

/**
 * The student groups this course runs. Labels are what students see in the form
 * and what appears on every report, so they match the faculty's own wording.
 */
public enum StudentGroup {
    INF_2A("INF - 2A"),
    INF_2B("INF - 2B"),
    KOMPJUTERIKE("KOMPJUTERIKE");

    private final String label;

    StudentGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

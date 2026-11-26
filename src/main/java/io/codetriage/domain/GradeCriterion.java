package io.codetriage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;

/** One line of the rubric assessment. A suggestion, not a decision. */
@Embeddable
public class GradeCriterion {

    @Column(length = 300)
    private String requirement;

    private boolean met;

    @Lob
    @Column(length = 4000)
    private String note;

    public GradeCriterion() {
    }

    public GradeCriterion(String requirement, boolean met, String note) {
        this.requirement = requirement;
        this.met = met;
        this.note = note;
    }

    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }

    public boolean isMet() { return met; }
    public void setMet(boolean met) { this.met = met; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}

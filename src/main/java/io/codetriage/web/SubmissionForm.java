package io.codetriage.web;

import io.codetriage.domain.StudentGroup;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class SubmissionForm {

    @NotBlank(message = "Shkruani emrin dhe mbiemrin.")
    @Size(max = 120, message = "Emri është shumë i gjatë.")
    private String studentName;

    @NotNull(message = "Zgjidhni laboratorin.")
    @Min(value = 1, message = "Laboratori duhet të jetë nga 1 deri në 5.")
    @Max(value = 5, message = "Laboratori duhet të jetë nga 1 deri në 5.")
    private Integer labNumber;

    @NotNull(message = "Zgjidhni grupin.")
    private StudentGroup studentGroup;

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public Integer getLabNumber() { return labNumber; }
    public void setLabNumber(Integer labNumber) { this.labNumber = labNumber; }

    public StudentGroup getStudentGroup() { return studentGroup; }
    public void setStudentGroup(StudentGroup studentGroup) { this.studentGroup = studentGroup; }
}

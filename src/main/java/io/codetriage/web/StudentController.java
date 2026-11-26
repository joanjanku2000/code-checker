package io.codetriage.web;

import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentGroup;
import io.codetriage.domain.StudentSubmission;
import io.codetriage.domain.repo.LabRepository;
import io.codetriage.intake.SubmissionService;
import io.codetriage.intake.UnsafeArchiveException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Controller
public class StudentController {

    private final LabRepository labs;
    private final SubmissionService submissionService;

    public StudentController(LabRepository labs, SubmissionService submissionService) {
        this.labs = labs;
        this.submissionService = submissionService;
    }

    @ModelAttribute("groups")
    public StudentGroup[] groups() {
        return StudentGroup.values();
    }

    /**
     * Every lab currently accepting work, across all groups. The form filters
     * this list by the group the student picks, and the server checks the pair
     * again on submit — the client-side filter is convenience, not a control.
     */
    @ModelAttribute("openLabs")
    public List<Lab> openLabs() {
        return labs.findByOpenTrueOrderByStudentGroupAscNumberAsc().stream()
                .filter(Lab::isAcceptingSubmissions)
                .toList();
    }

    @GetMapping("/")
    public String form(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new SubmissionForm());
        }
        return "submit";
    }

    @PostMapping("/submit")
    public String submit(@Valid @ModelAttribute("form") SubmissionForm form,
                         BindingResult binding,
                         @RequestParam(value = "solution", required = false) MultipartFile solution,
                         Model model) {

        if (binding.hasErrors()) {
            return "submit";
        }

        Lab lab = labs.findByNumberAndStudentGroup(form.getLabNumber(), form.getStudentGroup())
                .orElse(null);

        if (lab == null || !lab.isAcceptingSubmissions()) {
            model.addAttribute("error",
                    "Laboratori " + form.getLabNumber() + " nuk është i hapur për grupin "
                            + form.getStudentGroup().getLabel() + ".");
            return "submit";
        }

        try {
            StudentSubmission saved = submissionService.accept(lab, form.getStudentName(), solution);
            model.addAttribute("submission", saved);
            return "submitted";

        } catch (UnsafeArchiveException e) {
            model.addAttribute("error", e.getMessage());
            return "submit";
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(Model model) {
        model.addAttribute("form", new SubmissionForm());
        model.addAttribute("groups", StudentGroup.values());
        model.addAttribute("openLabs", openLabs());
        model.addAttribute("error", "Skedari është shumë i madh. Kufiri është 20 MB.");
        return "submit";
    }
}

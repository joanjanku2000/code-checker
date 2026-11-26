package io.codetriage.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codetriage.domain.GradeCriterion;
import io.codetriage.domain.GradeVerdict;
import io.codetriage.llm.LlmClient;
import io.codetriage.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assesses a submission against the assignment text and proposes a verdict.
 *
 * A proposal, not a decision. The lecturer confirms or overrides it, and the
 * stored grade is always theirs — see {@link io.codetriage.domain.Grade}.
 *
 * The prompt forbids any comment on authorship. The assessor's job is "does
 * this meet the stated requirements"; whether code was AI-written is a separate
 * question this application answers separately, and badly mixing the two would
 * let an unprovable hunch leak into a mark.
 */
@Component
public class RubricAssessor {

    private static final Logger log = LoggerFactory.getLogger(RubricAssessor.class);

    private static final int MAX_ASSIGNMENT_CHARS = 12_000;
    private static final int MAX_CODE_CHARS = 24_000;

    private static final String SYSTEM = """
            You are assisting a university lecturer in assessing first-year Java \
            lab submissions. You assess a submission only against the requirements \
            stated in the assignment.

            You never speculate about whether code was written by a person, by an AI, \
            or copied from anywhere. Authorship is out of scope and is handled \
            separately. Assess the work in front of you on its merits.""";

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public RubricAssessor(LlmClient llm) {
        this.llm = llm;
    }

    public boolean isAvailable() {
        return llm.isConfigured();
    }

    public record Assessment(List<GradeCriterion> criteria,
                             GradeVerdict suggestedVerdict,
                             String reasoning) {
    }

    public Assessment assess(String assignmentText, String studentCode) {
        if (!llm.isConfigured()) {
            throw new LlmException("ANTHROPIC_API_KEY is not set, so no assessment can be made.");
        }
        if (assignmentText == null || assignmentText.isBlank()) {
            throw new LlmException("This lab has no assignment text to assess against.");
        }
        if (studentCode == null || studentCode.isBlank()) {
            throw new LlmException("This submission has no readable code.");
        }

        String prompt = buildPrompt(
                truncate(assignmentText, MAX_ASSIGNMENT_CHARS),
                truncate(studentCode, MAX_CODE_CHARS));

        // Asks for temperature 0, but most models now reject the parameter and
        // it is suppressed by default (see AppProperties.Llm.sendTemperature).
        // So repeated assessments of the same submission can differ slightly.
        // That is a reason to read the criteria rather than trust the verdict,
        // which is what the lecturer is asked to do anyway.
        String reply = llm.complete(SYSTEM, prompt, 0.0);

        String json = JsonExtractor.extractFirstObject(reply);
        if (json == null) {
            throw new LlmException("The assessment reply contained no JSON object.");
        }
        return parse(json);
    }

    private String buildPrompt(String assignmentText, String studentCode) {
        return """
                ASSIGNMENT
                ----------
                %s

                STUDENT SUBMISSION
                ------------------
                %s

                Assess whether this submission satisfies the requirements stated in the
                assignment above.

                Reply with only a JSON object of this shape:

                {
                  "criteria": [
                    {"requirement": "short statement of one requirement",
                     "met": true,
                     "note": "what the code does or fails to do, one or two sentences"}
                  ],
                  "pass": true,
                  "reasoning": "two or three sentences explaining the overall verdict"
                }

                Rules:
                - Derive every requirement from the assignment text alone. Do not invent
                  requirements it does not state.
                - Give between 3 and 8 criteria.
                - Judge correctness and completeness against the requirements. Ignore
                  formatting and naming style unless the assignment demands them.
                - If the assignment is ambiguous on a point, mark the criterion met and
                  say so in the note.
                - "pass" should be false only when a requirement central to the exercise
                  is genuinely unmet.
                - Do not comment on authorship, originality, or whether the code appears
                  AI-generated.
                """.formatted(assignmentText, studentCode);
    }

    private Assessment parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            List<GradeCriterion> criteria = new ArrayList<>();
            for (JsonNode node : root.path("criteria")) {
                String requirement = node.path("requirement").asText("").strip();
                if (requirement.isEmpty()) {
                    continue;
                }
                criteria.add(new GradeCriterion(
                        cap(requirement, 300),
                        node.path("met").asBoolean(false),
                        cap(node.path("note").asText("").strip(), 3500)));
            }

            if (criteria.isEmpty()) {
                throw new LlmException("The assessment contained no usable criteria.");
            }

            JsonNode passNode = root.path("pass");
            if (passNode.isMissingNode() || passNode.isNull()) {
                throw new LlmException("The assessment did not state a verdict.");
            }
            GradeVerdict suggested = passNode.asBoolean(false)
                    ? GradeVerdict.PASS : GradeVerdict.NOT_PASS;

            String reasoning = cap(root.path("reasoning").asText("").strip(), 7000);

            return new Assessment(criteria, suggested, reasoning);

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not parse the assessment JSON: {}", e.getMessage());
            throw new LlmException("The assessment reply was not valid JSON.");
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n\n[truncated]";
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

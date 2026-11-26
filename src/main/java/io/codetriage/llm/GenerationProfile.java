package io.codetriage.llm;

/**
 * One way of asking a model for a solution.
 *
 * The corpus is only as good as its spread. A student who pastes the task with
 * no instructions, one who asks for heavy comments, and one who prompts in
 * Albanian get materially different code, so the reference set has to contain
 * all three. Each profile is run several times at different temperatures.
 *
 * @param key         short, filesystem-safe identifier shown on reports
 * @param description what this profile imitates, in plain words
 * @param system      system prompt, or empty for none
 * @param userPattern user prompt; "{TASK}" is replaced with the lab text
 */
public record GenerationProfile(
        String key,
        String description,
        String system,
        String userPattern
) {
    public String renderUser(String taskText) {
        return userPattern.replace("{TASK}", taskText);
    }
}

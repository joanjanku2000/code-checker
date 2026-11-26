package io.codetriage.llm;

import java.util.List;

/**
 * The built-in catalogue. Add to this freely: a wider corpus raises the chance
 * that a copied answer lands close to something in it, and costs only tokens.
 */
public final class GenerationProfiles {

    private GenerationProfiles() {
    }

    public static final List<GenerationProfile> DEFAULTS = List.of(

            new GenerationProfile("bare",
                    "Task pasted with no instructions at all: the most common student prompt.",
                    "",
                    "{TASK}"),

            new GenerationProfile("just_code",
                    "Asks for code only, no explanation.",
                    "You are a helpful programming assistant.",
                    "Solve the following in Java. Give only the code, no explanation.\n\n{TASK}"),

            new GenerationProfile("student_voice",
                    "Asks the model to write like a first-year student.",
                    "You are helping a first-year university student with a Java lab exercise.",
                    "Write a solution to this the way a first-year student would: simple, "
                            + "no advanced language features, no streams or lambdas.\n\n{TASK}"),

            new GenerationProfile("commented",
                    "Asks for thorough explanatory comments.",
                    "You are a patient programming tutor.",
                    "Solve this in Java and add clear comments explaining each step.\n\n{TASK}"),

            new GenerationProfile("idiomatic",
                    "Asks for clean, production-quality Java.",
                    "You are a senior Java engineer.",
                    "Implement this in clean, idiomatic, production-quality Java with proper "
                            + "validation and error handling.\n\n{TASK}"),

            new GenerationProfile("minimal",
                    "Asks for the shortest thing that works.",
                    "",
                    "Give me the shortest correct Java solution to this. No comments.\n\n{TASK}"),

            new GenerationProfile("full_oop",
                    "Asks for separate classes, encapsulation, getters and setters.",
                    "You are a Java instructor demonstrating object-oriented design.",
                    "Solve this in Java using proper object-oriented design: separate classes, "
                            + "private fields, getters and setters.\n\n{TASK}"),

            new GenerationProfile("albanian",
                    "Prompted in Albanian, which yields Albanian identifiers and comments.",
                    "Je një asistent programimi që ndihmon studentët e inxhinierisë informatike.",
                    "Zgjidh këtë ushtrim në Java. Shkruaj kodin me komente në shqip.\n\n{TASK}")
    );
}

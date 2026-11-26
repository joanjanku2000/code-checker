package io.codetriage.grading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonExtractorTest {

    @Test
    @DisplayName("strips a ```json fence and surrounding prose")
    void fenced() {
        String json = JsonExtractor.extractFirstObject("""
                Here is the assessment:

                ```json
                {"criteria": [{"requirement": "Has a class", "met": true}], "pass": true}
                ```

                Let me know if you want more detail.
                """);
        assertNotNull(json);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"criteria\""));
        assertFalse(json.contains("Let me know"));
    }

    @Test
    @DisplayName("trailing prose with a brace does not extend the match")
    void trailingBrace() {
        // The naive indexOf('{')..lastIndexOf('}') approach fails this case.
        String json = JsonExtractor.extractFirstObject(
                "{\"pass\": false}\n\nNote: they wrote `if (x) { return; }` which is incomplete.");
        assertEquals("{\"pass\": false}", json);
    }

    @Test
    @DisplayName("braces and escapes inside string values are ignored")
    void bracesInStrings() {
        assertTrue(JsonExtractor.extractFirstObject(
                "{\"note\": \"a block { not balanced\", \"pass\": true}").endsWith("\"pass\": true}"));
        assertTrue(JsonExtractor.extractFirstObject(
                "{\"note\": \"they wrote \\\"System.out\\\" at C:\\\\tmp\", \"pass\": true}")
                .endsWith("\"pass\": true}"));
    }

    @Test
    @DisplayName("nesting is captured in full")
    void nested() {
        assertEquals("{\"a\": {\"b\": [1, {\"c\": true}]}, \"pass\": true}",
                JsonExtractor.extractFirstObject(
                        "x {\"a\": {\"b\": [1, {\"c\": true}]}, \"pass\": true} y"));
    }

    @Test
    @DisplayName("an unterminated object is rejected rather than half-returned")
    void unterminated() {
        assertNull(JsonExtractor.extractFirstObject("{\"pass\": true, \"criteria\": ["));
    }

    @Test
    @DisplayName("a stray brace in prose before the JSON is skipped")
    void falseStart() {
        assertEquals("{\"pass\": true}", JsonExtractor.extractFirstObject(
                "The student used { braces oddly. Assessment:\n{\"pass\": true}"));
    }

    @Test
    @DisplayName("replies with no JSON return null")
    void noJson() {
        assertNull(JsonExtractor.extractFirstObject(null));
        assertNull(JsonExtractor.extractFirstObject(""));
        assertNull(JsonExtractor.extractFirstObject("I cannot assess this."));
    }
}

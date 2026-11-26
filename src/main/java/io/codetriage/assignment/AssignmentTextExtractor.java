package io.codetriage.assignment;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Reads the assignment sheet the lecturer uploads.
 *
 * The extracted text is what the model is given, both for generating reference
 * solutions and for rubric grading, so a bad extraction quietly degrades both.
 * When extraction yields too little to be a real assignment, that is reported
 * rather than passed along.
 */
@Component
public class AssignmentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(AssignmentTextExtractor.class);

    public static final List<String> ACCEPTED = List.of(".txt", ".md", ".pdf", ".docx");

    /** Below this, the file almost certainly did not extract properly. */
    private static final int SUSPICIOUSLY_SHORT = 40;

    public static boolean isAccepted(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return ACCEPTED.stream().anyMatch(lower::endsWith);
    }

    public String extract(Path file, String originalFilename) throws IOException {
        String lower = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        String text;

        if (lower.endsWith(".pdf")) {
            text = fromPdf(file);
        } else if (lower.endsWith(".docx")) {
            text = DocxTextExtractor.extract(file);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            throw new IOException("Unsupported file type. Use .pdf, .docx, .txt or .md.");
        }

        text = text == null ? "" : text.strip();

        if (text.length() < SUSPICIOUSLY_SHORT) {
            throw new IOException("Only " + text.length() + " characters could be read from this "
                    + "file. If it is a scanned PDF, the text is an image and cannot be read — "
                    + "paste the task text instead.");
        }
        log.info("Extracted {} characters from {}", text.length(), originalFilename);
        return text;
    }

    private String fromPdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted()) {
                throw new IOException("This PDF is password-protected and cannot be read.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}

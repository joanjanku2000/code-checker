package io.codetriage.assignment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocxTextExtractorTest {

    private Path docx(Path dir, String name, String documentXml) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    @DisplayName("an assignment sheet survives the round trip with structure intact")
    void realisticSheet(@TempDir Path tmp) throws IOException {
        String xml = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Laboratori 3: Klasa dhe Objekte</w:t></w:r></w:p>
                    <w:p><w:r><w:t xml:space="preserve">Shkruani një klasë </w:t></w:r>
                      <w:r><w:rPr><w:b/></w:rPr><w:t>LlogariBankare</w:t></w:r>
                      <w:r><w:t xml:space="preserve"> me fushat e mëposhtme:</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr>
                      <w:r><w:t>numriLlogarise</w:t></w:r></w:p>
                    <w:p/>
                    <w:p/>
                    <w:p><w:r><w:t>Kushti:</w:t></w:r><w:r><w:tab/></w:r>
                      <w:r><w:t>bilanci &gt;= 0 &amp;&amp; bilanci &lt; 10000</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """;
        String text = DocxTextExtractor.extract(docx(tmp, "lab3.docx", xml));

        assertTrue(text.contains("Laboratori 3: Klasa dhe Objekte"));
        // Runs within a paragraph must join with no inter-element whitespace.
        assertTrue(text.contains("Shkruani një klasë LlogariBankare me fushat e mëposhtme:"), text);
        assertTrue(text.contains("- numriLlogarise"), text);
        assertTrue(text.contains("bilanci >= 0 && bilanci < 10000"), text);
        assertTrue(text.contains("Kushti:\t"), text);
        assertFalse(text.contains("<w:"), text);
        assertFalse(text.contains("\n\n\n"), "blank paragraphs should collapse");
    }

    @Test
    @DisplayName("escaped markup in the author's own prose is not mistaken for a tag")
    void doubleEscaped() {
        String text = DocxTextExtractor.fromDocumentXml(
                "<w:p><w:r><w:t>Use the &amp;lt;b&amp;gt; tag and A &lt; B</w:t></w:r></w:p>");
        assertTrue(text.contains("&lt;b&gt;"), text);
        assertTrue(text.contains("A < B"), text);
    }

    @Test
    @DisplayName("numeric entities are decoded, malformed ones left alone")
    void numericEntities() {
        assertTrue(DocxTextExtractor.fromDocumentXml(
                "<w:p><w:r><w:t>caf&#233; &#x2014; x</w:t></w:r></w:p>").contains("café"));
        assertTrue(DocxTextExtractor.fromDocumentXml(
                "<w:p><w:r><w:t>5 &#notanumber; x</w:t></w:r></w:p>").contains("notanumber"));
    }

    @Test
    @DisplayName("line breaks inside a paragraph become newlines")
    void lineBreaks() {
        String text = DocxTextExtractor.fromDocumentXml(
                "<w:p><w:r><w:t>Rreshti i</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>dyte</w:t></w:r></w:p>");
        assertEquals("Rreshti i\ndyte", text);
    }

    @Test
    @DisplayName("degenerate input is safe")
    void degenerate() {
        assertEquals("", DocxTextExtractor.fromDocumentXml(null));
        assertEquals("", DocxTextExtractor.fromDocumentXml("   "));
    }

    @Test
    @DisplayName("a zip without document.xml, and a non-zip, are both reported clearly")
    void badFiles(@TempDir Path tmp) throws IOException {
        Path noDoc = tmp.resolve("bad.docx");
        try (OutputStream out = Files.newOutputStream(noDoc);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("something.txt"));
            zip.write("nope".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertTrue(assertThrows(IOException.class, () -> DocxTextExtractor.extract(noDoc))
                .getMessage().contains("document.xml"));

        Path fake = tmp.resolve("fake.docx");
        Files.writeString(fake, "plain text pretending to be a docx");
        assertTrue(assertThrows(IOException.class, () -> DocxTextExtractor.extract(fake))
                .getMessage().contains("readable .docx"));
    }
}

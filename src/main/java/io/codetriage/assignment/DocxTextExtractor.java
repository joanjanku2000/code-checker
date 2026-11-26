package io.codetriage.assignment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Pulls plain text out of a .docx without Apache POI.
 *
 * A .docx is a zip holding word/document.xml, and all that is wanted here is the
 * prose of an assignment sheet — no styles, no images, no layout. POI would do
 * this properly but costs about 15 MB of dependencies to read one XML file, so
 * the markup is handled directly.
 *
 * Paragraph and break elements become newlines; every other tag is dropped. The
 * order matters: tags are stripped first and entities unescaped second, so an
 * escaped &lt;b&gt; in the author's text is never mistaken for markup.
 */
public final class DocxTextExtractor {

    private static final String DOCUMENT_ENTRY = "word/document.xml";

    /**
     * Text runs plus the structural elements worth keeping, in document order.
     * Anything this does not match is markup or layout whitespace and is dropped.
     */
    private static final Pattern CONTENT = Pattern.compile(
            "<w:t(?:\\s[^>]*)?>(?<text>.*?)</w:t>"
                    + "|<w:t\\s*/>(?<emptytext>)"
                    + "|<w:tab\\s*/>(?<tab>)"
                    + "|<w:(?:br|cr)\\s*/?>(?<brk>)"
                    + "|<w:numPr>(?<bullet>)"
                    + "|</w:p>(?<para>)",
            Pattern.DOTALL);
    private static final long MAX_XML_BYTES = 20L * 1024 * 1024;

    private DocxTextExtractor() {
    }

    public static String extract(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            ZipEntry entry = zip.getEntry(DOCUMENT_ENTRY);
            if (entry == null) {
                throw new IOException("This .docx has no " + DOCUMENT_ENTRY
                        + " inside, so it may be a .doc renamed to .docx.");
            }
            if (entry.getSize() > MAX_XML_BYTES) {
                throw new IOException("The document body is too large to read.");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes((int) MAX_XML_BYTES);
                return fromDocumentXml(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (ZipException e) {
            throw new IOException("This file is not a readable .docx.");
        }
    }

    /**
     * The whole transformation, separated out so it can be tested directly.
     *
     * Only the contents of &lt;w:t&gt; elements are real document text. Stripping
     * tags globally instead looks simpler and is wrong: a pretty-printed
     * document.xml has newlines and indentation between every element, and all
     * of it survives as spurious whitespace. So the scan picks out text runs and
     * the few structural elements that carry meaning, and ignores everything
     * else — which drops inter-element formatting by construction.
     */
    public static String fromDocumentXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }

        Matcher m = CONTENT.matcher(xml);
        StringBuilder out = new StringBuilder();

        while (m.find()) {
            if (m.group("text") != null) {
                out.append(unescape(m.group("text")));
            } else if (m.group("emptytext") != null) {
                // <w:t/>: a run with no content.
                continue;
            } else if (m.group("tab") != null) {
                out.append('\t');
            } else if (m.group("brk") != null) {
                out.append('\n');
            } else if (m.group("bullet") != null) {
                out.append("- ");
            } else if (m.group("para") != null) {
                out.append('\n');
            }
        }

        String text = out.toString();
        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    private static String unescape(String s) {
        String out = s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
        out = decodeNumericEntities(out);
        // Ampersand last, so "&amp;lt;" does not turn into a tag.
        return out.replace("&amp;", "&");
    }

    private static String decodeNumericEntities(String s) {
        if (s.indexOf("&#") < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("&#", i)) {
                int end = s.indexOf(';', i);
                if (end > i && end - i <= 10) {
                    String body = s.substring(i + 2, end);
                    try {
                        int code = body.startsWith("x") || body.startsWith("X")
                                ? Integer.parseInt(body.substring(1), 16)
                                : Integer.parseInt(body);
                        sb.appendCodePoint(code);
                        i = end + 1;
                        continue;
                    } catch (RuntimeException ignored) {
                        // not a real entity; fall through and copy it verbatim
                    }
                }
            }
            sb.append(s.charAt(i++));
        }
        return sb.toString();
    }
}

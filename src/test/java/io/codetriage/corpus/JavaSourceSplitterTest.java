package io.codetriage.corpus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceSplitterTest {

    private static List<SourceFile> run(String reply) {
        return JavaSourceSplitter.split(JavaSourceSplitter.extractCode(reply));
    }

    private static List<String> names(List<SourceFile> files) {
        return files.stream().map(SourceFile::fileName).toList();
    }

    @Test
    @DisplayName("strips chat prose and names the file after the public class")
    void singleClassWithProse() {
        List<SourceFile> files = run("""
                Sure! Here is a solution:

                ```java
                public class BankAccount {
                    private double balance;
                    public void deposit(double amount) { balance += amount; }
                }
                ```

                Let me know if you need anything else!
                """);
        assertEquals(1, files.size());
        assertEquals("BankAccount.java", files.get(0).fileName());
        assertFalse(files.get(0).content().contains("Sure!"));
    }

    @Test
    @DisplayName("gives every top-level type its own correctly named file, header intact")
    void multipleTypes() {
        List<SourceFile> files = run("""
                ```java
                package al.upt.lab2;

                import java.util.List;
                import java.util.ArrayList;

                public class Library {
                    private List<Book> books = new ArrayList<>();
                }

                class Book {
                    String title;
                }

                interface Lendable {
                    void lend();
                }
                ```
                """);
        assertEquals(List.of("Library.java", "Book.java", "Lendable.java"), names(files));
        for (SourceFile file : files) {
            assertTrue(file.content().contains("package al.upt.lab2;"), file.fileName());
            assertTrue(file.content().contains("import java.util.List;"), file.fileName());
        }
        assertFalse(files.get(1).content().contains("private List<Book>"));
    }

    @Test
    @DisplayName("braces inside strings, chars and comments do not split the file")
    void bracesInLiterals() {
        List<SourceFile> files = run("""
                public class Tricky {
                    // this } brace is a comment
                    /* and this { one too */
                    public void go() {
                        String s = "a } weird { string";
                        char c = '}';
                        System.out.println(s + c);
                    }
                }
                """);
        assertEquals(1, files.size());
        assertEquals("Tricky.java", files.get(0).fileName());
        assertTrue(files.get(0).content().contains("weird { string"));
    }

    @Test
    @DisplayName("handles enums, records and unfenced replies")
    void enumsAndRecords() {
        List<SourceFile> files = run("""
                enum Status { OPEN, CLOSED }

                record Point(int x, int y) {
                    int sum() { return x + y; }
                }
                """);
        assertEquals(List.of("Status.java", "Point.java"), names(files));
    }

    @Test
    @DisplayName("keeps Albanian comments and diacritics")
    void albanianComments() {
        List<SourceFile> files = run("""
                ```java
                public class Shuma {
                    // Kjo metodë kthen shumën e dy numrave
                    public int shuma(int a, int b) { return a + b; }
                }
                ```
                """);
        assertEquals(1, files.size());
        assertTrue(files.get(0).content().contains("kthen shumën"));
    }

    @Test
    @DisplayName("ignores non-Java fenced blocks")
    void ignoresShellBlocks() {
        List<SourceFile> files = run("""
                Compile it like this:

                ```bash
                javac Main.java && java Main
                ```

                And here is the code:

                ```java
                public class Main { public static void main(String[] a) {} }
                ```
                """);
        assertEquals(1, files.size());
        assertFalse(files.get(0).content().contains("javac Main.java"));
    }

    @Test
    @DisplayName("text blocks containing braces stay in one file")
    void textBlocks() {
        String code = "public class Tpl {\n"
                + "    String json = \"\"\"\n"
                + "        { \"a\": 1 }\n"
                + "        \"\"\";\n"
                + "}\n";
        assertEquals(1, run(code).size());
    }

    @Test
    @DisplayName("duplicate type names get distinct filenames")
    void duplicateNames() {
        List<SourceFile> files = run("class Dup { int a; }\nclass Dup { int b; }\n");
        assertEquals(2, files.size());
        assertNotEquals(files.get(0).fileName(), files.get(1).fileName());
    }

    @Test
    @DisplayName("empty and prose-only replies do not throw")
    void degenerate() {
        assertTrue(run("").isEmpty());
        assertEquals(1, run("I cannot help with that.").size());
    }
}

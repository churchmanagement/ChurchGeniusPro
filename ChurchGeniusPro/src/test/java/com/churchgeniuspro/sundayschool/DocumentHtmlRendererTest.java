package com.churchgeniuspro.sundayschool;

import com.churchgeniuspro.service.DocumentHtmlRenderer;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clicking View on a Word document used to download it: browsers have no renderer
 * for {@code .doc}/{@code .docx}, so {@code Content-Disposition: inline} saves the
 * file rather than showing it. The fix converts Word to HTML on the server, and
 * these tests pin what that conversion has to get right — including the escaping,
 * since the text comes from a file a teacher uploaded and the page is served from
 * the application's own origin to children.
 */
class DocumentHtmlRendererTest {

    private final DocumentHtmlRenderer renderer = new DocumentHtmlRenderer();

    /** Builds a real .docx in memory, so this exercises POI rather than a stub. */
    private static byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                XWPFRun run = doc.createParagraph().createRun();
                run.setText(text);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("A .docx becomes a readable HTML page")
    void docxRendersAsHtml() throws Exception {
        String html = renderer.toHtml(docx("The Good Samaritan", "Read Luke 10 before Sunday."), "lesson.docx");

        assertThat(html).isNotNull();
        assertThat(html).startsWith("<!doctype html>");
        assertThat(html).contains("The Good Samaritan");
        assertThat(html).contains("Read Luke 10 before Sunday.");
        assertThat(html).contains("lesson.docx");          // shown in the header bar
    }

    @Test
    @DisplayName("Bold, italic and underline survive the conversion")
    void formattingSurvives() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun bold = doc.createParagraph().createRun();
            bold.setText("Memory verse"); bold.setBold(true);
            XWPFRun italic = doc.createParagraph().createRun();
            italic.setText("quietly"); italic.setItalic(true);
            XWPFRun under = doc.createParagraph().createRun();
            under.setText("important"); under.setUnderline(UnderlinePatterns.SINGLE);
            doc.write(out); bytes = out.toByteArray();
        }
        String html = renderer.toHtml(bytes, "notes.docx");
        assertThat(html).contains("<strong>Memory verse</strong>");
        assertThat(html).contains("<em>quietly</em>");
        assertThat(html).contains("<u>important</u>");
    }

    @Test
    @DisplayName("Tables are rendered as tables, not run together")
    void tablesRender() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTable t = doc.createTable(1, 2);
            t.getRow(0).getCell(0).setText("Week");
            t.getRow(0).getCell(1).setText("Passage");
            doc.write(out); bytes = out.toByteArray();
        }
        String html = renderer.toHtml(bytes, "plan.docx");
        assertThat(html).contains("<table>");
        assertThat(html).contains("Week");
        assertThat(html).contains("Passage");
    }

    @Test
    @DisplayName("Markup inside the document is escaped, not executed")
    void documentTextIsEscaped() throws Exception {
        String nasty = "<script>alert('x')</script> & <img src=x onerror=1>";
        String html = renderer.toHtml(docx(nasty), "sneaky.docx");

        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
        // and the page forbids scripts outright
        assertThat(html).contains("default-src 'none'");
    }

    @Test
    @DisplayName("A filename containing markup cannot break out of the header")
    void fileNameIsEscaped() throws Exception {
        String html = renderer.toHtml(docx("hello"), "<script>x</script>.docx");
        assertThat(html).doesNotContain("<script>x</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("Something that is not a Word file returns null so the caller can fall back")
    void nonWordBytesReturnNull() {
        assertThat(renderer.toHtml("not a document".getBytes(StandardCharsets.UTF_8), "broken.docx")).isNull();
        assertThat(renderer.toHtml(new byte[0], "empty.docx")).isNull();
        assertThat(renderer.toHtml(null, "nothing.docx")).isNull();
    }

    @Test
    @DisplayName("Only Word extensions are claimed — PDF, text and images keep their own handling")
    void onlyWordIsClaimed() {
        assertThat(DocumentHtmlRenderer.isWord("a.doc")).isTrue();
        assertThat(DocumentHtmlRenderer.isWord("a.docx")).isTrue();
        assertThat(DocumentHtmlRenderer.isWord("A.DOCX")).isTrue();
        for (String other : new String[]{"a.pdf","a.txt","a.png","a.jpg","a.pptx","a.xlsx","a.rtf","a.odt","a",null}) {
            assertThat(DocumentHtmlRenderer.isWord(other)).as("%s", other).isFalse();
        }
    }

    @Test
    @DisplayName("The page prints without the header chrome")
    void printStylesArePresent() throws Exception {
        String html = renderer.toHtml(docx("hello"), "x.docx");
        assertThat(html).contains("@media print");
        assertThat(html).contains(".doc-head { display:none; }");
    }
}

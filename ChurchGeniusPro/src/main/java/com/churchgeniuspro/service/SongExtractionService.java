package com.churchgeniuspro.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts song titles/headings and lyrics from uploaded .txt / .doc / .docx /
 * .pdf files for the Song Book feature.
 *
 * <ul>
 *   <li>{@link #extractTitles} — returns a clean, de-duplicated list of song
 *       titles (one per non-empty line / heading, leading numbering stripped).</li>
 *   <li>{@link #extractLyricsHtml} — returns sanitized HTML preserving line and
 *       stanza breaks (and bold/italic for Word documents).</li>
 *   <li>{@link #sanitizeHtml} — sanitizes user-edited lyric HTML before storage.</li>
 * </ul>
 */
@Service
public class SongExtractionService {

    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*(?:\\d{1,3}[.)\\-:]|[•\\-*–—])\\s+");

    /* ─────────────────────────── TITLES ─────────────────────────── */

    public List<String> extractTitles(byte[] bytes, String filename) throws Exception {
        String ext = ext(filename);
        String text;
        if ("docx".equals(ext))      text = readDocx(bytes);
        else if ("doc".equals(ext))  text = readDoc(bytes);
        else if ("pdf".equals(ext))  text = readPdf(bytes);
        else                          text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

        List<String> titles = new ArrayList<>();
        String prev = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = LEADING_NUM.matcher(raw).replaceFirst("").trim();
            // collapse internal whitespace
            line = line.replaceAll("\\s{2,}", " ").trim();
            if (line.isEmpty()) { prev = null; continue; }
            if (line.length() > 200) line = line.substring(0, 200).trim();
            // skip obvious non-titles (page numbers, separators)
            if (line.matches("[\\-_=~.•*\\s]{3,}")) continue;
            if (line.matches("(?i)page\\s*\\d+")) continue;
            if (line.equalsIgnoreCase(prev)) continue;   // dedupe consecutive
            titles.add(line);
            prev = line;
        }
        return titles;
    }

    /* ─────────────────────────── LYRICS ─────────────────────────── */

    public String extractLyricsHtml(byte[] bytes, String filename) throws Exception {
        String ext = ext(filename);
        if ("docx".equals(ext)) return docxToHtml(bytes);
        String text;
        if ("doc".equals(ext))      text = readDoc(bytes);
        else if ("pdf".equals(ext)) text = readPdf(bytes);
        else                         text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return textToHtml(text);
    }

    /** Plain text → HTML: blank lines split stanzas (<p>), single newlines = <br>. */
    public String textToHtml(String text) {
        String[] lines = text.replace("\r", "").split("\n", -1);
        StringBuilder html = new StringBuilder();
        StringBuilder stanza = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                flushStanza(html, stanza);
            } else {
                if (stanza.length() > 0) stanza.append("<br/>");
                stanza.append(esc(line.replaceAll("\\s+$", "")));
            }
        }
        flushStanza(html, stanza);
        return html.length() == 0 ? "<p></p>" : html.toString();
    }

    private void flushStanza(StringBuilder html, StringBuilder stanza) {
        if (stanza.length() > 0) { html.append("<p>").append(stanza).append("</p>"); stanza.setLength(0); }
    }

    /** Word .docx → HTML preserving line breaks and bold/italic per run. */
    private String docxToHtml(byte[] bytes) throws Exception {
        StringBuilder html = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder stanza = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String plain = p.getText();
                if (plain == null || plain.trim().isEmpty()) { flushStanza(html, stanza); continue; }
                StringBuilder lineHtml = new StringBuilder();
                List<XWPFRun> runs = p.getRuns();
                if (runs == null || runs.isEmpty()) {
                    lineHtml.append(esc(plain));
                } else {
                    for (XWPFRun r : runs) {
                        String t = r.text();
                        if (t == null || t.isEmpty()) continue;
                        String e = esc(t);
                        if (r.isBold())   e = "<strong>" + e + "</strong>";
                        if (r.isItalic()) e = "<em>" + e + "</em>";
                        lineHtml.append(e);
                    }
                }
                if (stanza.length() > 0) stanza.append("<br/>");
                stanza.append(lineHtml);
            }
            flushStanza(html, stanza);
        }
        return html.length() == 0 ? "<p></p>" : html.toString();
    }

    /* ─────────────────────────── READERS ─────────────────────────── */

    private String readPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setLineSeparator("\n");
            s.setParagraphEnd("\n");
            return s.getText(doc);
        }
    }

    private String readDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
        }
        return sb.toString();
    }

    private String readDoc(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor we = new WordExtractor(doc)) {
            return String.join("\n", we.getParagraphText());
        }
    }

    /* ─────────────────────────── SANITIZER ─────────────────────────── */

    private static final Pattern TAG = Pattern.compile("</?([a-zA-Z0-9]+)([^>]*)>");
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
            "p", "br", "div", "span", "b", "i", "u", "strong", "em",
            "h1", "h2", "h3", "h4", "ul", "ol", "li", "blockquote");

    /**
     * Sanitize user-edited lyric HTML: drop &lt;script&gt;/&lt;style&gt;, any
     * disallowed tag, and all attributes (which removes on*-handlers and
     * javascript: URLs). Keeps a safe subset of formatting tags.
     */
    public String sanitizeHtml(String html) {
        if (html == null) return null;
        // remove script/style blocks entirely
        String out = html.replaceAll("(?is)<\\s*(script|style)[^>]*>.*?<\\s*/\\s*\\1\\s*>", "");
        // strip comments
        out = out.replaceAll("(?s)<!--.*?-->", "");
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = TAG.matcher(out);
        int last = 0;
        while (m.find()) {
            sb.append(out, last, m.start());
            String tag = m.group(1).toLowerCase();
            boolean closing = m.group(0).startsWith("</");
            if (ALLOWED.contains(tag)) {
                sb.append(closing ? "</" + tag + ">" : "<" + tag + ">");   // drop all attributes
            }
            last = m.end();
        }
        sb.append(out.substring(last));
        return sb.toString();
    }

    /* ─────────────────────────── helpers ─────────────────────────── */

    private static String ext(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i + 1).toLowerCase() : "";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

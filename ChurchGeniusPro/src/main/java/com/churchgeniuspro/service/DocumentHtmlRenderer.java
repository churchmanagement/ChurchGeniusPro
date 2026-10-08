package com.churchgeniuspro.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;

/**
 * Renders a Word document as a self-contained HTML page so it can be read in the
 * browser instead of landing in the downloads folder.
 *
 * <p>Browsers render PDF, text and images natively; Word is the gap. Serving a
 * {@code .doc} with {@code Content-Disposition: inline} does not help — the
 * browser has no renderer for it and saves the file anyway, which is exactly the
 * behaviour being complained about. The only way to actually show it is to turn
 * it into something the browser does understand, and Apache POI (already on the
 * classpath for the worship-planning importer) can do that on the server, so no
 * document is handed to a third-party viewer service on the way.
 *
 * <p>Both Word formats are covered: {@code .docx} through {@link XWPFDocument},
 * and legacy {@code .doc} through {@link HWPFDocument}.
 *
 * <p><b>Everything is escaped.</b> The text comes from a file a teacher uploaded,
 * and the result is served from the application's own origin, so unescaped markup
 * would be stored cross-site scripting aimed at children. Text is escaped at every
 * point it enters the output; only the tags this class writes itself are literal.
 */
@Service
public class DocumentHtmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(DocumentHtmlRenderer.class);

    /** Word formats this renderer can turn into HTML. */
    public static boolean isWord(String fileName) {
        String ext = extensionOf(fileName);
        return "doc".equals(ext) || "docx".equals(ext);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @return a complete HTML page, or {@code null} when the bytes could not be
     *         read as a Word document — the caller then falls back to serving the
     *         original file rather than showing an error.
     */
    public String toHtml(byte[] data, String fileName) {
        if (data == null || data.length == 0) return null;
        try {
            String body = "docx".equals(extensionOf(fileName)) ? docxBody(data) : docBody(data);
            if (body == null || body.isBlank()) return null;
            return page(fileName, body);
        } catch (Exception e) {
            log.warn("Could not render {} as HTML — falling back to the original file: {}",
                     fileName, e.toString());
            return null;
        }
    }

    // ── .docx ───────────────────────────────────────────────────────────────

    private String docxBody(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) out.append(paragraph(p));
                else if (el instanceof XWPFTable t) out.append(table(t));
            }
        }
        return out.toString();
    }

    private String paragraph(XWPFParagraph p) {
        StringBuilder inner = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            String text = run.text();
            if (text == null || text.isEmpty()) continue;
            String piece = esc(text).replace("\n", "<br>");
            if (run.isBold())       piece = "<strong>" + piece + "</strong>";
            if (run.isItalic())     piece = "<em>" + piece + "</em>";
            if (run.getUnderline() != UnderlinePatterns.NONE) piece = "<u>" + piece + "</u>";
            inner.append(piece);
        }
        if (inner.length() == 0) return "<p class=\"blank\">&nbsp;</p>";

        String align = switch (p.getAlignment()) {
            case CENTER -> "center";
            case RIGHT  -> "right";
            case BOTH   -> "justify";
            default     -> "left";
        };
        String style = p.getStyle() == null ? "" : p.getStyle().toLowerCase(Locale.ROOT);
        String tag = style.startsWith("heading1") || style.equals("title") ? "h1"
                   : style.startsWith("heading2") ? "h2"
                   : style.startsWith("heading3") ? "h3"
                   : "p";
        return "<" + tag + " style=\"text-align:" + align + "\">" + inner + "</" + tag + ">";
    }

    private String table(XWPFTable t) {
        StringBuilder out = new StringBuilder("<table>");
        for (XWPFTableRow row : t.getRows()) {
            out.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder cellHtml = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) cellHtml.append(paragraph(p));
                out.append("<td>").append(cellHtml).append("</td>");
            }
            out.append("</tr>");
        }
        return out.append("</table>").toString();
    }

    // ── legacy .doc ─────────────────────────────────────────────────────────

    private String docBody(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data))) {
            Range range = doc.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) {
                String text = range.getParagraph(i).text();
                if (text == null) continue;
                // HWPF paragraphs carry their terminator and Word's cell/row marks.
                // \u0007 is Word's cell/row mark, \u000B a line break inside a paragraph.
                text = text.replace('\r', '\n').replace('\u0007', ' ').replace('\u000B', '\n').trim();
                if (text.isEmpty()) { out.append("<p class=\"blank\">&nbsp;</p>"); continue; }
                out.append("<p>").append(esc(text).replace("\n", "<br>")).append("</p>");
            }
        }
        return out.toString();
    }

    // ── page shell ──────────────────────────────────────────────────────────

    /**
     * Wraps the converted body in a readable page. The Content-Security-Policy is
     * deliberately strict: this page shows the contents of an uploaded file, so
     * nothing in it should ever be able to run script or reach the network.
     */
    private String page(String fileName, String body) {
        String title = esc(fileName == null ? "Document" : fileName);
        return """
               <!doctype html>
               <html lang="en">
               <head>
                 <meta charset="utf-8">
                 <meta name="viewport" content="width=device-width, initial-scale=1">
                 <meta http-equiv="Content-Security-Policy"
                       content="default-src 'none'; style-src 'unsafe-inline'; img-src data:;">
                 <title>__TITLE__</title>
                 <style>
                   body { margin:0; background:#f4f4f7; color:#222;
                          font:15px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
                   .doc-head { background:#673147; color:#fff; padding:12px 20px; font-size:14px; font-weight:600; }
                   .sheet { max-width:820px; margin:22px auto 60px; background:#fff; padding:44px 52px;
                            box-shadow:0 2px 14px rgba(0,0,0,.12); border-radius:4px; }
                   .sheet p { margin:0 0 10px; }
                   .sheet p.blank { margin:0 0 6px; }
                   .sheet h1 { font-size:24px; margin:0 0 14px; color:#4a2333; }
                   .sheet h2 { font-size:19px; margin:18px 0 10px; color:#4a2333; }
                   .sheet h3 { font-size:16px; margin:16px 0 8px; color:#4a2333; }
                   .sheet table { border-collapse:collapse; margin:12px 0; width:100%; }
                   .sheet td { border:1px solid #d9d2dc; padding:7px 10px; vertical-align:top; }
                   @media print {
                     body { background:#fff; }
                     .doc-head { display:none; }
                     .sheet { box-shadow:none; margin:0; padding:0; max-width:none; }
                   }
                   @media (max-width:640px) { .sheet { padding:24px 20px; margin:12px; } }
                 </style>
               </head>
               <body>
                 <div class="doc-head">__TITLE__</div>
                 <div class="sheet">__BODY__</div>
               </body>
               </html>
               """.replace("__TITLE__", title).replace("__BODY__", body);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

package com.churchgeniuspro.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces author-supplied HTML to a small, safe subset before it is stored.
 *
 * <p>The giving-statement letter text is written by a church's own staff and then
 * rendered, as HTML, into <em>every member's</em> Financial Report. That is a
 * stored-XSS path: one compromised or careless staff login would otherwise reach
 * every congregant's browser. So nothing reaches the database until it has been
 * through here.
 *
 * <p>The approach is allow-list <b>re-emission</b>, not blocklist stripping: the
 * input is tokenised and a new document is written out containing only tags this
 * class knows, only attributes it knows, and text that has been escaped. Anything
 * unrecognised cannot survive by being spelled unusually, because it is never
 * copied through — it is either dropped or escaped into text. Unbalanced and
 * badly nested tags are closed for you, so the surrounding page cannot be
 * restructured by an unclosed element.
 *
 * <p>What survives is what the toolbar can produce: bold, italic, underline,
 * strikethrough, paragraphs, line breaks, lists, alignment and colour — plus the
 * three letter-template classes, so the printed design is preserved.
 */
public final class RichTextSanitizer {

    private RichTextSanitizer() {}

    /** Generous next to a few paragraphs of letter text; a stop on absurd input. */
    public static final int MAX_LENGTH = 20_000;

    /** Tags that are re-emitted. Everything else is dropped, keeping its text. */
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "b", "strong", "i", "em", "u", "s", "strike", "del", "ins",
            "ul", "ol", "li", "div", "span", "h3", "h4", "blockquote", "sub", "sup");

    /** Allowed tags that never have content and are never pushed on the stack. */
    private static final Set<String> VOID_TAGS = Set.of("br");

    /**
     * Elements whose CONTENT is discarded too, not just their tags. For these the
     * text between the tags is markup rather than prose, so escaping and keeping it
     * would show the reader a page of code — and for {@code script} and friends,
     * keeping it at all is the thing being prevented.
     */
    private static final Set<String> DROP_WITH_CONTENT = Set.of(
            "script", "style", "iframe", "object", "embed", "applet", "noscript",
            "template", "svg", "math", "head", "title", "form", "select", "textarea");

    /** The letter template's own classes — the reason the printed design survives an edit. */
    private static final Set<String> ALLOWED_CLASSES = Set.of(
            "ltr-body", "ltr-closing", "ltr-disclaimer");

    /** Style properties the toolbar produces. Deliberately excludes sizing and backgrounds. */
    private static final Set<String> ALLOWED_STYLE_PROPS = Set.of(
            "text-align", "font-weight", "font-style", "text-decoration",
            "text-decoration-line", "color");

    /** Conservative value grammar: enough for {@code #aa33ff}, {@code rgb(1, 2, 3)}, keywords. */
    private static final Pattern STYLE_VALUE = Pattern.compile("^[#A-Za-z0-9 ,.%()-]{1,60}$");

    private static final Set<String> ALIGN_VALUES = Set.of("left", "right", "center", "justify");

    /** A character entity that may be copied through untouched. */
    private static final Pattern ENTITY = Pattern.compile(
            "\\G&(#\\d{1,6}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,9});");

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /**
     * The sanitised form of {@code raw}: safe to store, and safe to write into a
     * page without further escaping.
     *
     * @return "" for null or blank input — never null
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";

        StringBuilder out  = new StringBuilder(raw.length());
        Deque<String> open = new ArrayDeque<>();
        int i = 0;
        final int n = raw.length();

        while (i < n) {
            char c = raw.charAt(i);

            if (c == '&') {                                   // keep real entities, escape stray &
                Matcher m = ENTITY.matcher(raw);
                m.region(i, n);
                if (m.find()) { out.append(m.group()); i = m.end(); }
                else          { out.append("&amp;");   i++; }
                continue;
            }
            if (c != '<') {
                if (c == '>') out.append("&gt;"); else out.append(c);
                i++;
                continue;
            }

            if (raw.startsWith("<!--", i)) {                   // comment
                int e = raw.indexOf("-->", i);
                i = (e < 0) ? n : e + 3;
                continue;
            }
            if (raw.startsWith("<!", i) || raw.startsWith("<?", i)) {   // doctype / PI
                int e = raw.indexOf('>', i);
                i = (e < 0) ? n : e + 1;
                continue;
            }

            boolean closing   = raw.startsWith("</", i);
            int     nameStart = i + (closing ? 2 : 1);
            if (nameStart >= n || !Character.isLetter(raw.charAt(nameStart))) {
                out.append("&lt;");                           // a stray '<' is text
                i++;
                continue;
            }

            int p = nameStart;
            while (p < n && Character.isLetterOrDigit(raw.charAt(p))) p++;
            String name = raw.substring(nameStart, p).toLowerCase(Locale.ROOT);

            int tagEnd = findTagEnd(raw, p);                  // index of '>' outside quotes
            if (tagEnd >= n) {                               // never closed: it is prose, not a tag
                out.append("&lt;");
                i++;
                continue;
            }
            String attrs = raw.substring(p, tagEnd);
            int    next  = tagEnd + 1;

            if (DROP_WITH_CONTENT.contains(name)) {
                i = closing ? next : skipElement(raw, next, name);
                continue;
            }
            if (!ALLOWED_TAGS.contains(name)) {               // unknown tag: drop it, keep its text
                i = next;
                continue;
            }

            if (closing) {
                if (open.contains(name)) {                    // also closes anything left open inside
                    String t;
                    do { t = open.pop(); out.append("</").append(t).append('>'); } while (!t.equals(name));
                }
                i = next;
                continue;
            }

            out.append('<').append(name).append(attributes(name, attrs));
            if (VOID_TAGS.contains(name) || attrs.stripTrailing().endsWith("/")) {
                out.append("/>");
            } else {
                out.append('>');
                open.push(name);
            }
            i = next;
        }

        while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
        return out.toString().trim();
    }

    /** True when the value carries no visible text — used to fall back to the default. */
    public static boolean isEmpty(String html) {
        if (html == null) return true;
        String text = html.replaceAll("(?s)<[^>]*>", "")
                          .replace("&nbsp;", " ")
                          .replace('\u00A0', ' ');
        return text.isBlank();
    }

    /* ── internals ──────────────────────────────────────────────────────── */

    /** The '>' that ends this tag, skipping any inside quoted attribute values. */
    private static int findTagEnd(String s, int from) {
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '"' || c == '\'') quote = c;
            else if (c == '>') return i;
        }
        return s.length();
    }

    /** Index just past this element's closing tag, or the end of input if it has none. */
    private static int skipElement(String s, int from, String name) {
        String needle = "</" + name;
        int at = indexOfIgnoreCase(s, needle, from);
        if (at < 0) return s.length();
        int end = findTagEnd(s, at + needle.length());
        return (end < s.length()) ? end + 1 : s.length();
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    /** The attribute string to re-emit: class, style and align only, each validated. */
    private static String attributes(String tag, String attrs) {
        if (attrs == null || attrs.isBlank()) return "";
        StringBuilder kept = new StringBuilder();
        Matcher m = ATTRIBUTE.matcher(attrs);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String val = m.group(3) != null ? m.group(3)
                       : m.group(4) != null ? m.group(4)
                       : m.group(5) != null ? m.group(5) : "";
            switch (key) {
                case "class" -> {
                    String classes = allowedClasses(val);
                    if (!classes.isEmpty()) kept.append(" class=\"").append(classes).append('"');
                }
                case "style" -> {
                    String style = allowedStyle(val);
                    if (!style.isEmpty()) kept.append(" style=\"").append(style).append('"');
                }
                case "align" -> {
                    String v = val.trim().toLowerCase(Locale.ROOT);
                    if (ALIGN_VALUES.contains(v) && !"br".equals(tag)) {
                        kept.append(" style=\"text-align:").append(v).append('"');
                    }
                }
                default -> { /* every other attribute, event handlers included, is dropped */ }
            }
        }
        return kept.toString();
    }

    private static String allowedClasses(String value) {
        StringBuilder b = new StringBuilder();
        for (String token : value.trim().split("\\s+")) {
            String t = token.toLowerCase(Locale.ROOT);
            if (ALLOWED_CLASSES.contains(t)) {
                if (b.length() > 0) b.append(' ');
                b.append(t);
            }
        }
        return b.toString();
    }

    private static String allowedStyle(String value) {
        StringBuilder b = new StringBuilder();
        for (String decl : value.split(";")) {
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val  = decl.substring(colon + 1).trim();
            if (!ALLOWED_STYLE_PROPS.contains(prop))    continue;
            if (!STYLE_VALUE.matcher(val).matches())    continue;
            String lower = val.toLowerCase(Locale.ROOT);
            // Belt and braces: the grammar above already excludes the characters
            // these need, but they are the two that must never get through.
            if (lower.contains("url(") || lower.contains("expression")) continue;
            if (b.length() > 0) b.append(';');
            b.append(prop).append(':').append(val);
        }
        return b.toString();
    }
}

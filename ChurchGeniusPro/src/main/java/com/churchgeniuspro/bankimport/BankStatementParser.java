package com.churchgeniuspro.bankimport;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a bank / credit-card statement export into a list of {@link BankTxn}
 * (date + description + signed amount). Supports three formats with no external
 * dependencies:
 *
 * <ul>
 *   <li><b>CSV</b> — flexible column detection (date / description / amount, or
 *       separate debit &amp; credit columns); handles quoted fields.</li>
 *   <li><b>OFX</b> — Open Financial Exchange (SGML, tags often unclosed).</li>
 *   <li><b>QFX</b> — Quicken's OFX variant.</li>
 * </ul>
 */
public final class BankStatementParser {

    private BankStatementParser() {}

    public static List<BankTxn> parse(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        String lower = filename == null ? "" : filename.toLowerCase();
        boolean ofx = lower.endsWith(".ofx") || lower.endsWith(".qfx")
                || containsIgnoreCase(text, "<STMTTRN>")
                || containsIgnoreCase(text, "OFXHEADER")
                || containsIgnoreCase(text, "<OFX>");
        return ofx ? parseOfx(text) : parseCsv(text);
    }

    // ───────────────────────── OFX / QFX ─────────────────────────

    private static List<BankTxn> parseOfx(String text) {
        List<BankTxn> out = new ArrayList<>();
        // Split on each <STMTTRN> opener — robust whether or not the closing
        // </STMTTRN> tag is present (classic OFX SGML omits closers).
        String[] parts = text.split("(?i)<STMTTRN>");
        for (int i = 1; i < parts.length; i++) {
            String block = parts[i];
            int end = indexOfIgnoreCase(block, "</STMTTRN>");
            if (end >= 0) block = block.substring(0, end);
            BankTxn t = new BankTxn();
            t.date = normalizeDate(tag(block, "DTPOSTED"));
            t.description = join(tag(block, "NAME"), tag(block, "MEMO"));
            t.amount = parseAmount(tag(block, "TRNAMT"));
            if (t.amount != null) out.add(t);
        }
        return out;
    }

    /** Reads an SGML/OFX tag value: everything after {@code <NAME>} up to the next tag or EOL. */
    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    // ───────────────────────── CSV ─────────────────────────

    private static List<BankTxn> parseCsv(String text) {
        List<BankTxn> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        int h = 0;
        while (h < lines.length && lines[h].trim().isEmpty()) h++;
        if (h >= lines.length) return out;

        List<String> header = splitCsv(lines[h]);
        int idxDate = -1, idxDesc = -1, idxAmt = -1, idxDebit = -1, idxCredit = -1;
        for (int i = 0; i < header.size(); i++) {
            String col = header.get(i).toLowerCase().trim();
            if (idxDate < 0 && col.contains("date")) idxDate = i;
            if (idxDesc < 0 && (col.contains("desc") || col.contains("memo") || col.contains("payee")
                    || col.contains("name") || col.contains("narration") || col.contains("details")
                    || col.contains("transaction") || col.contains("reference"))) idxDesc = i;
            if (idxAmt < 0 && col.contains("amount")) idxAmt = i;
            if (idxDebit < 0 && (col.contains("debit") || col.contains("withdraw"))) idxDebit = i;
            if (idxCredit < 0 && (col.contains("credit") || col.contains("deposit"))) idxCredit = i;
        }
        boolean hasHeader = idxDate >= 0 || idxAmt >= 0 || idxDesc >= 0 || idxDebit >= 0 || idxCredit >= 0;
        int start = hasHeader ? h + 1 : h;
        if (!hasHeader) { idxDate = 0; idxDesc = 1; idxAmt = 2; }   // assume Date, Description, Amount

        for (int r = start; r < lines.length; r++) {
            if (lines[r].trim().isEmpty()) continue;
            List<String> c = splitCsv(lines[r]);
            BankTxn t = new BankTxn();
            t.date = normalizeDate(get(c, idxDate));
            t.description = clean(get(c, idxDesc));
            BigDecimal amt = idxAmt >= 0 ? parseAmount(get(c, idxAmt)) : null;
            if (amt == null && (idxDebit >= 0 || idxCredit >= 0)) {
                BigDecimal debit = parseAmount(get(c, idxDebit));
                BigDecimal credit = parseAmount(get(c, idxCredit));
                BigDecimal v = BigDecimal.ZERO;
                boolean seen = false;
                if (credit != null) { v = v.add(credit.abs()); seen = true; }
                if (debit != null) { v = v.subtract(debit.abs()); seen = true; }
                amt = seen ? v : null;
            }
            t.amount = amt;
            if (t.amount != null && t.description != null && !t.description.isBlank()) out.add(t);
        }
        return out;
    }

    /** Split a CSV line, honouring double-quoted fields and "" escapes. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else q = !q;
            } else if (ch == ',' && !q) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ───────────────────────── shared helpers ─────────────────────────

    /** Normalize OFX yyyymmdd, ISO yyyy-mm-dd, and US m/d/yyyy(or yy) to ISO yyyy-MM-dd. */
    static String normalizeDate(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        Matcher ofx = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})").matcher(s);
        if (ofx.find()) return ofx.group(1) + "-" + ofx.group(2) + "-" + ofx.group(3);
        Matcher iso = Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(s);
        if (iso.find()) return iso.group(1) + "-" + pad(iso.group(2)) + "-" + pad(iso.group(3));
        Matcher us = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})").matcher(s);
        if (us.find()) {
            String yy = us.group(3);
            if (yy.length() == 2) yy = "20" + yy;
            return yy + "-" + pad(us.group(1)) + "-" + pad(us.group(2));
        }
        return s; // leave as-is if unrecognized
    }

    /** Parse a money string: $, commas, +, parentheses/(trailing -)/DR-CR all handled. */
    static BigDecimal parseAmount(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        boolean neg = false;
        String low = s.toLowerCase();
        if (low.endsWith("dr")) { neg = true; s = s.substring(0, s.length() - 2).trim(); }
        else if (low.endsWith("cr")) { s = s.substring(0, s.length() - 2).trim(); }
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length() - 1); }
        s = s.replace("$", "").replace(",", "").replace(" ", "").replace("+", "");
        if (s.endsWith("-")) { neg = true; s = s.substring(0, s.length() - 1); }
        if (s.startsWith("-")) { neg = true; s = s.substring(1); }
        s = s.replaceAll("[^0-9.]", "");
        if (s.isEmpty() || ".".equals(s)) return null;
        try {
            BigDecimal v = new BigDecimal(s);
            return neg ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pad(String n) { return n.length() == 1 ? "0" + n : n; }

    private static String get(List<String> list, int idx) {
        return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
    }

    private static String clean(String s) { return s == null ? null : s.trim(); }

    private static String join(String a, String b) {
        a = clean(a); b = clean(b);
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        if (a.equalsIgnoreCase(b)) return a;
        return a + " — " + b;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return indexOfIgnoreCase(hay, needle) >= 0;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay == null ? -1 : hay.toLowerCase().indexOf(needle.toLowerCase());
    }
}

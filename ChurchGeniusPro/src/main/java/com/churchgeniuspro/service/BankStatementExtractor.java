package com.churchgeniuspro.service;

import com.churchgeniuspro.bankimport.BankTxn;
import com.churchgeniuspro.bankimport.TransactionCategorizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a list of transactions from a photographed / scanned bank statement
 * (image or PDF), so the Bank Import review grid can display them like a parsed
 * CSV/OFX file.
 *
 * <p>Pluggable OCR point — same pattern as {@link CheckExtractor}:
 * <ul>
 *   <li><b>PDF</b> statements with a text layer are parsed here (PDFBox), across
 *       all pages (multi-page supported).</li>
 *   <li><b>Images</b> (JPG/PNG/HEIC, screenshots, photos) are flagged
 *       {@code image-needs-ocr}; the browser runs Tesseract.js OCR and POSTs the
 *       text to {@link #extractFromText} for the same line parsing.</li>
 * </ul>
 * Each parsed transaction is run through {@link TransactionCategorizer} so it
 * carries the same category / fund / confidence shape the grid already uses.
 */
@Service
public class BankStatementExtractor {

    public static class Result {
        public List<Map<String, Object>> rows = new ArrayList<>();
        public Map<String, Object> summary = new LinkedHashMap<>();
        public boolean readable = false;
        public String method = "none";   // pdf-text | ocr-text | image-needs-ocr | none
        public String note = "";
        /** Raw extracted PDF text — for an AI fallback when the line parser finds nothing. */
        public String rawText = "";
    }

    public Result extract(byte[] bytes, String filename, String contentType) {
        Result r = new Result();
        String lc = filename == null ? "" : filename.toLowerCase();
        boolean isPdf = (contentType != null && contentType.contains("pdf")) || lc.endsWith(".pdf");

        if (isPdf) {
            String text = "";
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                text = new PDFTextStripper().getText(doc);   // all pages
            } catch (Exception e) {
                r.method = "none"; r.note = "Could not read the PDF: " + e.getMessage(); return r;
            }
            if (text != null && text.replaceAll("\\s", "").length() >= 12) {
                r.rawText = text;
                build(parseText(text, 100), filename, r);
                r.method = "pdf-text";
                if (!r.readable) r.note = "No transactions could be read from this PDF. Please review or enter manually.";
                return r;
            }
            r.method = "image-needs-ocr"; r.note = "This looks like a scanned image. Reading it requires OCR.";
            return r;
        }

        boolean isImage = (contentType != null && contentType.startsWith("image"))
                || lc.matches(".*\\.(jpg|jpeg|png|heic|heif|webp|gif|bmp|tiff?)$");
        if (isImage) { r.method = "image-needs-ocr"; r.note = "Image received — running OCR."; return r; }

        r.method = "none"; r.note = "Unsupported file type. Upload a JPG, PNG, HEIC, or PDF.";
        return r;
    }

    /** Parse recognized text (browser OCR). {@code ocrConfidence} 0-100 scales row confidence. */
    public Result extractFromText(String text, int ocrConfidence) {
        Result r = new Result();
        int scale = ocrConfidence <= 0 ? 100 : Math.max(45, Math.min(100, ocrConfidence));
        build(parseText(text == null ? "" : text, scale), null, r);
        r.method = "ocr-text";
        if (!r.readable) r.note = "Couldn't read transactions from this image. Please retake the photo or enter values manually.";
        return r;
    }

    // ── build result rows (with categorization) ──

    private void build(List<Txn> txns, String filename, Result r) {
        int credits = 0, debits = 0;
        BigDecimal totalIn = BigDecimal.ZERO, totalOut = BigDecimal.ZERO;
        for (Txn t : txns) {
            BankTxn b = new BankTxn();
            b.date = t.date;
            b.description = t.description;
            b.amount = t.amount;
            try { TransactionCategorizer.categorize(b); } catch (Exception ignore) { }

            int catConf = b.confidence;
            int rowConf = clamp((int) Math.round((catConf + t.parseConf) / 2.0 * (t.scale / 100.0)));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", b.date);
            m.put("description", b.description);
            m.put("amount", b.amount);
            m.put("category", b.category);
            m.put("fund", b.fund == null ? "General" : b.fund);
            m.put("confidence", rowConf);
            // extra fields the editor can use (ignored by the grid)
            if (t.refNo != null)   m.put("refNo", t.refNo);
            if (t.checkNo != null) m.put("checkNo", t.checkNo);
            if (t.payee != null)   m.put("payee", t.payee);
            m.put("txnType", t.amount.signum() < 0 ? "debit" : "credit");
            r.rows.add(m);

            if (t.amount.signum() > 0) { credits++; totalIn = totalIn.add(t.amount); }
            else { debits++; totalOut = totalOut.add(t.amount.abs()); }
        }
        r.rows.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        r.readable = !r.rows.isEmpty();

        Map<String, Object> s = r.summary;
        s.put("count", r.rows.size());
        s.put("credits", credits);
        s.put("debits", debits);
        s.put("totalIn", totalIn);
        s.put("totalOut", totalOut);
        if (filename != null) s.put("fileName", filename);
    }

    /**
     * Build a Result from transactions extracted by the vision model.
     * Each map: date (MM/DD/YYYY), description, amount (signed number),
     * type (debit|credit), refNo, checkNo. Categorized via {@link TransactionCategorizer}.
     */
    public Result fromVisionTransactions(List<Map<String, Object>> txns) {
        List<Txn> list = new ArrayList<>();
        if (txns != null) for (Map<String, Object> m : txns) {
            String iso = visionIso(str(m.get("date")));
            if (iso == null) continue;
            Double amt = (m.get("amount") instanceof Number) ? ((Number) m.get("amount")).doubleValue() : parseDbl(str(m.get("amount")));
            if (amt == null || amt == 0) continue;
            Txn t = new Txn();
            t.date = iso;
            t.description = str(m.get("description"));
            if (t.description == null || t.description.isBlank()) t.description = "Transaction";
            java.math.BigDecimal a = java.math.BigDecimal.valueOf(amt).setScale(2, java.math.RoundingMode.HALF_UP);
            String type = str(m.get("type"));
            if ("debit".equalsIgnoreCase(type)) a = a.abs().negate();
            else if ("credit".equalsIgnoreCase(type)) a = a.abs();
            t.amount = a;
            t.refNo = str(m.get("refNo"));
            t.checkNo = str(m.get("checkNo"));
            t.payee = t.description;
            t.parseConf = 95; t.scale = 100;
            list.add(t);
        }
        Result r = new Result();
        build(list, null, r);
        r.method = "vision";
        if (!r.readable) r.note = "No transactions were read from the image.";
        return r;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o).trim(); }
    private static Double parseDbl(String s) {
        if (s == null) return null;
        String v = s.replaceAll("[^0-9.\\-]", "");
        try { return v.isEmpty() ? null : Double.parseDouble(v); } catch (NumberFormatException e) { return null; }
    }
    private static String visionIso(String s) {
        if (s == null) return null;
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (iso.find()) return pad(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        Matcher m = Pattern.compile("(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})").matcher(s);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1)), da = Integer.parseInt(m.group(2));
            String y = m.group(3); int yr = Integer.parseInt(y);
            if (y.length() == 2) yr = (yr > 70 ? 1900 : 2000) + yr;
            return pad(yr, mo, da);
        }
        return null;
    }
    private static String pad(int yr, int mo, int da) {
        if (mo < 1 || mo > 12 || da < 1 || da > 31) return null;
        return String.format("%04d-%02d-%02d", yr, mo, da);
    }

    // ── line parsing ──

    /** A parsed statement line. */
    private static class Txn {
        String date, description, payee, refNo, checkNo;
        BigDecimal amount;
        int parseConf;
        int scale;
    }

    private static final Pattern MONEY = Pattern.compile("\\(?-?\\$?\\s?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})\\)?-?(?:\\s?(?:CR|DR))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_NUM  = Pattern.compile("\\b(0?[1-9]|1[0-2])[/\\-](0?[1-9]|[12]\\d|3[01])(?:[/\\-](\\d{2,4}))?\\b");
    private static final Pattern DATE_WORD = Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECK = Pattern.compile("\\b(?:check|cheque|chk|ck)\\s*#?\\s*(\\d{3,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF   = Pattern.compile("\\b(?:ref(?:erence)?|conf(?:irmation)?|trace|txn|trans|id)\\s*#?:?\\s*([A-Za-z0-9]{4,})\\b", Pattern.CASE_INSENSITIVE);
    // A line that is just an amount (optional +/- sign, $, trailing CR/DR) — used by the
    // "stacked" statement layout where the amount sits on its own line above the date row.
    private static final Pattern AMOUNT_ONLY = Pattern.compile("^([+\\-])?\\s*\\$?\\s*([0-9]{1,3}(?:,[0-9]{3})*\\.[0-9]{2})\\s*(CR|DR)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_HEADER_TS = Pattern.compile("^\\d{1,2}/\\d{1,2}/\\d{2},\\s*\\d.*");

    /**
     * Parse statement text into transactions. Handles two layouts:
     *  (a) single line: date + description + amount on one line; and
     *  (b) stacked: the amount on its own line (a leading "+" = credit) directly above
     *      a "date description" line — e.g. the U.S. Bank "Recent Activity List" export.
     */
    private List<Txn> parseText(String text, int scale) {
        List<Txn> out = new ArrayList<>();
        Double pendingVal = null;     // amount seen on a preceding amount-only line
        boolean pendingPlus = false;  // a "+" / "CR" on that line means a credit (money in)
        for (String raw : text.replace("\r", "").split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            String low = s.toLowerCase();
            if (low.contains("recent activity") || low.contains("u.s. bank")) continue;   // page header
            if (PAGE_HEADER_TS.matcher(s).matches()) continue;                              // "5/31/26, 8:22 PM" header
            if (low.equals("posted") || low.equals("pending") || low.startsWith("checking ") || low.startsWith("savings ")) continue;

            // amount-only line → remember it for the following date row
            Matcher am = AMOUNT_ONLY.matcher(s);
            if (am.matches()) {
                pendingVal = Double.parseDouble(am.group(2).replace(",", ""));
                pendingPlus = "+".equals(am.group(1)) || "CR".equalsIgnoreCase(am.group(3));
                continue;
            }

            // single-line layout (date + amount on the same line)
            Txn inline = parseLine(s, scale);
            if (inline != null) { out.add(inline); pendingVal = null; continue; }

            // stacked layout: a date line whose amount was on the previous line
            Matcher dm = DATE_NUM.matcher(s);
            if (dm.find() && pendingVal != null) {
                int mo = Integer.parseInt(dm.group(1)), da = Integer.parseInt(dm.group(2));
                int yr = dm.group(3) != null ? normYear(dm.group(3)) : java.time.Year.now().getValue();
                String iso = isoOf(yr, mo, da);
                if (iso != null) {
                    String desc = (s.substring(0, dm.start()) + " " + s.substring(Math.min(dm.end(), s.length())))
                            .replaceAll("\\s+", " ").replaceAll("^[\\-–:|*]+", "").replaceAll("[\\-–:|*]+$", "").trim();
                    if (desc.isEmpty()) desc = "Transaction";
                    BigDecimal amt = BigDecimal.valueOf(pendingPlus ? pendingVal : -pendingVal).setScale(2, java.math.RoundingMode.HALF_UP);
                    Txn t = new Txn();
                    t.date = iso; t.description = desc; t.payee = desc; t.amount = amt;
                    t.parseConf = 88; t.scale = scale;
                    out.add(t);
                }
                pendingVal = null;
            }
        }
        return out;
    }

    private Txn parseLine(String line, int scale) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() < 6) return null;

        // 1) date (may appear anywhere on the line)
        String iso = null; int dateStart = -1, dateEnd = -1;
        Matcher dn = DATE_NUM.matcher(s);
        if (dn.find()) {
            int mo = Integer.parseInt(dn.group(1)), da = Integer.parseInt(dn.group(2));
            int yr = dn.group(3) != null ? normYear(dn.group(3)) : java.time.Year.now().getValue();
            iso = isoOf(yr, mo, da); dateStart = dn.start(); dateEnd = dn.end();
        } else {
            Matcher dw = DATE_WORD.matcher(s);
            if (dw.find()) {
                int mo = monthNum(dw.group(1)), da = Integer.parseInt(dw.group(2));
                int yr = dw.group(3) != null ? Integer.parseInt(dw.group(3)) : java.time.Year.now().getValue();
                iso = isoOf(yr, mo, da); dateStart = dw.start(); dateEnd = dw.end();
            }
        }
        if (iso == null) return null;   // a transaction row must carry a date

        // 2) money tokens
        List<int[]> spans = new ArrayList<>();
        List<String> toks = new ArrayList<>();
        Matcher mm = MONEY.matcher(s);
        while (mm.find()) {
            String g = mm.group().trim();
            if (g.replaceAll("[^0-9]", "").length() < 3) continue;   // need at least N.NN
            if (!g.contains(".")) continue;
            spans.add(new int[]{ mm.start(), mm.end() });
            toks.add(g);
        }
        if (toks.isEmpty()) return null;

        // Choose the transaction amount: if a trailing running balance is present
        // (2+ money tokens), the amount is the second-to-last; else the last.
        int pick = toks.size() >= 2 ? toks.size() - 2 : toks.size() - 1;
        String amtTok = toks.get(pick);

        BigDecimal amount = parseMoney(amtTok);
        if (amount == null) return null;

        // 3) sign
        int parseConf = 80;
        boolean neg;
        String low = s.toLowerCase();
        if (amtTok.contains("(") || amtTok.trim().endsWith("-") || amtTok.toUpperCase().contains("DR") || amtTok.contains("$-") || amtTok.startsWith("-")) {
            neg = true; parseConf = 90;
        } else if (amtTok.toUpperCase().contains("CR") || amtTok.trim().endsWith("+")) {
            neg = false; parseConf = 90;
        } else {
            Boolean kw = signFromKeywords(low);
            if (kw != null) { neg = kw; parseConf = 80; }
            else { neg = true; parseConf = 60; }   // default to debit, low confidence
        }
        amount = neg ? amount.abs().negate() : amount.abs();

        // 4) description = the whole line minus the date and the money tokens
        String desc = (s.substring(0, dateStart) + " " + s.substring(Math.min(dateEnd, s.length()))).trim();
        for (String tk : toks) desc = desc.replace(tk, " ");
        desc = desc.replaceAll("\\s+", " ").replaceAll("(?i)\\b(DR|CR)\\b", "").trim();
        desc = desc.replaceAll("^[\\-–:|*]+", "").replaceAll("[\\-–:|*]+$", "").trim();
        if (desc.isEmpty()) desc = "Transaction";

        // 5) reference / check number
        Txn t = new Txn();
        Matcher ck = CHECK.matcher(s);
        if (ck.find()) { t.checkNo = ck.group(1); t.refNo = ck.group(1); }
        else {
            Matcher rf = REF.matcher(s);
            if (rf.find()) t.refNo = rf.group(1);
        }

        t.date = iso;
        t.description = desc;
        t.payee = desc;
        t.amount = amount;
        t.parseConf = parseConf;
        t.scale = scale;
        return t;
    }

    /** Keyword sign hint: true = debit (negative), false = credit (positive), null = unknown. */
    private Boolean signFromKeywords(String low) {
        String[] debit = {"withdrawal","debit","purchase","payment","pos ","atm","fee","charge","bill pay","ach debit","check ","chk ","sent","transfer to","xfer to","pmt"};
        String[] credit = {"deposit","credit","ach credit","interest","refund","received","transfer from","xfer from","giving","donation","offering","tithe","payroll","direct dep","dir dep"};
        for (String k : credit) if (low.contains(k)) return false;
        for (String k : debit)  if (low.contains(k)) return true;
        return null;
    }

    // ── helpers ──

    private static BigDecimal parseMoney(String tok) {
        String v = tok.replaceAll("[^0-9.]", "");
        if (v.isEmpty() || !v.contains(".")) return null;
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.signum() <= 0 || d.compareTo(new BigDecimal("100000000")) > 0) return null;
            return d;
        } catch (NumberFormatException e) { return null; }
    }

    private static int normYear(String y) {
        int n = Integer.parseInt(y);
        if (y.length() == 2) return (n > 70 ? 1900 : 2000) + n;
        return n;
    }

    private static String isoOf(int yr, int mo, int da) {
        if (mo < 1 || mo > 12 || da < 1 || da > 31) return null;
        return String.format("%04d-%02d-%02d", yr, mo, da);
    }

    private static int monthNum(String s) {
        switch (s.substring(0, 3).toLowerCase()) {
            case "jan": return 1;  case "feb": return 2;  case "mar": return 3;  case "apr": return 4;
            case "may": return 5;  case "jun": return 6;  case "jul": return 7;  case "aug": return 8;
            case "sep": return 9;  case "oct": return 10; case "nov": return 11; case "dec": return 12;
            default: return 0;
        }
    }

    private static int clamp(int v) { return Math.max(1, Math.min(99, v)); }
}

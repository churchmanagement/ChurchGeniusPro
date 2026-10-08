package com.churchgeniuspro.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts contribution fields from a scanned/uploaded check.
 *
 * <p>This is the pluggable OCR/extraction point for the Income "Scan Check" flow.
 * <ul>
 *   <li><b>PDF</b> checks/remittances with a text layer are parsed here directly
 *       (via PDFBox) — payor, amount, written amount, date, check number, memo.</li>
 *   <li><b>Images</b> (JPG/PNG/HEIC) can't be read on the server (no OCR engine is
 *       configured). {@link #extract} returns {@code readable=false} so the browser
 *       can run on-device OCR (Tesseract.js) and POST the recognized text back to
 *       {@link #extractFromText} for the same parsing — or the user enters values
 *       manually. To wire a server-side OCR/vision provider, plug it in where
 *       {@code image-needs-ocr} is returned and feed its text to {@code parse(...)}.</li>
 * </ul>
 * All parsing is reused by both paths via {@link #extractFromText}.
 */
@Service
public class CheckExtractor {

    private static final Logger log = LoggerFactory.getLogger(CheckExtractor.class);

    /** Result returned to the controller / UI. */
    public static class Result {
        /** payorName, amount, amountWritten, date (yyyy-MM-dd), dateDisplay (MM/DD/YYYY), checkNo, memo */
        public Map<String, String> fields = new LinkedHashMap<>();
        public Map<String, Integer> confidence = new LinkedHashMap<>();
        public boolean readable = false;
        public boolean amountMismatch = false;
        public String method = "none";   // pdf-text | ocr-text | image-needs-ocr | none
        public String note = "";
        /** Full OCR/extracted text — lets the controller match contributor names anywhere on the check. */
        public String rawText = "";
    }

    // ── entry points ──

    public Result extract(byte[] bytes, String filename, String contentType) {
        Result r = new Result();
        String lc = filename == null ? "" : filename.toLowerCase();
        boolean isPdf = (contentType != null && contentType.contains("pdf")) || lc.endsWith(".pdf");

        if (isPdf) {
            String text = "";
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                text = new PDFTextStripper().getText(doc);
            } catch (Exception e) {
                r.method = "none";
                r.note = "Could not read the PDF: " + e.getMessage();
                return r;
            }
            if (text != null && text.replaceAll("\\s", "").length() >= 8) {
                parse(text, 85, r);
                r.method = "pdf-text";
                if (!r.readable) {
                    r.note = "This PDF didn't contain enough readable text. "
                           + "Please review the fields or enter the values manually.";
                }
                return r;
            }
            // PDF with no text layer == a scanned image inside a PDF → needs OCR.
            r.method = "image-needs-ocr";
            r.readable = false;
            r.note = "This looks like a scanned image. Reading it requires OCR.";
            return r;
        }

        boolean isImage = (contentType != null && contentType.startsWith("image"))
                || lc.endsWith(".jpg") || lc.endsWith(".jpeg") || lc.endsWith(".png")
                || lc.endsWith(".heic") || lc.endsWith(".heif") || lc.endsWith(".webp");
        if (isImage) {
            r.method = "image-needs-ocr";
            r.readable = false;
            r.note = "Image received — running OCR.";
            return r;
        }

        r.method = "none";
        r.note = "Unsupported file type. Please upload a JPG, PNG, HEIC, or PDF.";
        return r;
    }

    /** Parse already-recognized text (e.g. from browser OCR). {@code ocrConfidence} 0-100 scales results. */
    public Result extractFromText(String text, int ocrConfidence) {
        Result r = new Result();
        int base = Math.max(40, Math.min(95, ocrConfidence <= 0 ? 70 : ocrConfidence));
        if (log.isInfoEnabled()) {
            String raw = text == null ? "" : text;
            log.info("[CheckScan] raw OCR text ({} chars, ocrConf={}):\n{}", raw.length(), ocrConfidence,
                    raw.length() > 1500 ? raw.substring(0, 1500) + "…" : raw);
        }
        parse(text == null ? "" : text, base, r);
        r.method = "ocr-text";
        // We populate whatever we found; only note guidance when truly nothing was read.
        if (!r.readable) {
            r.note = "Only partial details could be read. Please review the fields below or enter the values manually.";
        }
        return r;
    }

    // ── core parser ──

    private void parse(String text, int base, Result r) {
        r.rawText = text == null ? "" : text;
        String[] lines = text.replace("\r", "").split("\n");

        // Amount (numeric courtesy box) — prefer a $-prefixed value, else the largest decimal.
        String[] amt = findAmount(text);
        if (amt[0] != null) { r.fields.put("amount", amt[0]); r.confidence.put("amount", clamp(base + Integer.parseInt(amt[1]))); }

        // Written (legal) amount line.
        String written = findWrittenAmount(lines);
        if (written != null) {
            r.fields.put("amountWritten", written);
            Double wv = WordAmount.parse(written);
            if (wv != null) {
                String wvStr = String.format("%.2f", wv);
                r.confidence.put("amountWritten", clamp(base - 5));
                if (amt[0] == null) {
                    r.fields.put("amount", wvStr);
                    r.confidence.put("amount", clamp(base - 10));
                } else {
                    try {
                        double nv = Double.parseDouble(amt[0]);
                        if (Math.abs(nv - wv) > 0.005) r.amountMismatch = true;
                    } catch (NumberFormatException ignore) { }
                }
            }
        }

        // Date.
        String[] dt = findDate(text);
        if (dt[0] != null) {
            r.fields.put("date", dt[0]);             // yyyy-MM-dd
            r.fields.put("dateDisplay", dt[1]);      // MM/DD/YYYY
            r.confidence.put("date", clamp(base + Integer.parseInt(dt[2])));
        }

        // Check / reference number.
        String[] chk = findCheckNo(text, lines);
        if (chk[0] != null) { r.fields.put("checkNo", chk[0]); r.confidence.put("checkNo", clamp(base + Integer.parseInt(chk[1]))); }

        // Memo.
        String memo = findMemo(lines);
        if (memo != null) { r.fields.put("memo", memo); r.confidence.put("memo", clamp(base)); }

        // Payor (account holder) name.
        String payor = findPayor(lines);
        if (payor != null) { r.fields.put("payorName", payor); r.confidence.put("payorName", clamp(base - 15)); }

        // Bank name (informational).
        String bank = findBank(text);
        if (bank != null) { r.fields.put("bank", bank); r.confidence.put("bank", clamp(base - 5)); }

        // Don't fail the whole scan when only some fields are found — populate whatever
        // we identified. "readable" just drives whether we show the success vs. retake hint.
        r.readable = r.fields.containsKey("amount")
                  || r.fields.containsKey("date")
                  || r.fields.containsKey("checkNo")
                  || r.fields.containsKey("payorName")
                  || r.fields.containsKey("memo");

        if (log.isInfoEnabled()) {
            log.info("[CheckScan] mapping (readable={}):\n  Detected Contributor: {}\n  Detected Amount: {}\n  Detected Date: {}\n  Detected Ref No: {}\n  Detected Notes: {}",
                    r.readable,
                    nz(r.fields.get("payorName")), nz(r.fields.get("amount")),
                    nz(r.fields.get("dateDisplay")), nz(r.fields.get("checkNo")), nz(r.fields.get("memo")));
        }
    }

    private static String nz(String s) { return (s == null || s.isEmpty()) ? "(none)" : s; }

    // ── field finders ──

    /**
     * The dollars part of an amount: either comma-grouped ({@code 1,234}) or a
     * plain digit run ({@code 12345}). The comma-grouped form comes first and
     * requires at least one group, so it can never claim the leading digits of a
     * plain run.
     *
     * <p>This used to be {@code [0-9]{1,3}(?:,[0-9]{3})*}, which matches one to
     * three digits and then OPTIONAL comma groups — so on a courtesy box written
     * without a thousands comma it matched the first three digits and stopped:
     * {@code $12345.67} was read as {@code 123}, and the alternative meant to
     * catch the plain form was never reached. A five-figure check pre-filled the
     * income form with a plausible three-figure amount.
     */
    private static final String DOLLARS = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)";

    /**
     * {@code $}-prefixed amount, optional cents. The trailing {@code (?![0-9])} is
     * what makes the token whole: an amount that is a prefix of a longer digit run
     * is not an amount.
     */
    private static final Pattern AMOUNT = Pattern.compile("\\$\\s*(" + DOLLARS + "(?:\\.[0-9]{2})?)(?![0-9])");
    // Courtesy box where the cents are separated by a dash/dot, e.g. "$ 100-00", "S 100.00".
    // OCR often reads "$" as "S" or "5", so accept those prefixes too.
    private static final Pattern AMOUNT_DASH = Pattern.compile("[\\$S§]\\s*(" + DOLLARS + ")\\s*[\\-.]\\s*([0-9]{2})\\b");
    // Last-resort: a currency-ish prefix then dollars and 2-digit cents separated by dash/dot/space.
    private static final Pattern AMOUNT_LOOSE = Pattern.compile("[\\$S§]\\s*(" + DOLLARS + ")\\s*[\\-.\\s]\\s*([0-9]{2})\\b");
    // Any decimal money token, with or without thousands commas.
    private static final Pattern DECIMAL = Pattern.compile("\\b(" + DOLLARS + "\\.[0-9]{2})\\b");

    /** @return [value or null, confidenceBonus] */
    private String[] findAmount(String text) {
        // 1a) "$ 100-00" / "S 100.00" style (dash/dot between dollars and cents).
        Matcher md = AMOUNT_DASH.matcher(text);
        if (md.find()) {
            String v = clean(md.group(1).replace(",", "") + "." + md.group(2));
            if (v != null) return new String[]{ v, "10" };
        }
        // 1) $-prefixed value wins (the courtesy amount box).
        Matcher m = AMOUNT.matcher(text);
        String best = null;
        while (m.find()) {
            String v = clean(m.group(1));
            if (v != null) { best = v; break; }   // first $-amount
        }
        if (best != null) return new String[]{ best, "10" };
        // 2) otherwise the largest decimal token.
        Matcher d = DECIMAL.matcher(text);
        double max = -1; String maxStr = null;
        while (d.find()) {
            String v = clean(d.group(1));
            if (v == null) continue;
            try { double x = Double.parseDouble(v); if (x > max) { max = x; maxStr = v; } } catch (NumberFormatException ignore) { }
        }
        if (maxStr != null) return new String[]{ maxStr, "0" };
        // 3) last-resort: currency-ish prefix + dollars + 2-digit cents (dash/dot/space).
        Matcher lo = AMOUNT_LOOSE.matcher(text);
        if (lo.find()) {
            String v = clean(lo.group(1).replace(",", "") + "." + lo.group(2));
            if (v != null) return new String[]{ v, "-10" };
        }
        return new String[]{ null, "0" };
    }

    private static final String NUM_WORD = "(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)";

    private String findWrittenAmount(String[] lines) {
        for (String line : lines) {
            String l = line.trim();
            String low = l.toLowerCase();
            boolean hasNumWord   = low.matches(".*\\b" + NUM_WORD + "\\b.*");
            boolean hasMagnitude = low.matches(".*\\b(hundred|thousand|million)\\b.*");
            boolean hasCentsHint = low.contains("/100") || low.matches(".*\\b(and|no|xx|dollars?)\\b.*");
            // Accept a written-amount line on a number word + a magnitude word, OR the
            // explicit "dollars"/"/100" cue — don't require both (OCR often drops one).
            if (low.contains("dollar") || (hasNumWord && (hasMagnitude || hasCentsHint))) {
                if (l.length() >= 5) return l.replaceAll("\\*+", " ").replaceAll("\\s+", " ").trim();
            }
        }
        return null;
    }

    // Allow "/", "-", ".", "|" or spaces as separators (handwritten dates OCR oddly).
    private static final Pattern D_NUM   = Pattern.compile("\\b(0?[1-9]|1[0-2])\\s*[/\\-.|]\\s*(0?[1-9]|[12][0-9]|3[01])\\s*[/\\-.|]\\s*(\\d{2,4})\\b");
    private static final Pattern D_ISO   = Pattern.compile("\\b(\\d{4})-(0?[1-9]|1[0-2])-(0?[1-9]|[12][0-9]|3[01])\\b");
    private static final Pattern D_WORD  = Pattern.compile("\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern D_WORD2 = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // Loose: MM DD YYYY with any/space separators, anchored by a 4-digit 19xx/20xx year.
    private static final Pattern D_LOOSE = Pattern.compile("\\b(0?[1-9]|1[0-2])\\s*[/\\-.| ]+\\s*(0?[1-9]|[12][0-9]|3[01])\\s*[/\\-.| ]+\\s*((?:19|20)\\d{2})\\b");

    /** @return [iso yyyy-MM-dd or null, display MM/DD/YYYY, confidenceBonus] */
    private String[] findDate(String text) {
        Matcher m = D_NUM.matcher(text);
        if (m.find()) {
            int mo = Integer.parseInt(m.group(1)), da = Integer.parseInt(m.group(2));
            String y = m.group(3); int yr = Integer.parseInt(y);
            if (y.length() == 2) yr = (yr > 70 ? 1900 : 2000) + yr;
            return iso(yr, mo, da, "5");
        }
        Matcher i = D_ISO.matcher(text);
        if (i.find()) return iso(Integer.parseInt(i.group(1)), Integer.parseInt(i.group(2)), Integer.parseInt(i.group(3)), "5");
        Matcher w = D_WORD.matcher(text);
        if (w.find()) return iso(Integer.parseInt(w.group(3)), monthNum(w.group(1)), Integer.parseInt(w.group(2)), "8");
        Matcher w2 = D_WORD2.matcher(text);
        if (w2.find()) return iso(Integer.parseInt(w2.group(3)), monthNum(w2.group(2)), Integer.parseInt(w2.group(1)), "8");
        Matcher lo = D_LOOSE.matcher(text);
        if (lo.find()) return iso(Integer.parseInt(lo.group(3)), Integer.parseInt(lo.group(1)), Integer.parseInt(lo.group(2)), "0");
        return new String[]{ null, null, "0" };
    }

    private static final Pattern CHK_LABEL = Pattern.compile("(?:check|cheque|chk|no|number|ref(?:erence)?)\\s*(?:no\\.?|number|#|:)?\\s*[#:]?\\s*(\\d{3,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADDR = Pattern.compile("\\b(st|street|ave|avenue|rd|road|blvd|ln|lane|dr|drive|ct|court|way|pkwy|hwy|suite|apt|unit|box)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CITY_ZIP = Pattern.compile(",\\s*[A-Za-z]{2}\\s*\\d{5}");
    private static final Pattern CHK_TOKEN = Pattern.compile("(?<!\\d)(\\d{3,6})(?!\\d)");

    /** @return [number or null, confidenceBonus] */
    private String[] findCheckNo(String text, String[] lines) {
        Matcher m = CHK_LABEL.matcher(text);
        if (m.find()) return new String[]{ m.group(1), "8" };
        // Fallback: a standalone 3-6 digit token that isn't a routing/account number
        // (those are 9 / many digits → excluded by the boundaries), a year, a ZIP, an
        // amount, or part of a street address.
        for (String line : lines) {
            String low = line.toLowerCase();
            if (low.contains("dollar") || low.contains("/100") || low.contains("$")) continue;
            if (ADDR.matcher(line).find() || CITY_ZIP.matcher(line).find()) continue;
            if (line.matches(".*\\b\\d{1,3}-\\d{1,3}/\\d{2,4}\\b.*")) continue;   // bank routing fraction (e.g. 81-7/820)
            Matcher t = CHK_TOKEN.matcher(line);
            while (t.find()) {
                String v = t.group(1);
                int n = Integer.parseInt(v);
                if (v.length() == 4 && n >= 1900 && n <= 2100) continue;   // year
                return new String[]{ v, "0" };
            }
        }
        return new String[]{ null, "0" };
    }

    private static final Pattern MEMO = Pattern.compile("(?:memo|for|re|note|notes)\\s*[:\\-]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMO_FOR = Pattern.compile("(?i)\\bfor\\b[\\s:_\\-]+([A-Za-z][A-Za-z &'/]{1,40})$");
    private static final Pattern MEMO_KEYWORD = Pattern.compile("(?i)\\b(tithe|tithes|offering|offerings|donation|missions?|building\\s*fund|pledge|benevolence|love\\s*gift|first\\s*fruits)\\b");

    private String findMemo(String[] lines) {
        // 1) explicit "Memo:/For:" label
        for (String line : lines) {
            Matcher m = MEMO.matcher(line.trim());
            if (m.find()) {
                String v = clip(m.group(1));
                if (v != null) return v;
            }
        }
        // 2) "For <value>" without a colon (handwritten memo line)
        for (String line : lines) {
            Matcher m = MEMO_FOR.matcher(line.trim());
            if (m.find()) {
                String v = clip(m.group(1));
                if (v != null && !v.equalsIgnoreCase("deposit") && !v.toLowerCase().contains("order")) return v;
            }
        }
        // 3) a recognizable giving purpose anywhere on the check
        for (String line : lines) {
            Matcher k = MEMO_KEYWORD.matcher(line);
            if (k.find()) {
                String w = k.group(1).trim();
                return w.substring(0, 1).toUpperCase() + w.substring(1);
            }
        }
        return null;
    }

    private static final Pattern TITLE_WORD = Pattern.compile("[A-Z][a-z]{2,}");

    private String findPayor(String[] lines) {
        // The account holder's name (printed, usually top-left) is the contributor when
        // "Pay to the order of" is blank. OCR prepends/appends noise (e.g. "PAE Anson
        // Mathew Co"), so we look for the longest run of Title-Case words (First letter
        // upper, rest lower) on a line — which skips ALL-CAPS garbage like "EAA RRL".
        int scan = Math.min(lines.length, 16);
        for (int i = 0; i < scan; i++) {
            String l = lines[i].trim().replaceAll("[\\*|]+", " ").replaceAll("\\s+", " ").trim();
            if (l.length() < 3) continue;
            String low = l.toLowerCase();
            if (low.contains("pay to") || low.contains("order of") || low.contains("bank")
                || low.contains("memo") || low.contains("dollar") || low.contains("date")
                || low.contains("everlasting") || low.contains("believe") || low.contains("glory")
                || low.contains("john") || low.contains("god") || low.contains("highest")) continue;
            java.util.List<String> run = new java.util.ArrayList<>();
            java.util.List<String> best = new java.util.ArrayList<>();
            for (String w : l.split("\\s+")) {
                if (TITLE_WORD.matcher(w).matches()) {
                    run.add(w);
                    if (run.size() > best.size()) best = new java.util.ArrayList<>(run);
                } else {
                    run.clear();
                }
            }
            if (best.size() >= 2) {
                java.util.List<String> name = best.size() > 3 ? best.subList(0, 3) : best;
                return String.join(" ", name);
            }
        }
        return null;
    }

    private static final Pattern BANK = Pattern.compile("(?i)\\b(Bank of America|Wells Fargo|Chase|JPMorgan|Citibank|Citi|US Bank|U\\.S\\. Bank|PNC|Capital One|TD Bank|Truist|Regions|Fifth Third|KeyBank|Huntington|BMO|Ally|Navy Federal|USAA|Synovus|Comerica|[A-Z][A-Za-z]+ (?:Bank|Credit Union|Federal Credit Union))\\b");

    private String findBank(String text) {
        Matcher m = BANK.matcher(text);
        if (m.find()) { String b = m.group(1).trim(); if (b.length() >= 3 && b.length() <= 40) return b; }
        return null;
    }

    /** Trim and bound-check a candidate memo value. */
    private static String clip(String s) {
        if (s == null) return null;
        String v = s.replaceAll("\\*+", " ").replaceAll("\\s+", " ").trim().replaceAll("[\\-_]+$", "").trim();
        return (v.length() >= 2 && v.length() <= 80) ? v : null;
    }

    // ── helpers ──

    private String[] iso(int yr, int mo, int da, String bonus) {
        if (mo < 1 || mo > 12 || da < 1 || da > 31) return new String[]{ null, null, "0" };
        String isoStr = String.format("%04d-%02d-%02d", yr, mo, da);
        String disp   = String.format("%02d/%02d/%04d", mo, da, yr);
        return new String[]{ isoStr, disp, bonus };
    }

    private static int monthNum(String s) {
        String m = s.substring(0, 3).toLowerCase();
        switch (m) {
            case "jan": return 1;  case "feb": return 2;  case "mar": return 3;  case "apr": return 4;
            case "may": return 5;  case "jun": return 6;  case "jul": return 7;  case "aug": return 8;
            case "sep": return 9;  case "oct": return 10; case "nov": return 11; case "dec": return 12;
            default: return 0;
        }
    }

    private static final java.math.BigDecimal MAX_AMOUNT = new java.math.BigDecimal("10000000");

    /**
     * Strip commas, validate as a positive money value, normalise to two places.
     *
     * <p>BigDecimal rather than the double round-trip it replaced: the value is
     * money on its way into the ledger, and {@code String.format("%.2f")} without a
     * locale writes {@code 1234,56} on a comma-decimal JVM, which the page then
     * cannot parse.
     */
    private static String clean(String v) {
        if (v == null) return null;
        v = v.replace(",", "").trim();
        if (!v.matches("\\d+(?:\\.\\d{1,2})?")) return null;
        java.math.BigDecimal d = new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP);
        if (d.signum() <= 0 || d.compareTo(MAX_AMOUNT) > 0) return null;
        return d.toPlainString();
    }

    private static int clamp(int v) { return Math.max(1, Math.min(99, v)); }

    /**
     * Case-insensitive fuzzy name score 0..100 (token overlap + edit distance).
     * Used by the controller to match a detected payor to existing contributors.
     */
    public static int nameScore(String a, String b) {
        if (a == null || b == null) return 0;
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 100;
        // token overlap
        List<String> tx = tokens(x), ty = tokens(y);
        int common = 0;
        for (String t : tx) if (ty.contains(t)) common++;
        int overlap = (int) Math.round(100.0 * common / Math.max(tx.size(), ty.size()));
        if (x.contains(y) || y.contains(x)) overlap = Math.max(overlap, 88);
        // edit-distance ratio on the whole string
        int dist = levenshtein(x, y);
        int ratio = (int) Math.round(100.0 * (1.0 - (double) dist / Math.max(x.length(), y.length())));
        return Math.max(overlap, ratio);
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(" ")) if (t.length() > 1) out.add(t);
        if (out.isEmpty()) for (String t : s.split(" ")) if (!t.isEmpty()) out.add(t);
        return out;
    }
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    /** Minimal English word-number parser for written check amounts (handles "... and 00/100"). */
    static final class WordAmount {
        private static final Map<String, Integer> UNITS = new LinkedHashMap<>();
        static {
            String[] u = {"zero","one","two","three","four","five","six","seven","eight","nine","ten",
                "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};
            for (int i = 0; i < u.length; i++) UNITS.put(u[i], i);
            String[] tens = {"twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};
            for (int i = 0; i < tens.length; i++) UNITS.put(tens[i], (i + 2) * 10);
        }

        static Double parse(String s) {
            if (s == null) return null;
            String low = s.toLowerCase();
            // cents: "and 00/100", "and no/100", "xx/100".
            // Cut the dollars portion at the cents match (NOT at the word "and" —
            // "thousand" contains "and" and would truncate the number).
            int cents = 0; boolean haveCents = false;
            String dollarsPart = low;
            Matcher cm = Pattern.compile("(\\d{1,2}|no|xx)\\s*/\\s*100").matcher(low);
            if (cm.find()) {
                String c = cm.group(1);
                cents = (c.equals("no") || c.equals("xx")) ? 0 : Integer.parseInt(c);
                haveCents = true;
                dollarsPart = low.substring(0, cm.start());
            }
            int dl = dollarsPart.indexOf("dollar");
            if (dl >= 0) dollarsPart = dollarsPart.substring(0, dl);

            long total = 0, current = 0; boolean any = false;
            for (String w : dollarsPart.replaceAll("[^a-z ]", " ").split("\\s+")) {
                if (w.isEmpty()) continue;
                if (w.equals("and")) continue;
                if (UNITS.containsKey(w)) { current += UNITS.get(w); any = true; }
                else if (w.equals("hundred")) { current = (current == 0 ? 1 : current) * 100; any = true; }
                else if (w.equals("thousand")) { total += (current == 0 ? 1 : current) * 1000; current = 0; any = true; }
                else if (w.equals("million"))  { total += (current == 0 ? 1 : current) * 1000000; current = 0; any = true; }
            }
            total += current;
            if (!any && !haveCents) return null;
            return total + cents / 100.0;
        }
    }
}

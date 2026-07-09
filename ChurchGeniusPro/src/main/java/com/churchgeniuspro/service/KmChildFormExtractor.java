package com.churchgeniuspro.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts child-registration fields from an uploaded form.
 *
 * <p>Pluggable extraction point. Today it reads <b>digital PDFs</b> exactly: it pulls
 * AcroForm field values (a form filled on a computer) or, failing that, parses the PDF
 * text layer ("Label: value"). For scanned images / handwritten paper, real OCR or a
 * vision-AI service should be wired in here — the surrounding upload → review → de-dupe
 * → save workflow already works.
 */
@Service
public class KmChildFormExtractor {

    /** Document label (lower-cased) → canonical child field key. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("child first name", "firstName");
        LABELS.put("first name", "firstName");
        LABELS.put("child last name", "lastName");
        LABELS.put("last name", "lastName");
        LABELS.put("date of birth (mm/dd/yyyy)", "dob");
        LABELS.put("date of birth", "dob");
        LABELS.put("dob", "dob");
        LABELS.put("gender (male / female / other)", "gender");
        LABELS.put("gender", "gender");
        LABELS.put("grade / class", "grade");
        LABELS.put("grade", "grade");
        LABELS.put("class", "grade");
        LABELS.put("parent / guardian name", "parentName");
        LABELS.put("parent/guardian name", "parentName");
        LABELS.put("parent name", "parentName");
        LABELS.put("guardian name", "parentName");
        LABELS.put("phone", "parentPhone");
        LABELS.put("parent phone", "parentPhone");
        LABELS.put("parent / guardian phone", "parentPhone");
        LABELS.put("email", "parentEmail");
        LABELS.put("e-mail", "parentEmail");
        LABELS.put("parent email", "parentEmail");
        LABELS.put("contact name", "emergencyContactName");
        LABELS.put("emergency contact name", "emergencyContactName");
        LABELS.put("emergency contact", "emergencyContactName");
        LABELS.put("contact phone", "emergencyContactPhone");
        LABELS.put("emergency contact phone", "emergencyContactPhone");
        LABELS.put("emergency phone", "emergencyContactPhone");
        LABELS.put("allergies", "allergies");
        LABELS.put("medical notes / special needs", "medicalNotes");
        LABELS.put("medical notes", "medicalNotes");
        LABELS.put("medical", "medicalNotes");
        LABELS.put("special needs", "medicalNotes");
    }

    /** Per-child fields (each Child N section). */
    private static final String[] CHILD_KEYS = {
        "firstName", "lastName", "dob", "gender", "grade", "allergies", "medicalNotes"
    };
    /** Shared fields used for every extracted child. */
    private static final String[] SHARED_KEYS = {
        "parentName", "parentPhone", "parentEmail", "emergencyContactName", "emergencyContactPhone"
    };
    private static final java.util.Set<String> CHILD_KEY_SET = new java.util.HashSet<>(java.util.Arrays.asList(CHILD_KEYS));
    private static final java.util.Set<String> SHARED_KEY_SET = new java.util.HashSet<>(java.util.Arrays.asList(SHARED_KEYS));

    private static final String FEATURE = "kids-child-scan";

    /** OpenAI vision OCR for images and scanned (no-text) PDFs. */
    private final VisionCheckService vision;
    /** Resizes/validates images and enforces the per-church Vision quota before sending. */
    private final VisionUploadService visionUpload;
    /** Records per-call token usage / cost / outcome for admin reporting. */
    private final VisionUsageTracker tracker;

    public KmChildFormExtractor(VisionCheckService vision,
                                VisionUploadService visionUpload,
                                VisionUsageTracker tracker) {
        this.vision = vision;
        this.visionUpload = visionUpload;
        this.tracker = tracker;
    }

    public Result extract(byte[] bytes, String filename, String contentType, String clientId, String username) {
        Result r = new Result();
        String lc = filename == null ? "" : filename.toLowerCase();
        boolean isPdf = (contentType != null && contentType.contains("pdf")) || lc.endsWith(".pdf");

        if (!isPdf) {
            // Image (photo / scan) → resize → OpenAI vision OCR (metered + logged).
            runVision(bytes, contentType, filename, clientId, username, r);
            finalizeFlat(r);
            return r;
        }

        // Per-child raw maps (index 0..2) + shared raw map.
        @SuppressWarnings("unchecked")
        Map<String, String>[] childRaw = new Map[]{ new LinkedHashMap<String, String>(),
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>() };
        Map<String, String> sharedRaw = new LinkedHashMap<>();
        boolean usedVision = false;

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            int formVals = 0;
            if (acro != null) {
                for (int i = 1; i <= 3; i++) {
                    String sfx = (i == 1) ? "" : String.valueOf(i);
                    for (String key : CHILD_KEYS) {
                        String val = fieldVal(acro, key + sfx);
                        if (val != null) { childRaw[i - 1].put(key, val); formVals++; }
                    }
                }
                for (String key : SHARED_KEYS) {
                    String val = fieldVal(acro, key);
                    if (val != null) { sharedRaw.put(key, val); formVals++; }
                }
            }
            if (formVals > 0) {
                r.method = "form-fields";
            } else {
                String text = new PDFTextStripper().getText(doc);
                parseTextMulti(text, childRaw, sharedRaw);
                boolean any = !sharedRaw.isEmpty();
                for (Map<String, String> cm : childRaw) any = any || !cm.isEmpty();
                if (any) {
                    r.method = "text";
                } else if (vision != null && vision.isEnabled()) {
                    // Scanned PDF with no AcroForm and no text layer → rasterize + vision.
                    byte[] png = renderFirstPage(doc);
                    if (png != null) {
                        runVision(png, "image/png", "scan-page.png", clientId, username, r);
                        usedVision = "vision".equals(r.method);
                    } else {
                        r.method = "none";
                        r.note = "Could not read this scanned PDF. Please enter the details manually.";
                    }
                } else {
                    r.method = "none";
                    r.note = "No fillable fields or readable text were found. If this is a scanned image, "
                           + "OpenAI Vision is required (not configured) — please enter the details manually.";
                }
            }
        } catch (Exception e) {
            r.method = "error";
            r.note = "Could not read the file: " + e.getMessage();
            return r;
        }

        if (!usedVision) {
            // Build the per-child list from the parsed AcroForm/text maps.
            r.shared = normalizeShared(sharedRaw);
            for (Map<String, String> cm : childRaw) {
                Map<String, String> child = normalize(cm);
                String fn = child.get("firstName"), ln = child.get("lastName");
                if ((fn != null && !fn.isBlank()) || (ln != null && !ln.isBlank())) r.children.add(child);
            }
        }
        finalizeFlat(r);
        return r;
    }

    /** Map a vision result ({children, shared, authorizedPickups}) into the Result, normalizing each child. */
    @SuppressWarnings("unchecked")
    private void applyVision(Map<String, Object> v, Result r) {
        Object ch = v.get("children");
        if (ch instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, String> child = normalize((Map<String, String>) o);
                String fn = child.get("firstName"), ln = child.get("lastName");
                if ((fn != null && !fn.isBlank()) || (ln != null && !ln.isBlank())) r.children.add(child);
            }
        }
        Object sh = v.get("shared");
        if (sh instanceof Map) r.shared = normalizeShared((Map<String, String>) sh);
        Object pk = v.get("authorizedPickups");
        if (pk instanceof List) r.pickups = (List<Map<String, String>>) pk;
    }

    /**
     * Down-scale the image, enforce the per-church Vision quota/limits, run OpenAI Vision,
     * and record the call's token usage / cost / outcome. Sets {@code r.method="vision"} on
     * success, otherwise {@code "none"} with a user-facing note.
     */
    private void runVision(byte[] bytes, String contentType, String filename,
                           String clientId, String username, Result r) {
        if (vision == null || !vision.isEnabled()) {
            r.method = "none";
            r.note = "Reading scanned/handwritten images requires OpenAI Vision (not configured). "
                   + "Upload a PDF filled on a computer, or enter the details manually.";
            return;
        }
        // Per-church enable flag + upload quota.
        if (visionUpload != null && !visionUpload.available(clientId)) {
            if (tracker != null) tracker.recordRejected(clientId, username, FEATURE,
                    "Vision quota reached or disabled for church");
            r.method = "none";
            r.note = "AI scanning is unavailable (monthly limit reached or disabled). "
                   + "Please enter the details manually or contact your administrator.";
            return;
        }
        // Down-scale / re-encode to cut tokens & bandwidth before sending to OpenAI.
        byte[] sendBytes = bytes;
        String sendType = contentType;
        if (visionUpload != null) {
            try {
                VisionUploadService.Prepared p = visionUpload.prepare(clientId, bytes, contentType, filename);
                sendBytes = p.bytes;
                sendType = p.contentType;
            } catch (VisionUploadService.VisionRejectedException ex) {
                if (tracker != null) tracker.recordRejected(clientId, username, FEATURE, ex.getMessage());
                r.method = "none";
                r.note = ex.getMessage();
                return;
            }
        }
        VisionCheckService.VisionExtraction ext = vision.extractChildFormDetailed(sendBytes, sendType);
        int kids = (ext != null && ext.success && ext.data != null) ? childCount(ext.data) : 0;
        if (tracker != null) tracker.record(clientId, username, FEATURE, 1, ext, kids);

        if (ext != null && ext.success && ext.data != null) {
            applyVision(ext.data, r);
            r.method = "vision";
        } else {
            r.method = "none";
            r.note = (ext != null && ext.error != null)
                   ? "AI could not read this image (" + ext.error + "). Please enter the details manually."
                   : "Could not read this image. Please enter the details manually.";
        }
    }

    @SuppressWarnings("unchecked")
    private int childCount(Map<String, Object> data) {
        Object ch = data.get("children");
        return (ch instanceof List) ? ((List<Object>) ch).size() : 0;
    }

    /** Build the back-compat flat `fields` (first child + shared) consumed by single-child callers. */
    private void finalizeFlat(Result r) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (!r.children.isEmpty()) flat.putAll(r.children.get(0));
        if (r.shared != null) flat.putAll(r.shared);
        r.fields = flat;
    }

    /** Render the first PDF page to a PNG byte[] for vision OCR; null on failure. */
    private byte[] renderFirstPage(PDDocument doc) {
        try {
            if (doc.getNumberOfPages() == 0) return null;
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 150, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private String fieldVal(PDAcroForm acro, String name) {
        PDField f = acro.getField(name);
        if (f == null) return null;
        String v = f.getValueAsString();
        return (v != null && !v.trim().isEmpty()) ? v.trim() : null;
    }

    /**
     * Parse the PDF text layer into per-child + shared maps by tracking the current
     * "Child N Information" / "Parent" / "Emergency" section header.
     */
    private void parseTextMulti(String text, Map<String, String>[] childRaw, Map<String, String> sharedRaw) {
        if (text == null) return;
        int current = 0;   // 0 = shared (parent/emergency); 1..3 = child index
        Pattern childHeader = Pattern.compile("^child\\s*([123])\\b");
        for (String line : text.replace("\r", "").split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                // No "label: value" on this line — it may be a section header.
                String lower = line.trim().toLowerCase().replaceAll("\\s+", " ");
                Matcher hm = childHeader.matcher(lower);
                if (hm.find()) { current = Integer.parseInt(hm.group(1)); }
                else if (lower.startsWith("parent") || lower.startsWith("emergency") || lower.startsWith("authorized")) {
                    current = 0;
                }
                continue;
            }
            String label = line.substring(0, idx).trim().toLowerCase().replaceAll("\\s+", " ");
            String value = line.substring(idx + 1).trim();
            if (value.isEmpty()) continue;
            String key = LABELS.get(label);
            if (key == null) continue;
            if (current >= 1 && CHILD_KEY_SET.contains(key)) {
                childRaw[current - 1].putIfAbsent(key, value);
            } else if (SHARED_KEY_SET.contains(key)) {
                sharedRaw.putIfAbsent(key, value);
            }
        }
    }

    private Map<String, String> normalizeShared(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.isEmpty()) out.put(e.getKey(), v);
        }
        return out;
    }

    private Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey(), v = e.getValue().trim();
            if (v.isEmpty()) continue;
            if ("gender".equals(k)) v = normGender(v);
            else if ("dob".equals(k)) v = normDate(v);
            if (!v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    /** Normalize a date to yyyy-MM-dd (the HTML date input value). */
    private String normDate(String v) {
        if (v == null) return "";
        v = v.trim();
        // already ISO?
        if (v.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            Matcher iso = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(v);
            if (iso.find()) return iso.group(1) + "-" + pad(iso.group(2)) + "-" + pad(iso.group(3));
        }
        Matcher m = Pattern.compile("(\\d{1,2})\\D+(\\d{1,2})\\D+(\\d{2,4})").matcher(v);
        if (m.find()) {
            String mo = m.group(1), da = m.group(2), yr = m.group(3);
            if (yr.length() == 2) yr = (Integer.parseInt(yr) > 30 ? "19" : "20") + yr;
            return yr + "-" + pad(mo) + "-" + pad(da);
        }
        return "";
    }

    private String pad(String n) { int i = Integer.parseInt(n); return (i < 10 ? "0" : "") + i; }

    private String normGender(String v) {
        String l = v.toLowerCase();
        if (l.startsWith("m")) return "Male";
        if (l.startsWith("f")) return "Female";
        if (l.startsWith("o")) return "Other";
        return v;
    }

    /** Extraction result returned to the UI. */
    public static class Result {
        /** Back-compat flat view: first child's fields + shared parent/emergency. */
        public Map<String, String> fields = new LinkedHashMap<>();
        /** One map per completed Child N section (firstName, lastName, dob, gender, grade, allergies, medicalNotes). */
        public java.util.List<Map<String, String>> children = new java.util.ArrayList<>();
        /** Shared parent/guardian + emergency fields applied to every extracted child. */
        public Map<String, String> shared = new LinkedHashMap<>();
        /** Shared authorized-pickup entries (from vision OCR): [{personName,relationship,phone}]. */
        public java.util.List<Map<String, String>> pickups = new java.util.ArrayList<>();
        public String method = "none";  // form-fields | text | vision | none | error
        public String note = "";
    }
}

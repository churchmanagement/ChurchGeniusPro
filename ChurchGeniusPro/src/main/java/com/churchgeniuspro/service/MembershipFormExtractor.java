package com.churchgeniuspro.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts member fields from an uploaded membership form.
 *
 * <p>This is the pluggable extraction point. Today it handles <b>digital PDFs</b>
 * exactly: it reads AcroForm field values (from a form filled on a computer) or,
 * failing that, parses the PDF text layer ("Label: value" lines). For scanned
 * images / handwritten paper, real OCR or a vision-AI service should be wired in
 * here — the surrounding upload → review → de-dupe → save workflow already works.
 *
 * <p>Member Type is deliberately NOT extracted — it is assigned by church staff.
 * In addition to the primary member, up to
 * {@link MembershipFormPdfService#MAX_ADDITIONAL_MEMBERS} additional household
 * members are read into {@link Result#additionalMembers}.
 */
@Service
public class MembershipFormExtractor {

    /** Document label (lower-cased) → canonical PRIMARY member field key. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("first name", "firstName");
        LABELS.put("last name", "lastName");
        LABELS.put("preferred / other name", "otherName");
        LABELS.put("other name", "otherName");
        LABELS.put("preferred name", "otherName");
        LABELS.put("nickname", "otherName");
        LABELS.put("gender (male / female)", "gender");
        LABELS.put("gender", "gender");
        LABELS.put("date of birth (mm/dd/yyyy)", "dateOfBirth");
        LABELS.put("date of birth", "dateOfBirth");
        LABELS.put("dob", "dateOfBirth");
        LABELS.put("marital status", "maritalStatus");
        LABELS.put("wedding anniversary (mm/dd/yyyy)", "weddingAnniversary");
        LABELS.put("wedding anniversary", "weddingAnniversary");
        LABELS.put("anniversary", "weddingAnniversary");
        LABELS.put("email", "email");
        LABELS.put("e-mail", "email");
        LABELS.put("phone", "phone");
        LABELS.put("mobile", "phone");
        LABELS.put("address line 1", "address1");
        LABELS.put("address 1", "address1");
        LABELS.put("address line 2", "address2");
        LABELS.put("address 2", "address2");
        LABELS.put("city", "city");
        LABELS.put("state", "state");
        LABELS.put("zip / postal code", "pinCode");
        LABELS.put("zip", "pinCode");
        LABELS.put("postal code", "pinCode");
        LABELS.put("pin code", "pinCode");
        LABELS.put("country", "country");
    }

    /** Document label (lower-cased) → canonical ADDITIONAL-member field key. */
    private static final Map<String, String> MEMBER_LABELS = new LinkedHashMap<>();
    static {
        MEMBER_LABELS.put("first name", "firstName");
        MEMBER_LABELS.put("last name", "lastName");
        MEMBER_LABELS.put("relationship (spouse / adult / child)", "role");
        MEMBER_LABELS.put("relationship", "role");
        MEMBER_LABELS.put("relation", "role");
        MEMBER_LABELS.put("nickname", "otherName");
        MEMBER_LABELS.put("other name", "otherName");
        MEMBER_LABELS.put("preferred / other name", "otherName");
        MEMBER_LABELS.put("gender (male / female)", "gender");
        MEMBER_LABELS.put("gender", "gender");
        MEMBER_LABELS.put("date of birth (mm/dd/yyyy)", "dateOfBirth");
        MEMBER_LABELS.put("date of birth", "dateOfBirth");
        MEMBER_LABELS.put("dob", "dateOfBirth");
        MEMBER_LABELS.put("phone", "phone");
        MEMBER_LABELS.put("mobile", "phone");
        MEMBER_LABELS.put("email", "email");
        MEMBER_LABELS.put("e-mail", "email");
        MEMBER_LABELS.put("address (only if different from above)", "address1");
        MEMBER_LABELS.put("address (if different from above)", "address1");
        MEMBER_LABELS.put("address", "address1");
    }

    public Result extract(byte[] bytes, String filename, String contentType) {
        Result r = new Result();
        String lc = filename == null ? "" : filename.toLowerCase();
        boolean isPdf = (contentType != null && contentType.contains("pdf")) || lc.endsWith(".pdf");
        if (!isPdf) {
            r.method = "none";
            r.note = "This looks like an image. Automatic reading of scanned/handwritten images "
                   + "needs an OCR/AI service (not configured). Upload a PDF filled on a computer, "
                   + "or enter the details manually.";
            return r;
        }

        Map<String, String> raw = new LinkedHashMap<>();
        List<Map<String, String>> rawMembers = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            int formVals = 0;
            if (acro != null) {
                for (String n : MembershipFormPdfService.FIELD_NAMES) {
                    PDField f = acro.getField(n);
                    if (f != null) {
                        String v = f.getValueAsString();
                        if (v != null && !v.trim().isEmpty()) { raw.put(n, v.trim()); formVals++; }
                    }
                }
                // "Make it Private" checkboxes → privacy flags. A ticked box reads
                // back as "Yes" (any non-empty, non-"Off" value counts as checked).
                for (String pf : MembershipFormPdfService.PRIVACY_FIELDS) {
                    PDField f = acro.getField(pf);
                    if (f != null) {
                        String v = f.getValueAsString();
                        if (v != null && !v.trim().isEmpty() && !v.trim().equalsIgnoreCase("Off")) {
                            raw.put(pf, "true");
                        }
                    }
                }
                // Additional members: m{i}_<suffix>
                for (int i = 1; i <= MembershipFormPdfService.MAX_ADDITIONAL_MEMBERS; i++) {
                    Map<String, String> mr = new LinkedHashMap<>();
                    for (String suffix : MembershipFormPdfService.MEMBER_FIELDS) {
                        PDField f = acro.getField("m" + i + "_" + suffix);
                        if (f == null) continue;
                        String v = f.getValueAsString();
                        if (v == null || v.trim().isEmpty()) continue;
                        v = v.trim();
                        if ("address".equals(suffix)) {
                            mr.put("address1", v);
                        } else if ("relationship".equals(suffix)) {
                            mr.put("role", v);
                        } else {
                            mr.put(suffix, v);   // firstName, lastName, otherName, gender, dateOfBirth, phone, email
                        }
                        formVals++;
                    }
                    if (hasName(mr)) rawMembers.add(mr);
                }
            }
            if (formVals > 0) {
                r.method = "form-fields";
            } else {
                String text = new PDFTextStripper().getText(doc);
                parseText(text, raw, rawMembers);
                r.method = (raw.isEmpty() && rawMembers.isEmpty()) ? "none" : "text";
                if (raw.isEmpty() && rawMembers.isEmpty()) {
                    r.note = "No fillable fields or readable text were found. If this is a scanned image, "
                           + "OCR is required — please enter the details manually.";
                }
            }
        } catch (Exception e) {
            r.method = "error";
            r.note = "Could not read the file: " + e.getMessage();
            return r;
        }

        r.fields = normalize(raw);
        for (Map<String, String> mr : rawMembers) {
            Map<String, String> nm = normalize(mr);
            if (!nm.isEmpty()) r.additionalMembers.add(nm);
        }
        return r;
    }

    /**
     * Build a Result from fields extracted by the vision model (a scanned/photographed
     * membership form). Reuses the same normalization (gender, split dates) as the
     * AcroForm/text path so the response shape is identical.
     */
    public Result fromVisionFields(Map<String, String> primary, List<Map<String, String>> members) {
        Result r = new Result();
        r.method = "vision";
        if (primary != null) r.fields = normalize(new LinkedHashMap<>(primary));
        if (members != null) for (Map<String, String> m : members) {
            Map<String, String> nm = normalize(new LinkedHashMap<>(m));
            if (!nm.isEmpty()) r.additionalMembers.add(nm);
        }
        return r;
    }

    /**
     * Parse the PDF text layer. Everything before the first "Additional Member"
     * heading goes to the primary member; each subsequent heading starts a new
     * additional-member record.
     */
    private void parseText(String text, Map<String, String> raw, List<Map<String, String>> members) {
        if (text == null) return;
        Map<String, String> current = null;   // null = still in the primary section
        for (String line : text.replace("\r", "").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().matches("additional member\\s*\\d+.*")) {
                current = new LinkedHashMap<>();
                members.add(current);
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String label = line.substring(0, idx).trim().toLowerCase().replaceAll("\\s+", " ");
            String value = line.substring(idx + 1).trim();
            if (value.isEmpty()) continue;
            if (current == null) {
                String key = LABELS.get(label);
                if (key != null && !raw.containsKey(key)) raw.put(key, value);
            } else {
                String key = MEMBER_LABELS.get(label);
                if (key != null && !current.containsKey(key)) current.put(key, value);
            }
        }
        // drop any heading-only member blocks that captured nothing
        members.removeIf(m -> !hasName(m) && m.isEmpty());
    }

    private boolean hasName(Map<String, String> m) {
        return (m.get("firstName") != null && !m.get("firstName").isBlank())
            || (m.get("lastName")  != null && !m.get("lastName").isBlank());
    }

    private Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey(), v = e.getValue().trim();
            if (v.isEmpty()) continue;
            if ("gender".equals(k)) v = normGender(v);
            else if ("role".equals(k)) v = normRole(v);
            if (!v.isEmpty()) out.put(k, v);
        }
        splitDate(out.remove("dateOfBirth"), out, "birthday");
        splitDate(out.remove("weddingAnniversary"), out, "anniversary");
        return out;
    }

    private void splitDate(String v, Map<String, String> out, String prefix) {
        if (v == null) return;
        Matcher m = Pattern.compile("(\\d{1,2})\\D+(\\d{1,2})\\D+(\\d{2,4})").matcher(v);
        if (m.find()) {
            String mo = m.group(1), da = m.group(2), yr = m.group(3);
            if (yr.length() == 2) yr = (Integer.parseInt(yr) > 30 ? "19" : "20") + yr;
            out.put(prefix + "Month", String.valueOf(Integer.parseInt(mo)));
            out.put(prefix + "Day",   String.valueOf(Integer.parseInt(da)));
            out.put(prefix + "Year",  yr);
        }
    }

    private String normGender(String v) { String l = v.toLowerCase(); if (l.startsWith("m")) return "Male"; if (l.startsWith("f")) return "Female"; return ""; }

    /** Map a free-text relationship to the supported roles (Spouse / Adult / Child); keep as-is if unrecognized. */
    private String normRole(String v) {
        String l = v.toLowerCase().trim();
        if (l.startsWith("spouse") || l.startsWith("wife") || l.startsWith("husband") || l.startsWith("partner")) return "Spouse";
        if (l.startsWith("child") || l.startsWith("son") || l.startsWith("daughter") || l.startsWith("kid") || l.startsWith("minor")) return "Child";
        if (l.startsWith("adult")) return "Adult";
        return v.trim();
    }

    /** Extraction result returned to the UI. */
    public static class Result {
        public Map<String, String> fields = new LinkedHashMap<>();
        public List<Map<String, String>> additionalMembers = new ArrayList<>();
        public String method = "none";  // form-fields | text | none | error
        public String note = "";
    }
}

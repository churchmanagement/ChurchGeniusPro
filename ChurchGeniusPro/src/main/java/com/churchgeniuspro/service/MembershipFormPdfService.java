package com.churchgeniuspro.service;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates a clean, printable AND fillable (AcroForm) membership registration form
 * PDF that fills a single A4 page. Personal information is grouped into rows; each
 * additional family member spans two rows; a declaration + signature close the form.
 * The overlaid AcroForm fields (named to match the Add-Family member fields) let a
 * form filled on a computer be re-uploaded and read back exactly
 * ({@link MembershipFormExtractor}).
 */
@Service
public class MembershipFormPdfService {

    private static final float LEFT = 45f, RIGHT = 550f, TOP = 805f, BOTTOM = 40f;  // A4
    private static final float COL_GAP = 18f;     // gap between the two main columns
    private static final float COL_GAP_N = 10f;   // gap in multi-column rows
    private static final float ROWH = 26f;        // vertical advance per field row
    private static final float BRAND = 0.40f;     // gray-ish brand tone for rules

    private static final String DEFAULT_DECLARATION =
        "I certify that the information provided on this form is true and accurate to the best of my "
      + "knowledge. I agree to abide by the values and guidelines of the church, and I consent to the "
      + "church storing this information for membership and communication purposes.";

    /** Primary-member field names (match FamilyMember keys / mapped by the extractor). */
    public static final String[] FIELD_NAMES = {
        "firstName","lastName","otherName","gender","dateOfBirth",
        "maritalStatus","weddingAnniversary","email","phone",
        "address1","address2","city","state","pinCode","country"
    };

    /** Privacy checkbox field names (AcroForm checkboxes; read back by the extractor). */
    public static final String[] PRIVACY_FIELDS = { "phonePrivate", "emailPrivate", "addressPrivate" };

    /** How many additional household members the form provides space for. */
    public static final int MAX_ADDITIONAL_MEMBERS = 5;

    /** Per-additional-member field suffixes. AcroForm field names are {@code m{i}_<suffix>}. */
    public static final String[] MEMBER_FIELDS = {
        "firstName","lastName","relationship","otherName","gender","dateOfBirth","phone","email","address"
    };

    public byte[] generate(String churchName, byte[] logo, String logoContentType, String declarationText) {
        try (PDDocument doc = new PDDocument()) {
            PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDAcroForm acro = null;
            try {
                acro = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acro);
                PDResources dr = new PDResources();
                dr.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), normal);
                acro.setDefaultResources(dr);
                acro.setDefaultAppearance("/Helv 9 Tf 0 g");
                acro.setNeedAppearances(true);
            } catch (Exception ignore) { acro = null; }

            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = TOP;

            // ── Header ──
            if (logo != null && logo.length > 0) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo, "logo");
                    float h = 44f, w = h * img.getWidth() / Math.max(1f, img.getHeight());
                    if (w > 110f) { w = 110f; h = w * img.getHeight() / Math.max(1f, img.getWidth()); }
                    cs.drawImage(img, LEFT, y - h + 8, w, h);
                } catch (Exception ignore) { }
            }
            String cn = (churchName == null || churchName.isBlank()) ? "Our Church" : churchName.trim();
            centered(cs, bold, 18f, cn, y - 4);
            centered(cs, normal, 11f, "Membership Registration Form", y - 22);
            y -= 40;
            rule(cs, LEFT, RIGHT, y);
            y -= 16;

            // ── Personal Information ──
            y = sectionTitle(cs, bold, "Personal Information", y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "First Name", "Last Name", "Other Name" },
                    new String[]{ "firstName", "lastName", "otherName" }, y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "Gender", "Marital Status", "DOB (MM/DD/YYYY)", "Anniversary (MM/DD/YYYY)" },
                    new String[]{ "gender", "maritalStatus", "dateOfBirth", "weddingAnniversary" }, y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "Email", "Phone" },
                    new String[]{ "email", "phone" }, y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "Address 1", "Address 2" },
                    new String[]{ "address1", "address2" }, y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "City", "State", "Zip Code" },
                    new String[]{ "city", "state", "pinCode" }, y);

            // ── Privacy (tick to hide a field from non-staff members) ──
            y = privacyRow(cs, doc, acro, page, normal, y);

            // ── Family Members (two rows each) ──
            y -= 8;
            y = sectionTitle(cs, bold, "Family Members  (Relationship: Adult / Child;  Address only if different from above)", y);
            for (int i = 1; i <= MAX_ADDITIONAL_MEMBERS; i++) {
                y = memberBlock(cs, doc, acro, page, normal, bold, i, y);
            }

            // ── Declaration (auto-shrinks for long text so it always stays on the page) ──
            String decl = (declarationText != null && !declarationText.isBlank()) ? declarationText.trim() : DEFAULT_DECLARATION;
            y -= 8;
            // Smaller "Declaration" heading (matches form body, conserves space).
            text(cs, bold, 9.5f, "Declaration", LEFT, y);
            y -= 15;
            float dSize = 9f, dLh = 13f;
            float availForDecl = y - (BOTTOM + 52f);   // reserve space for checkbox + signature
            if (countWrapLines(normal, dSize, decl, RIGHT - LEFT) * dLh > availForDecl) { dSize = 7.5f; dLh = 10.5f; }
            y = wrapText(cs, normal, dSize, decl, LEFT, RIGHT, y, dLh);
            y -= 6;
            // Checkbox + label on a single row, vertically centred on the text.
            float boxSz = dSize;                       // box matches the label font size
            float boxY  = y - 0.18f * dSize;           // align box centre with the text's visual centre
            cs.addRect(LEFT, boxY, boxSz, boxSz); cs.setLineWidth(0.7f); cs.setStrokingColor(0f); cs.stroke();
            text(cs, normal, dSize, "I have read and agree to the above declaration.", LEFT + boxSz + 6, y);
            y -= 20;

            // ── Signature ──
            y -= 6;
            float colW = (RIGHT - LEFT - COL_GAP) / 2f, rx = LEFT + colW + COL_GAP;
            rule(cs, LEFT, LEFT + colW, y);
            rule(cs, rx, RIGHT, y);
            label(cs, normal, 8.5f, BRAND, "Signature", LEFT, y - 12);
            label(cs, normal, 8.5f, BRAND, "Date", rx, y - 12);

            // ── Footer ──
            text(cs, normal, 7.5f, "Please return this completed form to the " + cn + " office. Thank you!", LEFT, BOTTOM);

            cs.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render membership form PDF", e);
        }
    }

    // ── member block: heading + two 4-column rows ──

    private float memberBlock(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page,
                              PDFont normal, PDFont bold, int idx, float y) throws IOException {
        String p = "m" + idx + "_";
        text(cs, bold, 9.5f, "Member " + idx, LEFT, y);
        y -= 16;
        y = fieldRow(cs, doc, acro, page, normal,
                new String[]{ "First Name", "Last Name", "Relationship", "Nickname", "Gender" },
                new String[]{ p + "firstName", p + "lastName", p + "relationship", p + "otherName", p + "gender" }, y);
        y = fieldRow(cs, doc, acro, page, normal,
                new String[]{ "DOB (MM/DD/YYYY)", "Phone", "Email", "Address (if different)" },
                new String[]{ p + "dateOfBirth", p + "phone", p + "email", p + "address" }, y);
        return y - 6;
    }

    // ── drawing helpers ──

    /** N labeled fields spread evenly across the page width; returns the new y. */
    private float fieldRow(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                           String[] labels, String[] names, float y) throws IOException {
        int n = labels.length;
        float gap = COL_GAP_N;
        float w = (RIGHT - LEFT - (n - 1) * gap) / n;
        float x = LEFT;
        for (int i = 0; i < n; i++) { labeledField(cs, doc, acro, page, font, labels[i], names[i], x, w, y); x += w + gap; }
        return y - ROWH;
    }

    private void labeledField(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                              String label, String name, float x, float w, float y) throws IOException {
        label(cs, font, 8f, BRAND, label, x, y);
        float boxBottom = y - 18;
        rule(cs, x, x + w, boxBottom);
        addTextField(acro, page, name, x, boxBottom, w, 15f);
    }

    /**
     * One row of "Make … Private" checkboxes (Phone / Email / Address). Each is a
     * real AcroForm checkbox so a form filled on a computer is read back by
     * {@link MembershipFormExtractor}; a labelled box is also drawn for print.
     */
    private float privacyRow(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page,
                             PDFont font, float y) throws IOException {
        text(cs, font, 8f, "Privacy (optional) — tick a box to hide that detail from non-staff members:", LEFT, y);
        y -= 15;
        float boxSz = 9f;
        float cbBottom = y - 7f;
        String[] labels = { "Make Phone Private", "Make Email Private", "Make Address Private" };
        String[] names  = { "phonePrivate", "emailPrivate", "addressPrivate" };
        float x = LEFT;
        for (int i = 0; i < names.length; i++) {
            // Visible printed box (always drawn so paper forms show a checkbox).
            cs.addRect(x, cbBottom, boxSz, boxSz);
            cs.setLineWidth(0.7f);
            cs.setStrokingColor(0f);
            cs.stroke();
            // Interactive AcroForm checkbox overlaid on the box (X appears when ticked).
            addCheckBox(doc, acro, page, names[i], x, cbBottom, boxSz);
            text(cs, font, 8.5f, labels[i], x + boxSz + 5f, cbBottom + 1.5f);
            float lblW = font.getStringWidth(safe(labels[i])) / 1000f * 8.5f;
            x += boxSz + 5f + lblW + 26f;
        }
        return y - ROWH;
    }

    /**
     * Adds a fillable AcroForm checkbox over the printed box. Export value when
     * ticked is {@code Yes} (read back by {@link MembershipFormExtractor}); the
     * "Yes" appearance draws an X, "Off" is empty.
     */
    private void addCheckBox(PDDocument doc, PDAcroForm acro, PDPage page, String name,
                             float x, float y, float size) {
        if (acro == null || name == null) return;
        try {
            PDCheckBox cb = new PDCheckBox(acro);
            cb.setPartialName(name);

            PDAnnotationWidget widget = cb.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(x, y, size, size));
            widget.setPage(page);
            widget.setPrinted(true);

            COSDictionary nStates = new COSDictionary();
            nStates.setItem(COSName.getPDFName("Off"), checkAppearance(doc, size, false).getCOSObject());
            nStates.setItem(COSName.getPDFName("Yes"), checkAppearance(doc, size, true).getCOSObject());
            PDAppearanceDictionary apDict = new PDAppearanceDictionary();
            apDict.getCOSObject().setItem(COSName.N, nStates);
            widget.setAppearance(apDict);
            widget.getCOSObject().setItem(COSName.AS, COSName.getPDFName("Off"));

            cb.getCOSObject().setItem(COSName.V,  COSName.getPDFName("Off"));
            cb.getCOSObject().setItem(COSName.DV, COSName.getPDFName("Off"));

            page.getAnnotations().add(widget);
            acro.getFields().add(cb);
        } catch (Exception ignore) { /* printed box already drawn */ }
    }

    /** Appearance stream for a checkbox state: empty for Off, an X for Yes. */
    private PDAppearanceStream checkAppearance(PDDocument doc, float size, boolean checked) throws IOException {
        PDAppearanceStream s = new PDAppearanceStream(doc);
        s.setResources(new PDResources());
        s.setBBox(new PDRectangle(size, size));
        try (PDPageContentStream a = new PDPageContentStream(doc, s)) {
            if (checked) {
                a.setLineWidth(1.1f);
                a.setStrokingColor(0f);
                a.moveTo(2f, 2f);          a.lineTo(size - 2f, size - 2f);
                a.moveTo(size - 2f, 2f);   a.lineTo(2f, size - 2f);
                a.stroke();
            }
        }
        return s;
    }

    private float sectionTitle(PDPageContentStream cs, PDFont bold, String t, float y) throws IOException {
        text(cs, bold, 12f, t, LEFT, y);
        return y - 20;
    }

    private void addTextField(PDAcroForm acro, PDPage page, String name, float x, float y, float w, float h) {
        if (acro == null || name == null) return;
        try {
            PDTextField field = new PDTextField(acro);
            field.setPartialName(name);
            field.setDefaultAppearance("/Helv 9 Tf 0 g");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(x, y, w, h));
            widget.setPage(page);
            widget.setPrinted(true);
            page.getAnnotations().add(widget);
            acro.getFields().add(field);
        } catch (Exception ignore) { }
    }

    private void label(PDPageContentStream cs, PDFont font, float size, float gray, String s, float x, float y) throws IOException {
        cs.setNonStrokingColor(gray, gray, gray);
        text(cs, font, size, s, x, y);
        cs.setNonStrokingColor(0f, 0f, 0f);
    }

    private void text(PDPageContentStream cs, PDFont font, float size, String s, float x, float y) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safe(s));
        cs.endText();
    }

    private void centered(PDPageContentStream cs, PDFont font, float size, String s, float y) throws IOException {
        String t = safe(s);
        float w = font.getStringWidth(t) / 1000f * size;
        text(cs, font, size, t, (LEFT + RIGHT - w) / 2f, y);
    }

    private void rule(PDPageContentStream cs, float x1, float x2, float y) throws IOException {
        cs.setLineWidth(0.5f);
        cs.setStrokingColor(0.72f, 0.66f, 0.70f);
        cs.moveTo(x1, y); cs.lineTo(x2, y); cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
    }

    private float wrapText(PDPageContentStream cs, PDFont font, float size, String s,
                           float x1, float x2, float y, float lh) throws IOException {
        float maxW = x2 - x1;
        StringBuilder line = new StringBuilder();
        for (String word : s.replace("\r", "").replace("\n", " \n ").split(" ")) {
            if ("\n".equals(word)) { text(cs, font, size, line.toString(), x1, y); y -= lh; line.setLength(0); continue; }
            String trial = line.length() == 0 ? word : line + " " + word;
            float w = font.getStringWidth(safe(trial)) / 1000f * size;
            if (w > maxW && line.length() > 0) { text(cs, font, size, line.toString(), x1, y); y -= lh; line.setLength(0); line.append(word); }
            else { line.setLength(0); line.append(trial); }
        }
        if (line.length() > 0) { text(cs, font, size, line.toString(), x1, y); y -= lh; }
        return y;
    }

    /** Count how many lines {@link #wrapText} would produce (without drawing). */
    private int countWrapLines(PDFont font, float size, String s, float maxW) {
        int lines = 0;
        StringBuilder line = new StringBuilder();
        for (String word : s.replace("\r", "").replace("\n", " \n ").split(" ")) {
            if ("\n".equals(word)) { lines++; line.setLength(0); continue; }
            String trial = line.length() == 0 ? word : line + " " + word;
            float w;
            try { w = font.getStringWidth(safe(trial)) / 1000f * size; } catch (IOException e) { w = 0; }
            if (w > maxW && line.length() > 0) { lines++; line.setLength(0); line.append(word); }
            else { line.setLength(0); line.append(trial); }
        }
        if (line.length() > 0) lines++;
        return lines;
    }

    /** Replace characters the Standard-14 fonts can't encode. */
    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '’' || c == '‘') b.append('\'');
            else if (c == '“' || c == '”') b.append('"');
            else if (c == '–' || c == '—') b.append('-');
            else if (c >= 32 && c < 127) b.append(c);
            else b.append(' ');
        }
        return b.toString();
    }
}

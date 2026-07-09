package com.churchgeniuspro.service;

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
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Generates a professional, printable AND fillable child-registration form PDF for
 * Kids Ministry, mirroring the Register Child page. Visible labels + lines make it
 * easy to complete on paper; the overlaid AcroForm fields (named to match the child
 * registration fields) let a form filled on a computer be re-uploaded and read back
 * exactly with no OCR ({@link KmChildFormExtractor}).
 */
@Service
public class KmChildFormPdfService {

    private static final float LEFT = 50f, RIGHT = 562f, TOP = 760f, BOTTOM = 56f;
    private static final float COL_GAP = 18f;
    private static final float BRAND = 0.40f;

    /** AcroForm field names = canonical child-registration keys (matched by the extractor). */
    public static final String[] FIELD_NAMES = {
        "firstName","lastName","dob","gender","grade",
        "parentName","parentPhone","parentEmail",
        "emergencyContactName","emergencyContactPhone",
        "allergies","medicalNotes"
    };

    public byte[] generate(String churchName, byte[] logo, String logoContentType) {
        return generate(churchName, logo, logoContentType, null);
    }

    /**
     * Render the form, omitting any section disabled in the tenant's Kids Ministry
     * Setup. A {@code null} setup means "all sections enabled" (back-compat).
     */
    public byte[] generate(String churchName, byte[] logo, String logoContentType,
                           com.churchgeniuspro.hibernate.KmChildSetup setup) {
        boolean classroomOn = setup == null || setup.isClassroomEnabled();
        boolean emergencyOn = setup == null || setup.isEmergencyContactEnabled();
        boolean medicalOn   = setup == null || setup.isMedicalInfoEnabled();
        boolean allergiesOn = setup == null || setup.isAllergiesEnabled();
        boolean medNotesOn  = setup == null || setup.isMedicalNotesEnabled();
        boolean pickupOn    = setup == null || setup.isAuthorizedPickupEnabled();
        try (PDDocument doc = new PDDocument()) {
            PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold   = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDAcroForm acro = null;
            try {
                acro = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acro);
                PDResources dr = new PDResources();
                dr.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), normal);
                acro.setDefaultResources(dr);
                acro.setDefaultAppearance("/Helv 10 Tf 0 g");
                acro.setNeedAppearances(true);
            } catch (Exception ignore) { acro = null; }

            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = TOP;

            if (logo != null && logo.length > 0) {
                try {
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, logo, "logo");
                    float h = 46f, w = h * img.getWidth() / Math.max(1f, img.getHeight());
                    if (w > 110f) { w = 110f; h = w * img.getHeight() / Math.max(1f, img.getWidth()); }
                    cs.drawImage(img, LEFT, y - h + 8, w, h);
                } catch (Exception ignore) { }
            }
            String cn = (churchName == null || churchName.isBlank()) ? "Our Church" : churchName.trim();
            centered(cs, bold, 18f, cn, y - 6);
            centered(cs, normal, 12f, "Children's Ministry — Child Registration Form", y - 24);
            y -= 44;
            rule(cs, LEFT, RIGHT, y);
            y -= 22;

            float colW = (RIGHT - LEFT - COL_GAP) / 2f;
            float rx = LEFT + colW + COL_GAP;

            // ── Up to three children, each with their own fields ──
            for (int i = 1; i <= 3; i++) {
                String sfx = (i == 1) ? "" : String.valueOf(i);   // child 1 keeps the canonical field names (extractor-compatible)
                if (i > 1) y -= 6;
                y = sectionTitle(cs, bold, "Child " + i + " Information", y);
                y = field2(cs, doc, acro, page, normal,
                        "First Name", "firstName" + sfx, LEFT, colW,
                        "Last Name", "lastName" + sfx, rx, colW, y);
                if (classroomOn) {
                    y = field3(cs, doc, acro, page, normal,
                            "Date of Birth (MM/DD/YYYY)", "dob" + sfx,
                            "Gender", "gender" + sfx,
                            "Grade / Classroom", "grade" + sfx, y);
                } else {
                    y = field2(cs, doc, acro, page, normal,
                            "Date of Birth (MM/DD/YYYY)", "dob" + sfx, LEFT, colW,
                            "Gender (Male / Female / Other)", "gender" + sfx, rx, colW, y);
                }
                if (medicalOn && (allergiesOn || medNotesOn)) {
                    if (allergiesOn && medNotesOn) {
                        y = field2(cs, doc, acro, page, normal,
                                "Allergies", "allergies" + sfx, LEFT, colW,
                                "Medical Notes / Special Needs", "medicalNotes" + sfx, rx, colW, y);
                    } else if (allergiesOn) {
                        y = field1(cs, doc, acro, page, normal, "Allergies", "allergies" + sfx, LEFT, RIGHT - LEFT, y);
                    } else {
                        y = field1(cs, doc, acro, page, normal, "Medical Notes / Special Needs", "medicalNotes" + sfx, LEFT, RIGHT - LEFT, y);
                    }
                }
            }

            y -= 6;
            y = sectionTitle(cs, bold, "Parent / Guardian", y);
            y = field2(cs, doc, acro, page, normal, "Parent / Guardian Name", "parentName", LEFT, colW, "Phone", "parentPhone", rx, colW, y);
            y = field1(cs, doc, acro, page, normal, "Email", "parentEmail", LEFT, RIGHT - LEFT, y);

            if (emergencyOn) {
                y -= 6;
                y = sectionTitle(cs, bold, "Emergency Contact", y);
                y = field2(cs, doc, acro, page, normal, "Contact Name", "emergencyContactName", LEFT, colW, "Contact Phone", "emergencyContactPhone", rx, colW, y);
            }

            if (pickupOn) {
                y -= 6;
                y = sectionTitle(cs, bold, "Authorized Pickup", y);
                label(cs, normal, 8.5f, BRAND, "Name", LEFT, y);
                label(cs, normal, 8.5f, BRAND, "Relationship", LEFT + 240, y);
                label(cs, normal, 8.5f, BRAND, "Phone", LEFT + 380, y);
                y -= 6;
                for (int i = 0; i < 3; i++) {
                    rule(cs, LEFT, LEFT + 230, y);
                    rule(cs, LEFT + 240, LEFT + 370, y);
                    rule(cs, LEFT + 380, RIGHT, y);
                    y -= 22;
                }
            }

            cs.close();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render child registration PDF", e);
        }
    }

    // ── drawing helpers (same style as MembershipFormPdfService) ──

    private float sectionTitle(PDPageContentStream cs, PDFont bold, String t, float y) throws IOException {
        text(cs, bold, 11.5f, t, LEFT, y);
        return y - 22;
    }

    private float field2(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                         String l1, String n1, float x1, float w1,
                         String l2, String n2, float x2, float w2, float y) throws IOException {
        labeledField(cs, doc, acro, page, font, l1, n1, x1, w1, y, false);
        labeledField(cs, doc, acro, page, font, l2, n2, x2, w2, y, false);
        return y - 34;
    }

    private float field1(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                         String label, String name, float x, float w, float y) throws IOException {
        labeledField(cs, doc, acro, page, font, label, name, x, w, y, false);
        return y - 34;
    }

    /** Three single-line fields across the full width (e.g. DOB / Gender / Grade). */
    private float field3(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                         String l1, String n1, String l2, String n2, String l3, String n3, float y) throws IOException {
        float w = (RIGHT - LEFT - 2 * COL_GAP) / 3f;
        float x2 = LEFT + w + COL_GAP, x3 = x2 + w + COL_GAP;
        labeledField(cs, doc, acro, page, font, l1, n1, LEFT, w, y, false);
        labeledField(cs, doc, acro, page, font, l2, n2, x2, w, y, false);
        labeledField(cs, doc, acro, page, font, l3, n3, x3, w, y, false);
        return y - 34;
    }

    /** A taller, multi-line text field (e.g. allergies, medical notes). */
    private float area(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                       String label, String name, float x, float w, float h, float y) throws IOException {
        this.label(cs, font, 8.5f, BRAND, label, x, y);
        float boxBottom = y - 16 - (h - 16);
        // two writing lines for paper
        rule(cs, x, x + w, y - 18);
        rule(cs, x, x + w, y - 18 - 16);
        addTextField(acro, page, name, x, boxBottom, w, h, true);
        return y - 16 - h - 8;
    }

    private void labeledField(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                              String label, String name, float x, float w, float y, boolean multiline) throws IOException {
        this.label(cs, font, 8.5f, BRAND, label, x, y);
        float boxBottom = y - 18;
        rule(cs, x, x + w, boxBottom);
        addTextField(acro, page, name, x, boxBottom, w, 16f, multiline);
    }

    private void addTextField(PDAcroForm acro, PDPage page, String name, float x, float y, float w, float h, boolean multiline) {
        if (acro == null) return;
        try {
            PDTextField field = new PDTextField(acro);
            field.setPartialName(name);
            field.setDefaultAppearance("/Helv 10 Tf 0 g");
            if (multiline) field.setMultiline(true);
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
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
    }

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

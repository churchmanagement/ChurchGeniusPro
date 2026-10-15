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
 * Generates a clean, printable AND fillable (AcroForm) membership form PDF that fills
 * a single A4 page. Every field is a writing line with its label just below it. Personal information is grouped into rows; each
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
    private static final float ROWH = 26f;        // vertical advance per field row (line + label below it)
    private static final float BRAND = 0.40f;     // gray-ish brand tone for rules
    static final float CHECK = 10f;               // hand-markable checkbox size (gender, declaration)

    public static final String TITLE = "Membership Form";

    public static final String DEFAULT_DECLARATION =
        "I certify that the information provided is true and accurate to the best of my knowledge. I agree to "
      + "abide by the values and guidelines of the church and consent to the church collecting, storing, and "
      + "using this information for membership and communication purposes. By providing my phone number and "
      + "email address, I consent to receive church updates, reminders, announcements, and other ministry-related "
      + "communications via SMS and email. Message and data rates may apply. Message frequency may vary. "
      + "I understand that I may opt out at any time by replying STOP to text messages or by using the "
      + "Unsubscribe link in emails.";

    /** Primary-member text field names (match FamilyMember keys / mapped by the extractor). */
    public static final String[] FIELD_NAMES = {
        "firstName","lastName","otherName","dateOfBirth",
        "weddingAnniversary","email","phone",
        "address1","address2","city","state","pinCode","country"
    };

    /** Gender is two checkboxes (exactly one ticked → Male / Female; the extractor also reads the legacy text field). */
    public static final String GENDER_MALE = "genderMale", GENDER_FEMALE = "genderFemale";
    /** Declaration acceptance — the box drawn inline before "I certify"; read back by the extractor. */
    public static final String DECLARATION_FIELD = "declarationAccepted";

    /** Privacy checkbox field names (AcroForm checkboxes; read back by the extractor). */
    public static final String[] PRIVACY_FIELDS = { "phonePrivate", "emailPrivate", "addressPrivate" };

    /** How many additional household members the form provides space for. */
    public static final int MAX_ADDITIONAL_MEMBERS = 5;

    /** Per-additional-member field suffixes. AcroForm field names are {@code m{i}_<suffix>}. */
    public static final String[] MEMBER_FIELDS = {
        "firstName","lastName","relationship","otherName","gender","dateOfBirth","phone","email","address"
    };   // "gender" is the legacy text field; new forms carry m{i}_genderMale / m{i}_genderFemale checkboxes

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
            centered(cs, normal, 11f, TITLE, y - 22);
            y -= 40;
            rule(cs, LEFT, RIGHT, y);
            y -= 12;

            // ── Personal Information ──
            y = sectionTitle(cs, bold, "Personal Information", y);
            y = fieldRow(cs, doc, acro, page, normal,
                    new String[]{ "First Name", "Last Name", "Other Name" },
                    new String[]{ "firstName", "lastName", "otherName" }, y);
            {   // Gender as two checkboxes, then DOB and Anniversary (Marital Status is not stored anywhere — removed)
                float gw = 150f, rest = (RIGHT - LEFT - gw - 2 * COL_GAP_N) / 2f;
                genderBoxes(cs, doc, acro, page, normal, "", "Gender", LEFT, y, true);
                labeledField(cs, doc, acro, page, normal, "DOB (MM/DD/YYYY)", "dateOfBirth", LEFT + gw + COL_GAP_N, rest, y);
                labeledField(cs, doc, acro, page, normal, "Anniversary (MM/DD/YYYY)", "weddingAnniversary", LEFT + gw + COL_GAP_N + rest + COL_GAP_N, rest, y);
                y -= ROWH;
            }
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
            y -= AFTER_ROW;
            y = privacyRow(cs, doc, acro, page, normal, y);

            // ── Family Members (two rows each) ──
            y -= 4;
            y = sectionTitle(cs, bold, "Family Members  (Relationship: Adult / Child;  Address only if different from above)", y);
            for (int i = 1; i <= MAX_ADDITIONAL_MEMBERS; i++) {
                y = memberBlock(cs, doc, acro, page, normal, bold, i, y);
            }

            // ── Declaration (auto-shrinks for long text so it always stays on the page) ──
            String decl = normaliseDeclaration((declarationText != null && !declarationText.isBlank()) ? declarationText : DEFAULT_DECLARATION);
            y -= AFTER_ROW + 2;
            text(cs, bold, 9.5f, "Declaration", LEFT, y);
            y -= 15;
            float sigReserve = 12f + 12f + 16f + 10f;              // signature rule + labels + return message + margin
            float availForDecl = y - (BOTTOM + sigReserve);
            // Shrink-to-fit: 9 pt, then 7.5, then 6.5 — the form never grows a second page. A
            // declaration too long even at 6.5 pt is cut at the last line that fits, with an
            // ellipsis, so the signature block always stays on the page.
            float dSize = 9f, dLh = 13f;
            java.util.List<String> lines = wrapLines(normal, dSize, decl, RIGHT - LEFT, CHECK + 6f);
            for (float[] step : new float[][]{ { 7.5f, 10.5f }, { 6.5f, 9f } }) {
                if (lines.size() * dLh <= availForDecl) break;
                dSize = step[0]; dLh = step[1];
                lines = wrapLines(normal, dSize, decl, RIGHT - LEFT, CHECK + 6f);
            }
            int maxLines = Math.max(1, (int) Math.floor(availForDecl / dLh));
            if (lines.size() > maxLines) {
                lines = new java.util.ArrayList<>(lines.subList(0, maxLines));
                lines.set(maxLines - 1, lines.get(maxLines - 1) + " ...");
            }
            // Acceptance box inline before "I certify"; the first line starts after it, later lines wrap from the margin.
            float boxBottom = y - 0.2f * dSize;
            cs.addRect(LEFT, boxBottom, CHECK, CHECK); cs.setLineWidth(0.8f); cs.setStrokingColor(0f); cs.stroke();
            addCheckBox(doc, acro, page, DECLARATION_FIELD, LEFT, boxBottom, CHECK);
            boolean firstLine = true;
            for (String ln : lines) {
                text(cs, normal, dSize, ln, LEFT + (firstLine ? CHECK + 6f : 0f), y);
                firstLine = false;
                y -= dLh;
            }

            // ── Signature / Date ──
            y -= 14;
            float colW = (RIGHT - LEFT - COL_GAP) / 2f, rx = LEFT + colW + COL_GAP;
            rule(cs, LEFT, LEFT + colW, y);
            rule(cs, rx, RIGHT, y);
            label(cs, normal, 8.5f, BRAND, "Signature", LEFT, y - 12);
            label(cs, normal, 8.5f, BRAND, "Date", rx, y - 12);

            // ── Return message — always BELOW the signature labels, never through them ──
            float msgY = Math.max(BOTTOM, y - 12 - 16);
            text(cs, normal, 7.5f, "Please return this completed form to the " + cn + " office. Thank you!", LEFT, msgY);

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
        y -= AFTER_ROW;
        text(cs, bold, 9.5f, "Member " + idx, LEFT, y);
        y -= 16;
        {   // First / Last / Relationship / Nickname take the text columns; Gender is M ☐ F ☐ at the end
            float gw = 78f, w = (RIGHT - LEFT - gw - 4 * COL_GAP_N) / 4f, x = LEFT;
            String[] labels = { "First Name", "Last Name", "Relationship", "Nickname" };
            String[] names  = { p + "firstName", p + "lastName", p + "relationship", p + "otherName" };
            for (int i = 0; i < 4; i++) { labeledField(cs, doc, acro, page, normal, labels[i], names[i], x, w, y); x += w + COL_GAP_N; }
            genderBoxes(cs, doc, acro, page, normal, p, "Gender", x, y, false);
            y -= ROWH;
        }
        // DOB and Phone need less room than Email and Address: 85 / 95 / rest split evenly
        y = fieldRow(cs, doc, acro, page, normal,
                new String[]{ "DOB (MM/DD/YYYY)", "Phone", "Email", "Address (if different)" },
                new String[]{ p + "dateOfBirth", p + "phone", p + "email", p + "address" },
                new float[]{ 85f, 95f, -1f, -1f }, y);
        return y - 2;
    }

    // ── drawing helpers ──

    /** N labeled fields spread evenly across the page width; returns the new y. */
    private float fieldRow(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                           String[] labels, String[] names, float y) throws IOException {
        float[] w = new float[labels.length];
        java.util.Arrays.fill(w, -1f);
        return fieldRow(cs, doc, acro, page, font, labels, names, w, y);
    }

    /**
     * N labeled fields; {@code widths[i] > 0} fixes that column's width in points, {@code -1}
     * shares what is left equally. Same gap and baseline as the even split.
     */
    private float fieldRow(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                           String[] labels, String[] names, float[] widths, float y) throws IOException {
        int n = labels.length, flex = 0;
        float fixed = 0;
        for (float w : widths) { if (w > 0) fixed += w; else flex++; }
        float share = flex == 0 ? 0 : (RIGHT - LEFT - (n - 1) * COL_GAP_N - fixed) / flex;
        float x = LEFT;
        for (int i = 0; i < n; i++) {
            float w = widths[i] > 0 ? widths[i] : share;
            labeledField(cs, doc, acro, page, font, labels[i], names[i], x, w, y);
            x += w + COL_GAP_N;
        }
        return y - ROWH;
    }

    /** Field layout: the writing line first, the label just below it (8 pt, grey). */
    static final float LINE_DROP = 11f, LABEL_DROP = 20f, FIELD_H = 12f;
    /** Extra space before a heading or sentence that follows a field row (its label sits low). */
    static final float AFTER_ROW = 8f;

    private void labeledField(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                              String label, String name, float x, float w, float y) throws IOException {
        float lineY = y - LINE_DROP;
        rule(cs, x, x + w, lineY);
        label(cs, font, 8f, BRAND, label, x, y - LABEL_DROP);
        addTextField(acro, page, name, x, lineY, w, FIELD_H);
    }

    /**
     * Gender as two hand-markable checkboxes on the field line: "☐ MALE  ☐ FEMALE" (primary)
     * or "M ☐ F ☐" (family rows), with the label below like every other field. AcroForm names
     * {@code <prefix>genderMale} / {@code <prefix>genderFemale}.
     */
    private void genderBoxes(PDPageContentStream cs, PDDocument doc, PDAcroForm acro, PDPage page, PDFont font,
                             String prefix, String label, float x, float y, boolean longWords) throws IOException {
        float lineY = y - LINE_DROP;
        float boxBottom = lineY;        // the box sits on the writing line, clear of the label below
        String[] words = longWords ? new String[]{ "MALE", "FEMALE" } : new String[]{ "M", "F" };
        String[] names = { prefix + GENDER_MALE, prefix + GENDER_FEMALE };
        float cx = x;
        for (int i = 0; i < 2; i++) {
            cs.addRect(cx, boxBottom, CHECK, CHECK); cs.setLineWidth(0.8f); cs.setStrokingColor(0f); cs.stroke();
            addCheckBox(doc, acro, page, names[i], cx, boxBottom, CHECK);
            text(cs, font, 9f, words[i], cx + CHECK + 4f, boxBottom + 2f);
            cx += CHECK + 4f + font.getStringWidth(words[i]) / 1000f * 9f + (longWords ? 16f : 10f);
        }
        label(cs, font, 8f, BRAND, label, x, y - LABEL_DROP);
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
        return y - 22f;
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
        return y - 12;
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

    /**
     * Declaration text as typed in Public Screens may carry a line break at the end of
     * every line; those must not become short lines in the PDF. Single breaks → spaces,
     * a blank line stays a paragraph break.
     */
    public static String normaliseDeclaration(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "").trim();
        t = t.replaceAll("\\n[ \\t]*\\n+", "\u0001");      // paragraph break
        t = t.replaceAll("[ \\t]*\\n[ \\t]*", " ");         // single break → space
        t = t.replaceAll("[ \\t]{2,}", " ");
        return t.replace("\u0001", "\n").trim();
    }

    /** The wrapped lines; the first line's width budget is reduced by {@code firstIndent}. */
    private java.util.List<String> wrapLines(PDFont font, float size, String s, float maxW, float firstIndent) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.replace("\r", "").replace("\n", " \n ").split(" ")) {
            if ("\n".equals(word)) { out.add(line.toString()); line.setLength(0); continue; }
            if (word.isEmpty()) continue;
            String trial = line.length() == 0 ? word : line + " " + word;
            float budget = out.isEmpty() ? maxW - firstIndent : maxW;
            float w;
            try { w = font.getStringWidth(safe(trial)) / 1000f * size; } catch (IOException e) { w = 0; }
            if (w > budget && line.length() > 0) { out.add(line.toString()); line.setLength(0); line.append(word); }
            else { line.setLength(0); line.append(trial); }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
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

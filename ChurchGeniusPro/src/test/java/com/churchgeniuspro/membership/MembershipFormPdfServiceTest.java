package com.churchgeniuspro.membership;

import com.churchgeniuspro.service.MembershipFormPdfService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The printable / fillable membership form — layout contract after the 2026-10-14 corrections. */
@DisplayName("Membership Form PDF — labels below lines, gender boxes, inline declaration box, one page")
class MembershipFormPdfServiceTest {

    static final float LEFT = 45f, RIGHT = 550f, BOTTOM = 40f, PAGE_H = 841.89f;
    static byte[] pdf;
    static PDDocument doc;
    static PDAcroForm acro;
    static List<Word> words;

    /** One text run with its PDF-space baseline (y up) and left x. */
    record Word(String text, float x, float y, float width) {}

    @BeforeAll
    static void render() throws IOException {
        pdf = new MembershipFormPdfService().generate("Sharon Fellowship Church of Kansas", null, null, null);
        doc = Loader.loadPDF(pdf);
        acro = doc.getDocumentCatalog().getAcroForm();
        words = extractWords(doc);
    }

    static List<Word> extractWords(PDDocument d) throws IOException {
        List<Word> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override protected void writeString(String text, List<TextPosition> tps) {
                TextPosition a = tps.get(0), z = tps.get(tps.size() - 1);
                float y = PAGE_H - a.getYDirAdj();                       // stripper y is top-down; convert to PDF space
                out.add(new Word(text, a.getXDirAdj(), y, z.getXDirAdj() + z.getWidthDirAdj() - a.getXDirAdj()));
            }
        };
        s.setSortByPosition(true);
        s.getText(d);
        return out;
    }

    static Word word(String startsWith) {
        return words.stream().filter(w -> w.text().startsWith(startsWith)).findFirst()
                .orElseThrow(() -> new AssertionError("text not found: " + startsWith));
    }
    static Word wordAfter(String startsWith, float belowY) {
        return words.stream().filter(w -> w.text().startsWith(startsWith) && w.y() < belowY).findFirst()
                .orElseThrow(() -> new AssertionError("text not found below " + belowY + ": " + startsWith));
    }
    static PDRectangle rect(String field) {
        PDField f = acro.getField(field);
        assertThat(f).as("AcroForm field " + field).isNotNull();
        return f.getWidgets().get(0).getRectangle();
    }

    @Test
    @DisplayName("one A4 page; heading is 'Membership Form'; the old heading is gone")
    void headingAndPages() {
        assertThat(doc.getNumberOfPages()).isEqualTo(1);
        assertThat(words).anyMatch(w -> w.text().equals("Membership Form"));
        assertThat(words).noneMatch(w -> w.text().contains("Registration"));
    }

    @Test
    @DisplayName("First Name / Last Name / Other Name: the label sits BELOW its writing line (field widget), for the primary and every member")
    void labelsBelowLines() {
        String[][] pairs = { { "First Name", "firstName" }, { "Last Name", "lastName" }, { "Other Name", "otherName" } };
        for (String[] p : pairs) {
            PDRectangle r = rect(p[1]);
            Word label = word(p[0]);
            assertThat(label.y()).as(p[0] + " label below its line").isLessThan(r.getLowerLeftY());
            assertThat(Math.abs(label.x() - r.getLowerLeftX())).as(p[0] + " label aligned with its field").isLessThan(1.5f);
        }
        for (int i = 1; i <= MembershipFormPdfService.MAX_ADDITIONAL_MEMBERS; i++) {
            PDRectangle r = rect("m" + i + "_firstName");
            Word label = wordAfter("First Name", r.getUpperRightY());
            assertThat(label.y()).as("member " + i + " First Name label below its line").isLessThan(r.getLowerLeftY());
            assertThat(r.getLowerLeftY() - label.y()).as("…and close to it").isLessThan(14f);
        }
        // Every text field's label is below the field: no label is drawn above any field line any more.
        for (PDField f : acro.getFields()) {
            if (!(f instanceof PDTextField)) continue;
            PDRectangle r = f.getWidgets().get(0).getRectangle();
            boolean labelJustAbove = words.stream().anyMatch(w -> Math.abs(w.x() - r.getLowerLeftX()) < 1.5f
                    && w.y() > r.getUpperRightY() && w.y() - r.getUpperRightY() < 4f && Character.isUpperCase(w.text().charAt(0)));   // the previous row's label sits ≥ 5 pt above
            assertThat(labelJustAbove).as(f.getPartialName() + " must not have a label above its line").isFalse();
        }
    }

    @Test
    @DisplayName("gender is two checkboxes (MALE / FEMALE for the primary, M / F per member); no gender or marital-status text field")
    void genderBoxes() {
        assertThat(acro.getField(MembershipFormPdfService.GENDER_MALE)).isInstanceOf(PDCheckBox.class);
        assertThat(acro.getField(MembershipFormPdfService.GENDER_FEMALE)).isInstanceOf(PDCheckBox.class);
        assertThat(acro.getField("gender")).isNull();
        assertThat(acro.getField("maritalStatus")).isNull();
        assertThat(words).noneMatch(w -> w.text().contains("Marital"));
        assertThat(words).anyMatch(w -> w.text().startsWith("MALE")).anyMatch(w -> w.text().startsWith("FEMALE"));
        for (int i = 1; i <= MembershipFormPdfService.MAX_ADDITIONAL_MEMBERS; i++) {
            assertThat(acro.getField("m" + i + "_" + MembershipFormPdfService.GENDER_MALE)).isInstanceOf(PDCheckBox.class);
            assertThat(acro.getField("m" + i + "_" + MembershipFormPdfService.GENDER_FEMALE)).isInstanceOf(PDCheckBox.class);
            assertThat(acro.getField("m" + i + "_gender")).isNull();
            PDRectangle r = rect("m" + i + "_" + MembershipFormPdfService.GENDER_MALE);
            assertThat(r.getWidth()).as("hand-markable size").isGreaterThanOrEqualTo(10f);
        }
        // the boxes are inside the page and right of the Nickname column
        assertThat(rect("m1_" + MembershipFormPdfService.GENDER_FEMALE).getUpperRightX()).isLessThanOrEqualTo(RIGHT);
        assertThat(rect("m1_" + MembershipFormPdfService.GENDER_MALE).getLowerLeftX()).isGreaterThan(rect("m1_otherName").getUpperRightX());
    }

    @Test
    @DisplayName("member rows: DOB and Phone are narrower than Email and Address; all five blocks identical")
    void memberWidths() {
        float dob = -1, phone = -1, email = -1, addr = -1;
        for (int i = 1; i <= MembershipFormPdfService.MAX_ADDITIONAL_MEMBERS; i++) {
            String p = "m" + i + "_";
            float d = rect(p + "dateOfBirth").getWidth(), ph = rect(p + "phone").getWidth(), e = rect(p + "email").getWidth(), a = rect(p + "address").getWidth();
            assertThat(d).isLessThan(e).isLessThan(a).isGreaterThanOrEqualTo(80f);
            assertThat(ph).isLessThan(e).isLessThan(a).isGreaterThanOrEqualTo(90f);
            if (i == 1) { dob = d; phone = ph; email = e; addr = a; }
            else { assertThat(d).isEqualTo(dob); assertThat(ph).isEqualTo(phone); assertThat(e).isEqualTo(email); assertThat(a).isEqualTo(addr); }
            assertThat(rect(p + "address").getUpperRightX()).isLessThanOrEqualTo(RIGHT + 0.01f);
        }
    }

    @Test
    @DisplayName("declaration: default is the approved wording, the acceptance box is inline before 'I certify', later lines wrap from the margin, the old separate line is gone")
    void declaration() {
        assertThat(MembershipFormPdfService.DEFAULT_DECLARATION).startsWith("I certify that the information provided is true and accurate")
                .contains("replying STOP to text messages or by using the Unsubscribe link in emails.");
        PDRectangle box = rect(MembershipFormPdfService.DECLARATION_FIELD);
        assertThat(acro.getField(MembershipFormPdfService.DECLARATION_FIELD)).isInstanceOf(PDCheckBox.class);
        assertThat(box.getWidth()).isGreaterThanOrEqualTo(10f);
        assertThat(box.getLowerLeftX()).isEqualTo(LEFT);
        Word first = word("I certify");
        assertThat(first.x()).as("first line starts after the box").isGreaterThan(box.getUpperRightX());
        assertThat(Math.abs(first.y() - (box.getLowerLeftY() + 2f))).as("box on the first line").isLessThan(6f);
        // the next line of the declaration starts at the left margin and the text reaches the right side
        List<Word> declLines = words.stream().filter(w -> w.y() < first.y() && w.y() > first.y() - 60 && w.x() < LEFT + 1).toList();
        assertThat(declLines).isNotEmpty();
        float widest = words.stream().filter(w -> w.y() <= first.y() && w.y() > first.y() - 60).map(w -> w.x() + w.width()).max(Float::compare).orElse(0f);
        assertThat(widest).as("uses the available width").isGreaterThan(RIGHT - 40f);
        assertThat(words).noneMatch(w -> w.text().contains("I have read and agree"));
    }

    @Test
    @DisplayName("Signature and Date sit above the return message; the message is the last line and inside the page")
    void signatureAndReturnMessage() {
        Word sig = word("Signature"), date = word("Date"), msg = word("Please return this completed form");
        assertThat(msg.y()).isLessThan(sig.y() - 8f).isLessThan(date.y() - 8f);
        assertThat(msg.y()).isGreaterThanOrEqualTo(BOTTOM - 0.5f);
        assertThat(msg.text()).contains("Sharon Fellowship Church of Kansas office. Thank you!");
        assertThat(words.stream().map(Word::y).min(Float::compare).orElse(0f)).as("nothing below the bottom margin").isGreaterThanOrEqualTo(BOTTOM - 0.5f);
        assertThat(words.stream().map(w -> w.x() + w.width()).max(Float::compare).orElse(0f)).isLessThanOrEqualTo(RIGHT + 1f);
    }

    @Test
    @DisplayName("line breaks typed into the declaration collapse to spaces (paragraph breaks kept) and a very long declaration still fits on one page")
    void normalisationAndLongText() throws IOException {
        assertThat(MembershipFormPdfService.normaliseDeclaration("I certify\nthat this\n  is one line.\n\nSecond paragraph.\r\n"))
                .isEqualTo("I certify that this is one line.\nSecond paragraph.");
        String longDecl = (MembershipFormPdfService.DEFAULT_DECLARATION + " ").repeat(3);
        try (PDDocument d = Loader.loadPDF(new MembershipFormPdfService().generate("Church", null, null, longDecl))) {
            assertThat(d.getNumberOfPages()).isEqualTo(1);
            List<Word> ws = extractWords(d);
            Word sig = ws.stream().filter(w -> w.text().startsWith("Signature")).findFirst().orElseThrow();
            Word msg = ws.stream().filter(w -> w.text().startsWith("Please return")).findFirst().orElseThrow();
            assertThat(msg.y()).isLessThan(sig.y() - 8f).isGreaterThanOrEqualTo(BOTTOM - 0.5f);
            assertThat(ws.stream().map(Word::y).min(Float::compare).orElse(0f)).isGreaterThanOrEqualTo(BOTTOM - 0.5f);
        }
    }
}

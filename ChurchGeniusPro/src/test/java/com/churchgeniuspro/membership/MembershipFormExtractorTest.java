package com.churchgeniuspro.membership;

import com.churchgeniuspro.service.MembershipFormExtractor;
import com.churchgeniuspro.service.MembershipFormPdfService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a filled membership form back: gender from the new checkboxes, legacy text gender, declaration box. */
@DisplayName("MembershipFormExtractor — gender checkboxes, legacy PDFs, declaration acceptance")
class MembershipFormExtractorTest {

    final MembershipFormExtractor extractor = new MembershipFormExtractor();

    /** Fill fields on the generated form (text or checkbox) and return the bytes. */
    static byte[] filled(Map<String, String> values) throws IOException {
        try (PDDocument doc = Loader.loadPDF(new MembershipFormPdfService().generate("Church", null, null, null))) {
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            for (Map.Entry<String, String> e : values.entrySet()) {
                var f = acro.getField(e.getKey());
                assertThat(f).as(e.getKey()).isNotNull();
                if (f instanceof PDCheckBox cb) { if ("Yes".equals(e.getValue())) cb.check(); else cb.unCheck(); }
                else f.setValue(e.getValue());
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream(); doc.save(b); return b.toByteArray();
        }
    }

    MembershipFormExtractor.Result extract(byte[] bytes) { return extractor.extract(bytes, "form.pdf", "application/pdf"); }

    @Test
    @DisplayName("exactly one gender box ticked → Male / Female; neither or both → no gender")
    void genderBoxes() throws IOException {
        var r = extract(filled(Map.of("firstName", "Ada", "lastName", "L", MembershipFormPdfService.GENDER_FEMALE, "Yes")));
        assertThat(r.method).isEqualTo("form-fields");
        assertThat(r.fields).containsEntry("firstName", "Ada").containsEntry("gender", "Female");

        r = extract(filled(Map.of("firstName", "Bob", MembershipFormPdfService.GENDER_MALE, "Yes")));
        assertThat(r.fields).containsEntry("gender", "Male");

        r = extract(filled(Map.of("firstName", "Cy", MembershipFormPdfService.GENDER_MALE, "Yes", MembershipFormPdfService.GENDER_FEMALE, "Yes")));
        assertThat(r.fields).as("both ticked → blank").doesNotContainKey("gender");

        r = extract(filled(Map.of("firstName", "Di")));
        assertThat(r.fields).as("neither ticked → blank").doesNotContainKey("gender");
    }

    @Test
    @DisplayName("family-member M / F boxes map the same way, per member")
    void memberGender() throws IOException {
        var r = extract(filled(Map.of("firstName", "Ada", "lastName", "L",
                "m1_firstName", "Kid", "m1_lastName", "L", "m1_" + MembershipFormPdfService.GENDER_MALE, "Yes",
                "m2_firstName", "Two", "m2_" + MembershipFormPdfService.GENDER_FEMALE, "Yes", "m2_" + MembershipFormPdfService.GENDER_MALE, "Yes",
                "m3_firstName", "Three")));
        assertThat(r.additionalMembers).hasSize(3);
        assertThat(r.additionalMembers.get(0)).containsEntry("firstName", "Kid").containsEntry("gender", "Male");
        assertThat(r.additionalMembers.get(1)).doesNotContainKey("gender");
        assertThat(r.additionalMembers.get(2)).doesNotContainKey("gender");
    }

    @Test
    @DisplayName("declaration acceptance box is read back as declarationAccepted=true (absent when unticked)")
    void declaration() throws IOException {
        var r = extract(filled(Map.of("firstName", "Ada", MembershipFormPdfService.DECLARATION_FIELD, "Yes")));
        assertThat(r.fields).containsEntry("declarationAccepted", "true");
        r = extract(filled(Map.of("firstName", "Ada")));
        assertThat(r.fields).doesNotContainKey("declarationAccepted");
    }

    @Test
    @DisplayName("legacy PDF (text 'gender' / 'm1_gender' fields, no checkboxes) still reads: f → Female, Male → Male")
    void legacyTextGender() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page);
            PDAcroForm acro = new PDAcroForm(doc); doc.getDocumentCatalog().setAcroForm(acro);
            PDResources dr = new PDResources(); dr.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            acro.setDefaultResources(dr); acro.setDefaultAppearance("/Helv 9 Tf 0 g"); acro.setNeedAppearances(true);
            String[][] fields = { { "firstName", "Old" }, { "lastName", "Form" }, { "gender", "f" }, { "maritalStatus", "Married" },
                                  { "m1_firstName", "Son" }, { "m1_gender", "Male" } };
            float y = 700;
            for (String[] f : fields) {
                PDTextField t = new PDTextField(acro); t.setPartialName(f[0]); t.setDefaultAppearance("/Helv 9 Tf 0 g");
                PDAnnotationWidget w = t.getWidgets().get(0); w.setRectangle(new PDRectangle(50, y, 200, 14)); w.setPage(page);
                page.getAnnotations().add(w); acro.getFields().add(t); t.setValue(f[1]); y -= 20;
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream(); doc.save(b);
            var r = extract(b.toByteArray());
            assertThat(r.method).isEqualTo("form-fields");
            assertThat(r.fields).containsEntry("firstName", "Old").containsEntry("gender", "Female").doesNotContainKey("maritalStatus");
            assertThat(r.additionalMembers.get(0)).containsEntry("gender", "Male");
        }
    }
}

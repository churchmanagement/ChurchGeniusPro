package com.churchgeniuspro.payroll.pdf;

import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.service.W2Box;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * Renders a one-page <b>W-2 wage &amp; tax summary</b> PDF for an employee from
 * the aggregated {@link W2Box} values, using Apache PDFBox. This is a readable
 * reference layout of the W-2 boxes — <em>not</em> the official IRS red-ink Copy
 * A form (which must be filed via SSA-approved channels). Use it for employee
 * copies / internal review; file the official forms through your tax software.
 */
@Service
public class W2PdfService {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final float LEFT = 50f, RIGHT = 562f, TOP = 742f;

    public byte[] generate(W2Box w2, EmployerInfo employer, PayrollEmployee employee) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = TOP;

                text(cs, bold, 15, LEFT, y, "Form W-2 — Wage and Tax Statement");
                rightText(cs, bold, 13, RIGHT, y, "Tax year " + w2.getTaxYear());
                y -= 16;
                text(cs, normal, 9, LEFT, y, "Summary for reference — not the official IRS Copy A form.");
                y -= 14;
                hr(cs, y); y -= 16;

                // Employer + employee blocks
                String empName = employer != null && employer.getName() != null ? employer.getName() : "Employer";
                text(cs, bold, 11, LEFT, y, "Employer: " + empName);
                y -= 14;
                if (employer != null && employer.getEin() != null) { text(cs, normal, 9, LEFT, y, "EIN: " + employer.getEin()); y -= 12; }
                String who = w2.getEmployeeName() == null ? "" : w2.getEmployeeName();
                text(cs, bold, 11, LEFT, y, "Employee: " + who);
                y -= 14;
                if (employee != null && employee.getSsnLast4() != null && !employee.getSsnLast4().isBlank()) {
                    text(cs, normal, 9, LEFT, y, "SSN: XXX-XX-" + employee.getSsnLast4()); y -= 12;
                }
                y -= 6; hr(cs, y); y -= 20;

                // Box grid (two columns)
                float colL = LEFT, colR = 310f, boxW = 240f;
                y = boxRow(cs, normal, bold, colL, colR, boxW, y, "1  Wages, tips, other comp.", w2.getBox1WagesTipsOtherComp(),
                        "2  Federal income tax withheld", w2.getBox2FederalIncomeTax());
                y = boxRow(cs, normal, bold, colL, colR, boxW, y, "3  Social Security wages", w2.getBox3SocialSecurityWages(),
                        "4  Social Security tax withheld", w2.getBox4SocialSecurityTax());
                y = boxRow(cs, normal, bold, colL, colR, boxW, y, "5  Medicare wages and tips", w2.getBox5MedicareWages(),
                        "6  Medicare tax withheld", w2.getBox6MedicareTax());
                y = boxRow(cs, normal, bold, colL, colR, boxW, y, "16  State wages, tips, etc.", w2.getBox16StateWages(),
                        "17  State income tax", w2.getBox17StateIncomeTax());
                y = boxRow(cs, normal, bold, colL, colR, boxW, y, "18  Local wages, tips, etc.", w2.getBox18LocalWages(),
                        "19  Local income tax", w2.getBox19LocalIncomeTax());

                y -= 10;
                text(cs, normal, 8, LEFT, y,
                        "Box 6 includes Additional Medicare Tax where applicable. Figures aggregate this year's non-voided paystubs.");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render W-2 PDF", e);
        }
    }

    private float boxRow(PDPageContentStream cs, PDFont normal, PDFont bold,
                         float colL, float colR, float boxW, float y,
                         String leftLabel, BigDecimal leftVal,
                         String rightLabel, BigDecimal rightVal) throws IOException {
        box(cs, normal, bold, colL, y, boxW, leftLabel, leftVal);
        box(cs, normal, bold, colR, y, boxW, rightLabel, rightVal);
        return y - 44;
    }

    private void box(PDPageContentStream cs, PDFont normal, PDFont bold,
                     float x, float y, float w, String label, BigDecimal val) throws IOException {
        cs.setLineWidth(0.5f);
        cs.setStrokingColor(0.6f, 0.6f, 0.6f);
        cs.addRect(x, y - 34, w, 34);
        cs.stroke();
        cs.setStrokingColor(0f, 0f, 0f);
        text(cs, normal, 8, x + 5, y - 11, safe(label));
        text(cs, bold, 13, x + 5, y - 28, money(val));
    }

    // ── PDFBox helpers ──
    private static void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s) throws IOException {
        cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(safe(s)); cs.endText();
    }
    private static void rightText(PDPageContentStream cs, PDFont font, float size, float xRight, float y, String s) throws IOException {
        String t = safe(s); float wdt = font.getStringWidth(t) / 1000f * size; text(cs, font, size, xRight - wdt, y, t);
    }
    private static void hr(PDPageContentStream cs, float y) throws IOException {
        cs.setLineWidth(0.5f); cs.setStrokingColor(0.6f, 0.6f, 0.6f);
        cs.moveTo(LEFT, y); cs.lineTo(RIGHT, y); cs.stroke(); cs.setStrokingColor(0f, 0f, 0f);
    }
    private static String money(BigDecimal v) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        return (x.signum() < 0 ? "-$" : "$") + MONEY.format(x.abs());
    }
    private static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) sb.append(c); else sb.append('?');
        }
        return sb.toString();
    }
}

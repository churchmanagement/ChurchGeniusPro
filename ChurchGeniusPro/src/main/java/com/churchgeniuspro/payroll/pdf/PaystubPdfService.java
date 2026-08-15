package com.churchgeniuspro.payroll.pdf;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PaystubItem;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.PaystubItemRepository;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a professional one-page paystub (Earnings Statement) as a PDF using
 * Apache PDFBox. Covers every requirement-#7 field: employer + employee details,
 * pay period start/end, pay date, itemized earnings, itemized taxes, itemized
 * deductions, net pay, year-to-date columns, and masked direct-deposit info.
 *
 * <p>PDFBox has no table primitive, so a small cursor-based layout helper
 * ({@link Doc}) draws right-aligned money columns and paginates if a stub has an
 * unusually long list of line items.
 */
@Service
public class PaystubPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    // Page geometry (US Letter, points)
    private static final float LEFT = 50f, RIGHT = 562f, TOP = 742f, BOTTOM = 50f;
    private static final float CUR_RIGHT = 432f;   // right edge of the "Current" column
    private static final float YTD_RIGHT = RIGHT;  // right edge of the "YTD" column
    private static final float LINE = 14f;

    private final PaystubRepository paystubRepo;
    private final PaystubItemRepository itemRepo;
    private final PayrollEmployeeRepository employeeRepo;
    private final com.churchgeniuspro.payroll.service.SsnCrypto ssnCrypto;

    public PaystubPdfService(PaystubRepository paystubRepo,
                             PaystubItemRepository itemRepo,
                             PayrollEmployeeRepository employeeRepo,
                             com.churchgeniuspro.payroll.service.SsnCrypto ssnCrypto) {
        this.paystubRepo = paystubRepo;
        this.itemRepo = itemRepo;
        this.employeeRepo = employeeRepo;
        this.ssnCrypto = ssnCrypto;
    }

    /** Load a stub by id and render it. Returns the PDF bytes. */
    public byte[] generate(Long paystubId, EmployerInfo employer) {
        Paystub stub = paystubRepo.findById(paystubId)
                .orElseThrow(() -> new IllegalArgumentException("Paystub not found: " + paystubId));
        List<PaystubItem> items = itemRepo.findByPaystubIdOrderBySortOrderAsc(paystubId);
        PayrollEmployee emp = employeeRepo.findById(stub.getEmployeeId()).orElse(null);
        return generate(stub, items, emp, employer);
    }

    /** Render a fully-loaded stub. {@code employee} and {@code employer} may be null. */
    public byte[] generate(Paystub stub, List<PaystubItem> items,
                           PayrollEmployee employee, EmployerInfo employer) {
        try (Doc d = new Doc()) {
            header(d, stub, employer);
            employeeBlock(d, stub, employee);
            sectionEarnings(d, items, stub);
            sectionTaxes(d, items, stub);
            sectionDeductions(d, items, stub);
            summary(d, stub);
            directDeposit(d, stub);
            footer(d);
            return d.finish();
        } catch (IOException e) {
            throw new RuntimeException("Failed to render paystub PDF", e);
        }
    }

    // ── Sections ───────────────────────────────────────────────────────────

    private void header(Doc d, Paystub stub, EmployerInfo employer) throws IOException {
        String name = employer != null && employer.getName() != null ? employer.getName() : "Employer";
        d.text(LEFT, 16, d.bold, name);
        d.rightText(RIGHT, 12, d.bold, "EARNINGS STATEMENT");
        d.move(LINE + 2);
        if (employer != null) {
            for (String l : new String[]{employer.getAddressLine1(), employer.getAddressLine2(),
                    employer.getCityStateZip(), employer.getEin() == null ? null : "EIN: " + employer.getEin()}) {
                if (l != null && !l.isBlank()) { d.text(LEFT, 9, d.normal, l); d.move(11); }
            }
        }
        d.move(4);
        d.hr();
        d.move(16);
    }

    private void employeeBlock(Doc d, Paystub stub, PayrollEmployee emp) throws IOException {
        float topY = d.y;
        // Left: employee
        d.text(LEFT, 8, d.bold, "EMPLOYEE");
        d.move(12);
        d.text(LEFT, 11, d.bold, stub.getEmployeeName() == null ? "" : stub.getEmployeeName());
        d.move(LINE);
        if (emp != null) {
            for (String l : new String[]{emp.getAddressLine1(), emp.getAddressLine2(),
                    join(emp.getCity(), emp.getState(), emp.getPostalCode())}) {
                if (l != null && !l.isBlank()) { d.text(LEFT, 9, d.normal, l); d.move(11); }
            }
            // Stored encrypted at rest; decrypted only for this authorized document
            String ssn4 = ssnCrypto.decrypt(emp.getSsnLast4());
            if (ssn4 != null && !ssn4.isBlank()) {
                d.text(LEFT, 9, d.normal, "SSN: XXX-XX-" + ssn4);
                d.move(11);
            }
        }
        float leftBottom = d.y;

        // Right: pay info (drawn from the same top)
        d.y = topY;
        labelValueRight(d, "Pay Period", fmt(stub.getPayPeriodStart()) + " - " + fmt(stub.getPayPeriodEnd()));
        labelValueRight(d, "Pay Date", fmt(stub.getPayDate()));

        d.y = Math.min(leftBottom, d.y) - 6;
        d.hr();
        d.move(16);
    }

    private void sectionEarnings(Doc d, List<PaystubItem> items, Paystub stub) throws IOException {
        tableHeader(d, "EARNINGS");
        boolean any = false;
        for (PaystubItem it : items) {
            if (it.getCategory() == PaystubItem.Category.EARNING) {
                row(d, it.getLabel(), it.getCurrentAmount(), it.getYtdAmount(), false);
                any = true;
            }
        }
        if (!any) row(d, "Regular", stub.getGrossEarnings(), stub.getYtdGross(), false);
        totalRow(d, "Gross Pay", stub.getGrossEarnings(), stub.getYtdGross());
        d.move(8);
    }

    private void sectionTaxes(Doc d, List<PaystubItem> items, Paystub stub) throws IOException {
        tableHeader(d, "TAXES WITHHELD");
        for (PaystubItem it : items) {
            if (it.getCategory() == PaystubItem.Category.TAX) {
                row(d, it.getLabel(), it.getCurrentAmount(), it.getYtdAmount(), false);
            }
        }
        totalRow(d, "Total Taxes", stub.getTotalTaxes(), stub.getYtdTaxes());
        d.move(8);
    }

    private void sectionDeductions(Doc d, List<PaystubItem> items, Paystub stub) throws IOException {
        tableHeader(d, "DEDUCTIONS");
        boolean any = false;
        for (PaystubItem it : items) {
            if (it.getCategory() == PaystubItem.Category.PRE_TAX_DEDUCTION) {
                row(d, it.getLabel() + " (pre-tax)", it.getCurrentAmount(), it.getYtdAmount(), false);
                any = true;
            }
        }
        for (PaystubItem it : items) {
            if (it.getCategory() == PaystubItem.Category.POST_TAX_DEDUCTION) {
                row(d, it.getLabel(), it.getCurrentAmount(), it.getYtdAmount(), false);
                any = true;
            }
        }
        if (!any) { row(d, "None", BigDecimal.ZERO, null, false); }
        BigDecimal totalDed = nz(stub.getPreTaxDeductions()).add(nz(stub.getPostTaxDeductions()));
        BigDecimal ytdDed = nz(stub.getYtdPreTaxDeductions()).add(nz(stub.getYtdPostTaxDeductions()));
        totalRow(d, "Total Deductions", totalDed, ytdDed);
        d.move(10);
    }

    private void summary(Doc d, Paystub stub) throws IOException {
        d.ensure(70);
        d.hr();
        d.move(16);
        BigDecimal totalDed = nz(stub.getPreTaxDeductions()).add(nz(stub.getPostTaxDeductions()));
        BigDecimal ytdDed = nz(stub.getYtdPreTaxDeductions()).add(nz(stub.getYtdPostTaxDeductions()));
        // Column titles for the summary
        d.text(LEFT, 8, d.bold, "SUMMARY");
        d.rightText(CUR_RIGHT, 8, d.bold, "CURRENT");
        d.rightText(YTD_RIGHT, 8, d.bold, "YTD");
        d.move(LINE);
        row(d, "Gross Pay", stub.getGrossEarnings(), stub.getYtdGross(), false);
        row(d, "Total Taxes", stub.getTotalTaxes(), stub.getYtdTaxes(), false);
        row(d, "Total Deductions", totalDed, ytdDed, false);
        d.move(4);
        // Net pay highlighted
        d.fillRect(LEFT, d.y - 4, RIGHT - LEFT, 18, 0.93f);
        d.text(LEFT + 4, 12, d.bold, "NET PAY");
        d.rightText(CUR_RIGHT, 12, d.bold, money(stub.getNetPay()));
        d.rightText(YTD_RIGHT, 11, d.bold, money(stub.getYtdNetPay()));
        d.move(22);
    }

    private void directDeposit(Doc d, Paystub stub) throws IOException {
        if (stub.getDirectDepositAccountLast4() == null || stub.getDirectDepositAccountLast4().isBlank()) return;
        String type = stub.getDirectDepositAccountType() == null ? "" : " (" + stub.getDirectDepositAccountType() + ")";
        d.text(LEFT, 9, d.normal,
                "Direct Deposit - Account ****" + stub.getDirectDepositAccountLast4() + type);
        d.rightText(YTD_RIGHT, 9, d.normal, money(stub.getNetPay()));
        d.move(LINE);
    }

    private void footer(Doc d) throws IOException {
        d.move(6);
        d.text(LEFT, 7, d.normal,
                "This document is an earnings statement, not a check. Account numbers are masked for security. "
                        + "Generated " + LocalDate.now().format(DATE) + ".");
    }

    // ── Row / table helpers ──────────────────────────────────────────────────

    private void tableHeader(Doc d, String title) throws IOException {
        d.ensure(2 * LINE);
        d.fillRect(LEFT, d.y - 3, RIGHT - LEFT, 13, 0.92f);
        d.text(LEFT + 2, 8, d.bold, title);
        d.rightText(CUR_RIGHT, 8, d.bold, "CURRENT");
        d.rightText(YTD_RIGHT, 8, d.bold, "YTD");
        d.move(LINE);
    }

    private void row(Doc d, String label, BigDecimal current, BigDecimal ytd, boolean bold) throws IOException {
        d.ensure(LINE);
        PDFont f = bold ? d.bold : d.normal;
        d.text(LEFT + 2, 9, f, label == null ? "" : label);
        d.rightText(CUR_RIGHT, 9, f, money(current));
        if (ytd != null) d.rightText(YTD_RIGHT, 9, f, money(ytd));
        d.move(LINE);
    }

    private void totalRow(Doc d, String label, BigDecimal current, BigDecimal ytd) throws IOException {
        d.ensure(LINE + 2);
        d.setLineThin();
        d.cs.moveTo(LEFT + 2, d.y + 9);
        d.cs.lineTo(RIGHT, d.y + 9);
        d.cs.stroke();
        row(d, label, current, ytd, true);
    }

    private void labelValueRight(Doc d, String label, String value) throws IOException {
        d.rightText(CUR_RIGHT, 8, d.bold, label);
        d.rightText(YTD_RIGHT, 9, d.normal, value);
        d.move(LINE);
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private static String money(BigDecimal v) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        String s = MONEY.format(x.abs());
        return (x.signum() < 0 ? "-$" : "$") + s;
    }

    private static String fmt(LocalDate d) { return d == null ? "" : d.format(DATE); }

    private static String join(String city, String state, String zip) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) sb.append(city);
        if (state != null && !state.isBlank()) sb.append(sb.length() > 0 ? ", " : "").append(state);
        if (zip != null && !zip.isBlank()) sb.append(' ').append(zip);
        return sb.toString();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Replace characters the Standard-14 WinAnsi encoding can't render. */
    private static String safe(String s) {
        if (s == null) return "";
        String t = s.replace('—', '-').replace('–', '-')
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"');
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 0x20 && c <= 0x7E) || (c >= 0xA0 && c <= 0xFF)) sb.append(c);
            else sb.append('?');
        }
        return sb.toString();
    }

    // ── Cursor / document helper ─────────────────────────────────────────────

    /** Minimal cursor-based PDFBox writer with right-aligned text and pagination. */
    private static final class Doc implements AutoCloseable {
        final PDDocument doc = new PDDocument();
        final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDPage page;
        PDPageContentStream cs;
        float y;

        Doc() throws IOException { newPage(); }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = TOP;
        }

        void ensure(float needed) throws IOException {
            if (y - needed < BOTTOM) newPage();
        }

        void move(float dy) { y -= dy; }

        void text(float x, float size, PDFont font, String s) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(safe(s));
            cs.endText();
        }

        void rightText(float xRight, float size, PDFont font, String s) throws IOException {
            String t = safe(s);
            float w = font.getStringWidth(t) / 1000f * size;
            text(xRight - w, size, font, t);
        }

        void hr() throws IOException {
            cs.setLineWidth(0.5f);
            cs.setStrokingColor(0.6f, 0.6f, 0.6f);
            cs.moveTo(LEFT, y);
            cs.lineTo(RIGHT, y);
            cs.stroke();
            cs.setStrokingColor(0f, 0f, 0f);
        }

        void setLineThin() throws IOException {
            cs.setLineWidth(0.4f);
            cs.setStrokingColor(0.75f, 0.75f, 0.75f);
        }

        void fillRect(float x, float yy, float w, float h, float gray) throws IOException {
            cs.setNonStrokingColor(gray, gray, gray);
            cs.addRect(x, yy, w, h);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
        }

        byte[] finish() throws IOException {
            cs.close();
            cs = null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }

        @Override public void close() throws IOException {
            if (cs != null) { cs.close(); cs = null; }
            doc.close();
        }
    }
}

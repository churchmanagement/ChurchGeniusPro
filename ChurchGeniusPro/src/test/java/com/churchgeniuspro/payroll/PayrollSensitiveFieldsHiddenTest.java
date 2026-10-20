package com.churchgeniuspro.payroll;

import com.churchgeniuspro.payroll.controller.PayrollSetupController;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.pdf.EmployerInfo;
import com.churchgeniuspro.payroll.pdf.PaystubPdfService;
import com.churchgeniuspro.payroll.pdf.W2PdfService;
import com.churchgeniuspro.payroll.service.PayrollSetupService;
import com.churchgeniuspro.payroll.service.SsnCrypto;
import com.churchgeniuspro.payroll.service.W2Box;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-19: SSN (last 4) and the bank account / routing last-4 are no longer shown,
 * returned or printed. An employee who still has both stored (encrypted SSN, account
 * digits) must not leak them through the employee API, the pay stub or the W-2 PDF —
 * and the pay stub still says the net pay went by direct deposit. Invented values only.
 */
@DisplayName("Payroll — SSN and bank account last-4 are not returned or printed")
class PayrollSensitiveFieldsHiddenTest {

    static final String SSN4 = "4321", ACCT4 = "8765", ROUT4 = "1357";
    final SsnCrypto crypto = new SsnCrypto("");

    PayrollEmployee employee() {
        PayrollEmployee e = new PayrollEmployee();
        e.setId(7L); e.setAppClientId("CHR-test");
        e.setFirstName("Jamie"); e.setLastName("Rivera");
        e.setSsnLast4(crypto.encrypt(SSN4));                       // as stored before this change
        e.setBankName("Test Bank");
        e.setDirectDepositAccountLast4(ACCT4); e.setDirectDepositRoutingLast4(ROUT4);
        e.setDirectDepositAccountType("CHECKING");
        e.setActive(true);
        return e;
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) { return new PDFTextStripper().getText(d); }
    }

    @Test
    @DisplayName("employee API: no ssnLast4 / account / routing keys; bank name and account type still returned")
    @SuppressWarnings("unchecked")
    void apiOmitsThem() {
        PayrollSetupController c = new PayrollSetupController(mock(PayrollSetupService.class), crypto);
        Map<String, Object> m = (Map<String, Object>) ReflectionTestUtils.invokeMethod(c, "employeeJson", employee());
        assertThat(m).doesNotContainKeys("ssnLast4", "directDepositAccountLast4", "directDepositRoutingLast4");
        assertThat(m.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(SSN4) || v.contains(ACCT4) || v.contains(ROUT4));
        assertThat(m).containsEntry("bankName", "Test Bank").containsEntry("directDepositAccountType", "CHECKING");
    }

    @Test
    @DisplayName("pay stub PDF: no SSN and no account digits, but still 'Direct Deposit (CHECKING)' with the net pay")
    void paystubOmitsThem() throws Exception {
        Paystub s = new Paystub();
        s.setId(1L); s.setEmployeeName("Jamie Rivera");
        s.setPayPeriodStart(LocalDate.of(2026, 10, 1)); s.setPayPeriodEnd(LocalDate.of(2026, 10, 15)); s.setPayDate(LocalDate.of(2026, 10, 16));
        s.setGrossEarnings(new BigDecimal("2000.00")); s.setTotalTaxes(new BigDecimal("300.00")); s.setNetPay(new BigDecimal("1700.00"));
        s.setDirectDepositAccountLast4(ACCT4); s.setDirectDepositAccountType("CHECKING");   // copied onto older stubs
        String t = text(new PaystubPdfService(null, null, null, crypto).generate(s, List.of(), employee(), EmployerInfo.ofName("Test Church")));
        assertThat(t).doesNotContain("SSN").doesNotContain("XXX-XX").doesNotContain(SSN4).doesNotContain(ACCT4).doesNotContain("****");
        assertThat(t).contains("Direct Deposit (CHECKING)").contains("1,700.00");
    }

    @Test
    @DisplayName("W-2 PDF: no SSN line")
    void w2OmitsSsn() throws Exception {
        W2Box w = new W2Box();
        w.setEmployeeId(7L); w.setEmployeeName("Jamie Rivera"); w.setTaxYear(2026);
        String t = text(new W2PdfService(crypto).generate(w, EmployerInfo.ofName("Test Church"), employee()));
        assertThat(t).doesNotContain("SSN").doesNotContain("XXX-XX").doesNotContain(SSN4);
        assertThat(t).contains("Jamie Rivera");
    }
}

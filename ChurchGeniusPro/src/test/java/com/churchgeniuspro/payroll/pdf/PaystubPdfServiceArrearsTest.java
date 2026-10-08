package com.churchgeniuspro.payroll.pdf;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.service.SsnCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Financial audit H4: net pay clamps at zero and the shortfall is carried as
 * {@code arrearsAmount} — that only matters if the shortfall actually reaches
 * the printed paystub, not just a field nobody sees. Smoke-tests that the new
 * "Amount Not Collected" block in {@code PaystubPdfService#summary} renders
 * without error, both when it's needed and when it isn't (the overwhelmingly
 * common case), and that a historical stub predating this field (a null
 * {@code arrearsAmount} from before the column existed) doesn't throw.
 */
class PaystubPdfServiceArrearsTest {

    // The two overloads that query repositories aren't exercised here — the null
    // repo/DAO arguments are never dereferenced by generate(Paystub, List, ...).
    private final PaystubPdfService service = new PaystubPdfService(null, null, null, new SsnCrypto(""));

    private static Paystub stub(BigDecimal netPay, BigDecimal arrears) {
        Paystub s = new Paystub();
        s.setId(1L);
        s.setEmployeeName("Jamie Rivera");
        s.setPayPeriodStart(LocalDate.of(2026, 3, 1));
        s.setPayPeriodEnd(LocalDate.of(2026, 3, 14));
        s.setPayDate(LocalDate.of(2026, 3, 15));
        s.setGrossEarnings(new BigDecimal("500.00"));
        s.setTotalTaxes(BigDecimal.ZERO);
        s.setNetPay(netPay);
        s.setArrearsAmount(arrears);
        return s;
    }

    private static void assertIsPdf(byte[] bytes) {
        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("a stub with a positive arrearsAmount renders without error")
    void rendersWithArrears() {
        byte[] pdf = service.generate(stub(BigDecimal.ZERO, new BigDecimal("200.00")),
                List.of(), null, EmployerInfo.ofName("Test Church"));
        assertIsPdf(pdf);
    }

    @Test
    @DisplayName("a normal stub with no arrears renders without error (unaffected by the new block)")
    void rendersWithoutArrears() {
        byte[] pdf = service.generate(stub(new BigDecimal("500.00"), BigDecimal.ZERO),
                List.of(), null, EmployerInfo.ofName("Test Church"));
        assertIsPdf(pdf);
    }

    @Test
    @DisplayName("a null arrearsAmount (a paystub persisted before this column existed) does not throw")
    void nullArrearsDoesNotThrow() {
        byte[] pdf = service.generate(stub(new BigDecimal("500.00"), null),
                List.of(), null, EmployerInfo.ofName("Test Church"));
        assertIsPdf(pdf);
    }
}

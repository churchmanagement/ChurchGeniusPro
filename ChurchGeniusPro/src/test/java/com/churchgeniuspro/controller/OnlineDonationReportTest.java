package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Transactions report: the "Online Donations &amp; Contributions" view and the donation
 * labels on ordinary rows. Mocked repositories; invented people and amounts only.
 * (The SQL itself is exercised against a local PostgreSQL by the browser E2E.)
 */
@DisplayName("Transactions report — Online Donations & Contributions")
class OnlineDonationReportTest {

    static final String CLIENT = "CGP-test";

    IncomeRepository incomeRepo; ExpenseRepository expenseRepo; DonationRepository donationRepo;
    AccountingReportController ctl;

    @BeforeEach
    void setUp() {
        incomeRepo = mock(IncomeRepository.class); expenseRepo = mock(ExpenseRepository.class);
        donationRepo = mock(DonationRepository.class);
        ctl = new AccountingReportController(incomeRepo, expenseRepo, mock(PurposeRepository.class), mock(ChurchRegistrationRepository.class));
        ctl.setDonationRepo(donationRepo);
    }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "bookkeeper@example.org");
        s.setAttribute("appClientId", CLIENT); s.setAttribute("clientId", CLIENT);
        if (role != null) s.setAttribute("role", role);
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    /** One report row as the native query returns it (see DonationRepository#reportOnlineDonationsInRange). */
    static Object[] row(long id, String first, String last, String amount, String date,
                        Integer incomeId, String incomeAmt, Integer expenseId, String expenseAmt,
                        String stripeFee, String feeSource, boolean postedByReview, String source) {
        return new Object[]{ id, first, last, first.toLowerCase() + "@example.org", "555-0100", "For missions",
                new BigDecimal(amount), source, incomeId, Date.valueOf(date), incomeAmt == null ? null : new BigDecimal(incomeAmt),
                null, null, expenseId, expenseAmt == null ? null : new BigDecimal(expenseAmt),
                stripeFee == null ? null : new BigDecimal(stripeFee), feeSource, postedByReview, "USD" };
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> online(MockHttpServletRequest r, String sd, String ed) {
        ResponseEntity<?> res = ctl.onlineDonationsReport(sd, ed, r);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (Map<String, Object>) res.getBody();
    }

    @Nested @DisplayName("calculations")
    class Calculations {
        @Test @DisplayName("$50.00 with a recorded $1.75 fee → donation $50.00, fees $1.75, deposited $48.25")
        void recordedFee() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.<Object[]>of(
                    row(7, "Asha", "Thomas", "50.00", "2026-10-15", 801, "50.00", 901, "1.75", "1.75", "STRIPE", true, "PUBLIC")));
            Map<String, Object> m = online(req("Accountant", null), "2026-10-01", "2026-10-31");
            Map<String, Object> r = ((List<Map<String, Object>>) m.get("rows")).get(0);
            assertThat(r.get("donationNumber")).isEqualTo("DON-000007");
            assertThat(r.get("contributor")).isEqualTo("Asha Thomas");
            assertThat(r.get("email")).isEqualTo("asha@example.org");
            assertThat(r.get("phone")).isEqualTo("555-0100");
            assertThat(r.get("note")).isEqualTo("For missions");
            assertThat(r.get("date")).isEqualTo("2026-10-15");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("50.00");
            assertThat((BigDecimal) r.get("fee")).isEqualByComparingTo("1.75");
            assertThat((BigDecimal) r.get("deposited")).isEqualByComparingTo("48.25");
            assertThat(r.get("feeStatus")).isEqualTo("RECORDED");
            assertThat(r.get("postedBeforeReview")).isEqualTo(false);
            assertThat((BigDecimal) m.get("totalAmount")).isEqualByComparingTo("50.00");
            assertThat((BigDecimal) m.get("totalFees")).isEqualByComparingTo("1.75");
            assertThat((BigDecimal) m.get("totalDeposited")).isEqualByComparingTo("48.25");
            assertThat(m.get("feeNotRecordedCount")).isEqualTo(0);
        }

        @Test @DisplayName("no fee Expense and no verified fee → fee and deposited null, 'NOT_RECORDED', left out of the fee totals")
        void missingFeeIsNeverAssumed() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.of(
                    row(42, "Pat", "Example", "50.00", "2026-10-12", 1223, "50.00", null, null, null, null, false, "PUBLIC"),
                    row(43, "Lee", "Example", "100.00", "2026-10-16", 1300, "100.00", 1400, "3.20", "3.20", "STRIPE", true, "MEMBER_PORTAL")));
            Map<String, Object> m = online(req("Accountant", null), "2026-10-01", "2026-10-31");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("rows");
            Map<String, Object> missing = rows.get(0);
            assertThat(missing.get("feeStatus")).isEqualTo("NOT_RECORDED");
            assertThat(missing.get("fee")).isNull();
            assertThat(missing.get("deposited")).isNull();
            assertThat(missing.get("postedBeforeReview")).isEqualTo(true);
            assertThat(rows.get(1).get("source")).isEqualTo("Member Portal");
            assertThat((BigDecimal) m.get("totalAmount")).isEqualByComparingTo("150.00");
            assertThat((BigDecimal) m.get("totalFees")).isEqualByComparingTo("3.20");
            assertThat((BigDecimal) m.get("totalDeposited")).isEqualByComparingTo("96.80");
            assertThat(m.get("feeNotRecordedCount")).isEqualTo(1);
        }

        @Test @DisplayName("Stripe reported a fee but no Expense exists → shown as STRIPE_ONLY, not as recorded")
        void stripeOnly() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.<Object[]>of(
                    row(9, "Sam", "Example", "20.00", "2026-10-02", 700, "20.00", null, null, "0.88", "STRIPE", false, "PUBLIC")));
            Map<String, Object> r = ((List<Map<String, Object>>) online(req("Accountant", null), "2026-10-01", "2026-10-31").get("rows")).get(0);
            assertThat(r.get("feeStatus")).isEqualTo("STRIPE_ONLY");
            assertThat((BigDecimal) r.get("deposited")).isEqualByComparingTo("19.12");
        }

        @Test @DisplayName("posted with Stripe's $0.00 fee → NO_FEE, deposited = donation")
        void zeroFee() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.<Object[]>of(
                    row(10, "Kim", "Example", "25.00", "2026-10-03", 701, "25.00", null, null, "0.00", "STRIPE", true, "PUBLIC")));
            Map<String, Object> r = ((List<Map<String, Object>>) online(req("Accountant", null), "2026-10-01", "2026-10-31").get("rows")).get(0);
            assertThat(r.get("feeStatus")).isEqualTo("NO_FEE");
            assertThat((BigDecimal) r.get("deposited")).isEqualByComparingTo("25.00");
        }

        @Test @DisplayName("an Income row edited after posting is flagged with its own amount; the report keeps the donation amount")
        void editedIncomeFlagged() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.<Object[]>of(
                    row(11, "Ann", "Example", "100.00", "2026-10-04", 702, "96.80", null, null, null, null, false, "PUBLIC")));
            Map<String, Object> r = ((List<Map<String, Object>>) online(req("Accountant", null), "2026-10-01", "2026-10-31").get("rows")).get(0);
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) r.get("incomeAmount")).isEqualByComparingTo("96.80");
        }

        @Test @DisplayName("a donation appearing twice in the query result is counted once")
        void oneRowPerDonation() {
            Object[] a = row(12, "Bo", "Example", "10.00", "2026-10-05", 703, "10.00", 903, "0.59", "0.59", "STRIPE", true, "PUBLIC");
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), eq(CLIENT))).thenReturn(List.of(a, a.clone()));
            Map<String, Object> m = online(req("Accountant", null), "2026-10-01", "2026-10-31");
            assertThat((List<?>) m.get("rows")).hasSize(1);
            assertThat((BigDecimal) m.get("totalAmount")).isEqualByComparingTo("10.00");
        }
    }

    @Nested @DisplayName("dates and access")
    class DatesAndAccess {
        @Test @DisplayName("passes the inclusive start/end dates and the session's church to the query")
        void datesAndTenant() {
            when(donationRepo.reportOnlineDonationsInRange(any(), any(), anyString())).thenReturn(List.of());
            online(req("SuperAdmin", null), "2026-01-01", "2026-10-18");
            verify(donationRepo).reportOnlineDonationsInRange(Date.valueOf(LocalDate.of(2026, 1, 1)), Date.valueOf(LocalDate.of(2026, 10, 18)), CLIENT);
        }

        @Test @DisplayName("end before start / bad date → 400, nothing queried")
        void badDates() {
            assertThat(ctl.onlineDonationsReport("2026-10-18", "2026-10-01", req("Accountant", null)).getStatusCode().value()).isEqualTo(400);
            assertThat(ctl.onlineDonationsReport("x", "2026-10-01", req("Accountant", null)).getStatusCode().value()).isEqualTo(400);
            verify(donationRepo, never()).reportOnlineDonationsInRange(any(), any(), any());
        }

        @Test @DisplayName("roles without accounting access → 403, signed out → refused; nothing queried")
        void access() {
            assertThat(ctl.onlineDonationsReport("2026-10-01", "2026-10-31", req("Volunteer", null)).getStatusCode().value()).isEqualTo(403);
            assertThat(ctl.onlineDonationsReport("2026-10-01", "2026-10-31", req("User", null)).getStatusCode().value()).isEqualTo(403);
            MockHttpServletRequest none = new MockHttpServletRequest();
            assertThat(ctl.onlineDonationsReport("2026-10-01", "2026-10-31", none).getStatusCode().value()).isGreaterThanOrEqualTo(400);
            verify(donationRepo, never()).reportOnlineDonationsInRange(any(), any(), any());
        }
    }

    @Nested @DisplayName("labels on the ordinary report")
    class Labels {
        static Object[] incomeRow(int id, String ref, String importRef) {
            return new Object[]{ id, Date.valueOf("2026-10-12"), null, null, "Pat Example", "Tithe", "General",
                    new BigDecimal("50.00"), "Card", ref, "Online donation", importRef };
        }
        static Object[] expenseRow(int id, String importRef, String note) {
            return new Object[]{ id, Date.valueOf("2026-10-12"), "General", "Stripe fees",
                    new BigDecimal("1.75"), "Card", null, note, importRef };
        }

        @Test @DisplayName("rows of online donations get DON- numbers (by posting link or PaymentIntent); amounts and totals unchanged; import_ref not exposed")
        @SuppressWarnings("unchecked")
        void labels() {
            Donation posted = new Donation(); posted.setId(5L); posted.setClientId(CLIENT); posted.setStripePaymentIntentId("pi_posted");
            posted.setPostedIncomeId(800); posted.setPostedExpenseId(900);
            Donation hist = new Donation(); hist.setId(42L); hist.setClientId(CLIENT); hist.setStripePaymentIntentId("pi_hist");
            List<Object[]> inc = new ArrayList<>(List.of(incomeRow(800, "DON-000005", "pi_posted"), incomeRow(1223, null, "pi_hist"), incomeRow(5, null, null)));
            List<Object[]> exp = new ArrayList<>(List.of(expenseRow(900, "pi_posted:stripe-fee", "Stripe fee…"), expenseRow(901, null, "Office supplies")));
            when(incomeRepo.reportIncomeTransactionsInRange(any(), any(), eq(CLIENT))).thenReturn(inc);
            when(expenseRepo.reportExpenseTransactionsInRange(any(), any(), eq(CLIENT))).thenReturn(exp);
            when(donationRepo.findCreditPostedTo(eq(CLIENT), any())).thenReturn(List.of(posted));
            when(donationRepo.findFeePostedTo(eq(CLIENT), any())).thenReturn(List.of(posted));
            when(donationRepo.findByClientIdAndStripePaymentIntentIdIn(eq(CLIENT), any())).thenReturn(List.of(posted, hist));

            Map<String, Object> m = (Map<String, Object>) ctl.transactionsReport("2026-10-01", "2026-10-31", req("Accountant", null)).getBody();
            List<Map<String, Object>> income = (List<Map<String, Object>>) m.get("income");
            List<Map<String, Object>> expense = (List<Map<String, Object>>) m.get("expense");
            assertThat(income.get(0).get("donationNumber")).isEqualTo("DON-000005");
            assertThat(income.get(1).get("donationNumber")).isEqualTo("DON-000042");
            assertThat(income.get(2)).doesNotContainKey("donationNumber");
            assertThat(expense.get(0).get("donationNumber")).isEqualTo("DON-000005");
            assertThat(expense.get(1)).doesNotContainKey("donationNumber");
            assertThat(expense.get(1).get("note")).isEqualTo("Office supplies");
            income.forEach(r -> assertThat(r).doesNotContainKey("importRef"));
            expense.forEach(r -> assertThat(r).doesNotContainKey("importRef"));
            assertThat((BigDecimal) m.get("totalIncome")).isEqualByComparingTo("150.00");
            assertThat((BigDecimal) m.get("totalExpense")).isEqualByComparingTo("3.50");
        }

        @Test @DisplayName("without the donation repository (or if it fails) the report is exactly as before")
        @SuppressWarnings("unchecked")
        void unchangedWithoutLabels() {
            AccountingReportController plain = new AccountingReportController(incomeRepo, expenseRepo, mock(PurposeRepository.class), mock(ChurchRegistrationRepository.class));
            List<Object[]> inc = new ArrayList<>(); inc.add(new Object[]{ 1, Date.valueOf("2026-10-12"), "Pat", "Example", null, "Tithe", "General", new BigDecimal("10.00"), "Cash", "", "" });
            List<Object[]> exp = new ArrayList<>(); exp.add(new Object[]{ 2, Date.valueOf("2026-10-12"), "General", "Supplies", new BigDecimal("4.00"), "Cash", "", "" });
            when(incomeRepo.reportIncomeTransactionsInRange(any(), any(), eq(CLIENT))).thenReturn(inc);
            when(expenseRepo.reportExpenseTransactionsInRange(any(), any(), eq(CLIENT))).thenReturn(exp);
            Map<String, Object> a = (Map<String, Object>) plain.transactionsReport("2026-10-01", "2026-10-31", req("Accountant", null)).getBody();
            when(donationRepo.findCreditPostedTo(any(), any())).thenThrow(new RuntimeException("db down"));
            Map<String, Object> b = (Map<String, Object>) ctl.transactionsReport("2026-10-01", "2026-10-31", req("Accountant", null)).getBody();
            assertThat(b).isEqualTo(a);
            assertThat(((List<Map<String, Object>>) a.get("income")).get(0).get("member")).isEqualTo("Example, Pat");
        }
    }

    @Nested @DisplayName("donation number")
    class Number {
        @Test void format() {
            assertThat(Donation.numberOf(42L)).isEqualTo("DON-000042");
            assertThat(Donation.numberOf(1234567L)).isEqualTo("DON-1234567");
            assertThat(Donation.numberOf(null)).isNull();
            assertThat(Donation.idFromNumber("DON-000042")).isEqualTo(42L);
            assertThat(Donation.idFromNumber(" don-42 ")).isEqualTo(42L);
            assertThat(Donation.idFromNumber("DON-0")).isNull();
            assertThat(Donation.idFromNumber("PLEDGE-1")).isNull();
        }
    }
}

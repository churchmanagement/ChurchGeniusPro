package com.churchgeniuspro.accounting;

import com.churchgeniuspro.controller.AccountingReportController;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Financial audit M5: the Year-End Tax Report groups guest income by the
 * free-text {@code guest_name} column — the only identity a walk-in or
 * anonymous gift carries. A row with neither a matched member nor any guest
 * name at all (an unattributed lump sum, e.g. an uncounted plate-offering
 * total) used to fall into a single {@code "guest::"} bucket keyed by an
 * empty string, becoming a contribution statement addressed to nobody. It
 * is now excluded from the per-contributor statements and reported
 * separately instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YearEndTaxReportUnattributedTest {

    private static final String CLIENT = "CHR-1";
    private static final int YEAR = 2026;

    @Mock IncomeRepository             incomeRepo;
    @Mock ExpenseRepository            expenseRepo;
    @Mock PurposeRepository            purposeRepo;
    @Mock ChurchRegistrationRepository churchRepo;

    private AccountingReportController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountingReportController(incomeRepo, expenseRepo, purposeRepo, churchRepo);
    }

    private static MockHttpServletRequest staffRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "bookkeeper@example.org");
        session.setAttribute("role", "Accountant");
        session.setAttribute("appClientId", CLIENT);
        req.setSession(session);
        return req;
    }

    /** [member_id, first_name, last_name, guest_name, sub_name, main_name, total, cnt] */
    private static Object[] summaryRow(Integer memberId, String first, String last, String guestName,
                                       String subName, BigDecimal total) {
        return new Object[]{memberId, first, last, guestName, subName, "Offering", total, 1L};
    }

    /** [member_id, first, last, guest_name, addr1, addr2, city, state, pin, date, main, sub, amount, method] */
    private static Object[] entryRow(Integer memberId, String first, String last, String guestName,
                                     BigDecimal amount) {
        return new Object[]{memberId, first, last, guestName, "", "", "", "", "",
                LocalDate.of(YEAR, 3, 15), "Offering", "Tithe", amount, "Cash"};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> members(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("members");
    }

    @Test
    @DisplayName("a row with no member and no guest name is excluded from the statements and reported separately")
    void unattributedRowExcludedFromStatements() {
        when(incomeRepo.reportAnnualIncomeByMemberFiltered(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                summaryRow(5, "Jamie", "Rivera", null, "Tithe", new BigDecimal("100.00")),
                summaryRow(null, null, null, null, "Plate Offering", new BigDecimal("340.00")),
                summaryRow(null, null, null, "", "Plate Offering", new BigDecimal("10.00"))));
        when(incomeRepo.reportIncomeEntriesForTaxYear(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                entryRow(5, "Jamie", "Rivera", null, new BigDecimal("100.00")),
                entryRow(null, null, null, null, new BigDecimal("340.00")),
                entryRow(null, null, null, "", new BigDecimal("10.00"))));

        ResponseEntity<?> resp = controller.taxReport(YEAR, null, null, staffRequest());

        Map<String, Object> body = body(resp);
        List<Map<String, Object>> members = members(body);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("id")).isEqualTo(5);
        assertThat(members.get(0).get("memberTotal")).isEqualTo(new BigDecimal("100.00"));

        assertThat(body.get("unattributedTotal")).isEqualTo(new BigDecimal("350.00"));
        assertThat(body.get("unattributedCount")).isEqualTo(2);
        // The named member's total is the report's grand total — the two
        // unattributed rows never inflate a "statement" total for anyone.
        assertThat(body.get("grandTotal")).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("two entries for the same unattributed bucket don't crash trying to find a memberMap entry")
    void unattributedEntriesDoNotOrphan() {
        when(incomeRepo.reportAnnualIncomeByMemberFiltered(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                summaryRow(null, null, null, null, "Plate Offering", new BigDecimal("50.00"))));
        when(incomeRepo.reportIncomeEntriesForTaxYear(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                entryRow(null, null, null, null, new BigDecimal("20.00")),
                entryRow(null, null, null, null, new BigDecimal("30.00"))));

        ResponseEntity<?> resp = controller.taxReport(YEAR, null, null, staffRequest());

        assertThat(members(body(resp))).isEmpty();
        assertThat(body(resp).get("unattributedTotal")).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("a named guest still gets a statement, flagged as matched by name only")
    void namedGuestStillGetsStatement() {
        when(incomeRepo.reportAnnualIncomeByMemberFiltered(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                summaryRow(null, null, null, "Jane Visitor", "Tithe", new BigDecimal("50.00"))));
        when(incomeRepo.reportIncomeEntriesForTaxYear(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                entryRow(null, null, null, "Jane Visitor", new BigDecimal("50.00"))));

        ResponseEntity<?> resp = controller.taxReport(YEAR, null, null, staffRequest());

        Map<String, Object> body = body(resp);
        List<Map<String, Object>> members = members(body);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("name")).isEqualTo("Jane Visitor");
        assertThat(members.get(0).get("nameOnly")).isEqualTo(true);
        assertThat(members.get(0).get("memberTotal")).isEqualTo(new BigDecimal("50.00"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) members.get(0).get("entries");
        assertThat(entries).hasSize(1);

        assertThat(body.get("unattributedTotal")).isEqualTo(BigDecimal.ZERO);
        assertThat(body.get("unattributedCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("a member's own statement carries no nameOnly flag")
    void memberStatementHasNoNameOnlyFlag() {
        when(incomeRepo.reportAnnualIncomeByMemberFiltered(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                summaryRow(5, "Jamie", "Rivera", null, "Tithe", new BigDecimal("100.00"))));
        when(incomeRepo.reportIncomeEntriesForTaxYear(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                entryRow(5, "Jamie", "Rivera", null, new BigDecimal("100.00"))));

        ResponseEntity<?> resp = controller.taxReport(YEAR, null, null, staffRequest());

        Map<String, Object> member = members(body(resp)).get(0);
        assertThat(member).doesNotContainKey("nameOnly");
    }

    @Test
    @DisplayName("no unattributed income at all reports zero, not a missing field")
    void noUnattributedIncomeReportsZero() {
        when(incomeRepo.reportAnnualIncomeByMemberFiltered(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                summaryRow(5, "Jamie", "Rivera", null, "Tithe", new BigDecimal("100.00"))));
        when(incomeRepo.reportIncomeEntriesForTaxYear(YEAR, CLIENT, null, null)).thenReturn(List.<Object[]>of(
                entryRow(5, "Jamie", "Rivera", null, new BigDecimal("100.00"))));

        ResponseEntity<?> resp = controller.taxReport(YEAR, null, null, staffRequest());

        assertThat(body(resp).get("unattributedTotal")).isEqualTo(BigDecimal.ZERO);
        assertThat(body(resp).get("unattributedCount")).isEqualTo(0);
    }
}

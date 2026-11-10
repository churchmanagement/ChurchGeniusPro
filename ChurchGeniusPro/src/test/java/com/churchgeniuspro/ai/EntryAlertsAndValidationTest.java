package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.entryalert.EntryAlertController;
import com.churchgeniuspro.entryalert.EntryAlertService;
import com.churchgeniuspro.entryalert.EntryAlertService.Kind;
import com.churchgeniuspro.entryalert.EntryAlertService.Settings;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.hibernate.EntryAlertSetting;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.AiActionProposalRepository;
import com.churchgeniuspro.repository.EntryAlertSettingRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-09 — spec "Income &amp; Expense Alerts, Validation, and AI Entry Confirmation": the shared alert rules and
 * settings (per login and role), the Income / Expense create endpoints holding an alerted entry until the user
 * confirms, the Genius Assistant using the SAME alert service, its invalid date / amount / fund messages (never a
 * guess), and the saved confirmation with the fund totals before and after.
 */
@DisplayName("Income & Expense alerts, Genius Assistant validation and saved confirmation")
class EntryAlertsAndValidationTest {

    static final String CID = "CHR-A";
    static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    LocalDate today;

    // settings table in memory
    Map<String, EntryAlertSetting> rows = new LinkedHashMap<>();
    EntryAlertSettingRepository alertRepo;
    EntryAlertService alerts;

    // guided conversation fixture (same shape as AiGuidedRecordTest)
    AiActionLookup people; AiFinanceLookup fin; IncomeService incomes; ExpenseService expenses;
    AiActionProposalRepository repo; Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiActionService actions; AiAccessPolicy policy; AiGuidedRecordService svc;
    /** The running Net Balance, as the summary table would answer: before the save, then after it. */
    AtomicReference<BigDecimal> netBalance = new AtomicReference<>();

    static String key(String cid, String user, String role, String kind) { return cid + "|" + user + "|" + role + "|" + kind; }

    @BeforeEach
    void setUp() {
        today = AppClock.today();
        alertRepo = mock(EntryAlertSettingRepository.class);
        when(alertRepo.findByAppClientIdAndUsernameAndRoleAndKind(anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(i -> Optional.ofNullable(rows.get(key(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)))));
        when(alertRepo.save(any())).thenAnswer(i -> { EntryAlertSetting s = i.getArgument(0); rows.put(key(s.getAppClientId(), s.getUsername(), s.getRole(), s.getKind()), s); return s; });
        alerts = new EntryAlertService(alertRepo);

        people = mock(AiActionLookup.class); fin = mock(AiFinanceLookup.class); incomes = mock(IncomeService.class); expenses = mock(ExpenseService.class);
        when(people.members(eq(CID), anyString())).thenReturn(List.of());
        when(people.members(eq(CID), AiGuidedRecordTest.ci("anson mathew"))).thenReturn(List.of(new Hit(5, "Anson Mathew", "1")));
        when(people.memberById(eq(CID), anyLong())).thenAnswer(i -> (Long) i.getArgument(1) == 5L ? new Hit(5, "Anson Mathew", "1") : null);
        List<Hit> funds = List.of(new Hit(1, "Tithe Fund", null), new Hit(2, "Building Fund", null));
        List<Hit> xfunds = List.of(new Hit(21, "General Fund", null));
        List<Hit> cats = List.of(new Hit(31, "Utilities", null));
        List<Hit> methods = List.of(new Hit(11, "Cash", null), new Hit(12, "Check", null));
        when(fin.incomeFunds(CID)).thenReturn(funds); when(fin.expenseFunds(CID)).thenReturn(xfunds);
        when(fin.expenseCategories(CID)).thenReturn(cats); when(fin.methods(CID)).thenReturn(methods);
        when(fin.incomeFundById(eq(CID), anyLong())).thenAnswer(i -> AiGuidedRecordTest.byId(funds, i.getArgument(1)));
        when(fin.expenseFundById(eq(CID), anyLong())).thenAnswer(i -> AiGuidedRecordTest.byId(xfunds, i.getArgument(1)));
        when(fin.expenseCategoryById(eq(CID), anyLong())).thenAnswer(i -> AiGuidedRecordTest.byId(cats, i.getArgument(1)));
        when(fin.methodById(eq(CID), anyLong())).thenAnswer(i -> AiGuidedRecordTest.byId(methods, i.getArgument(1)));
        // the church's Net Balance as the dashboard shows it (the user's example: $3,402.26 before a $100 income)
        netBalance.set(new BigDecimal("3402.26"));
        when(fin.netBalance(CID)).thenAnswer(i -> netBalance.get());
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString()))
            .thenAnswer(i -> { netBalance.set(netBalance.get().add(i.getArgument(5))); Income x = new Income(); x.setId(501); return x; });
        when(expenses.createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString()))
            .thenAnswer(i -> { netBalance.set(netBalance.get().subtract(i.getArgument(5))); Expense x = new Expense(); x.setId(601); return x; });
        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
            store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        AiToolRegistry registry = new AiToolRegistry(List.of(new AiFinanceTools.CreateIncome(people, fin, incomes), new AiFinanceTools.CreateExpense(fin, expenses)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        policy = spy(new AiAccessPolicy());
        actions = new AiActionService(registry, repo, policy, null, new TransactionTemplate(tm), true, true, 10);
        svc = new AiGuidedRecordService(policy, actions, people, fin, null);
        svc.llmFallback = false;
        svc.setEntryAlerts(alerts);
    }

    static MockHttpServletRequest req(String user, String role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", user); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        return r;
    }
    Map<String, Object> say(MockHttpServletRequest r, String text) { return svc.handle(AiGuidedRecordTest.same(r), CID, text); }
    Map<String, Object> saveSettings(MockHttpServletRequest r, String kind, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> res = new EntryAlertController(alerts).put(kind, body, r);
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(200);
        return res.getBody();
    }
    static Map<String, Object> settings(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    // ── 1. the rule ───────────────────────────────────────────────────────────

    @Test @DisplayName("rules: future / old date (strictly more than N days), greater than / less than; off or missing = nothing")
    void rules() {
        Settings s = new Settings(true, 30, true, 365, true, new BigDecimal("10000"), true, new BigDecimal("5"));
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today, new BigDecimal("100"), today)).isEmpty();             // normal, recent
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today.plusDays(30), new BigDecimal("100"), today)).isEmpty();  // exactly 30 ahead
        var f = EntryAlertService.evaluate(s, Kind.INCOME, today.plusDays(31), new BigDecimal("100"), today);
        assertThat(f).extracting(EntryAlertService.Alert::code).containsExactly("FUTURE_DATE");
        assertThat(f.get(0).message()).contains(US.format(today.plusDays(31)), "31 days in the future", "more than 30 days ahead");
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today.minusDays(365), new BigDecimal("100"), today)).isEmpty();
        var o = EntryAlertService.evaluate(s, Kind.EXPENSE, today.minusDays(400), new BigDecimal("100"), today);
        assertThat(o).extracting(EntryAlertService.Alert::code).containsExactly("OLD_DATE");
        assertThat(o.get(0).message()).contains("400 days in the past", "expense dated more than 365 days ago");
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today, new BigDecimal("10000.00"), today)).isEmpty();          // equal = not greater
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today, new BigDecimal("10000.01"), today))
            .extracting(EntryAlertService.Alert::code).containsExactly("AMOUNT_HIGH");
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today, new BigDecimal("4.99"), today).get(0).message())
            .contains("$4.99", "less than $5.00");
        assertThat(EntryAlertService.evaluate(s, Kind.INCOME, today.plusDays(90), new BigDecimal("20000"), today)).hasSize(2);
        Settings off = new Settings(false, 1, false, 1, false, new BigDecimal("1"), false, new BigDecimal("1000"));
        assertThat(EntryAlertService.evaluate(off, Kind.INCOME, today.plusDays(900), new BigDecimal("50000"), today)).isEmpty();
        assertThat(EntryAlertService.evaluate(Settings.NONE, Kind.INCOME, today.plusDays(900), new BigDecimal("50000"), today)).isEmpty();
        // a broken settings lookup never stops an entry
        EntryAlertSettingRepository broken = mock(EntryAlertSettingRepository.class);
        when(broken.findByAppClientIdAndUsernameAndRoleAndKind(any(), any(), any(), any())).thenThrow(new RuntimeException("db"));
        assertThat(new EntryAlertService(broken).check(new EntryAlertService.Owner(CID, "a", "Admin", 1), Kind.INCOME, today.plusDays(900), BigDecimal.TEN, today)).isEmpty();
    }

    // ── 2. settings ───────────────────────────────────────────────────────────

    @Test @DisplayName("settings: validated (empty / negative / non-numeric / decimals / range / min ≥ max), saved per login, per role and per kind")
    void settingsValidationAndScope() {
        EntryAlertController c = new EntryAlertController(alerts);
        MockHttpServletRequest pat = req("pat", "Accountant");
        record Bad(Map<String, Object> body, String field, String text) {}
        for (Bad b : List.of(
                new Bad(settings("futureEnabled", true, "futureDays", ""), "futureDays", "Enter the number of days ahead"),
                new Bad(settings("pastEnabled", true, "pastDays", "-5"), "pastDays", "whole number"),
                new Bad(settings("pastEnabled", true, "pastDays", "0"), "pastDays", "from 1 to 3650"),
                new Bad(settings("futureEnabled", true, "futureDays", "4000"), "futureDays", "from 1 to 3650"),
                new Bad(settings("futureEnabled", true, "futureDays", "ten"), "futureDays", "whole number"),
                new Bad(settings("maxEnabled", true, "maxAmount", null), "maxAmount", "Enter the amount"),
                new Bad(settings("maxEnabled", true, "maxAmount", "-100"), "maxAmount", "greater than $0"),
                new Bad(settings("minEnabled", true, "minAmount", "abc"), "minAmount", "must be a number"),
                new Bad(settings("minEnabled", true, "minAmount", "1.005"), "minAmount", "two decimal places"),
                new Bad(settings("maxEnabled", true, "maxAmount", "100", "minEnabled", true, "minAmount", "100"), "minAmount", "smaller than"))) {
            ResponseEntity<Map<String, Object>> res = c.put("income", b.body(), pat);
            assertThat(res.getStatusCode().value()).as(b.body().toString()).isEqualTo(400);
            assertThat(String.valueOf(m(res.getBody().get("problems")).get(b.field()))).as(b.body().toString()).contains(b.text());
        }
        assertThat(rows).isEmpty();                                   // nothing saved while anything is invalid
        // off with no value is fine; "$1,000" is read as 1000
        saveSettings(pat, "income", settings("futureEnabled", true, "futureDays", "30", "maxEnabled", true, "maxAmount", "$1,000", "pastEnabled", false, "pastDays", null));
        Map<String, Object> got = new EntryAlertController(alerts).get("income", pat).getBody();
        assertThat(m(got.get("settings"))).containsEntry("futureDays", 30).containsEntry("maxAmount", "1000.00").containsEntry("pastEnabled", false);
        assertThat(got).containsEntry("role", "Accountant");
        // another login, the same login in another role, and the expense settings are all separate
        assertThat(m(c.get("income", req("lee", "Accountant")).getBody().get("settings"))).containsEntry("futureEnabled", false);
        assertThat(m(c.get("income", req("pat", "SuperAdmin")).getBody().get("settings"))).containsEntry("futureEnabled", false);
        assertThat(m(c.get("expense", pat).getBody().get("settings"))).containsEntry("maxEnabled", false);
        var owner = new EntryAlertService.Owner(CID, "pat", "Accountant", null);
        assertThat(alerts.check(owner, Kind.INCOME, today, new BigDecimal("1500"), today)).hasSize(1);
        assertThat(alerts.check(new EntryAlertService.Owner(CID, "pat", "SuperAdmin", null), Kind.INCOME, today, new BigDecimal("1500"), today)).isEmpty();
        assertThat(alerts.check(new EntryAlertService.Owner(CID, "lee", "Accountant", null), Kind.INCOME, today, new BigDecimal("1500"), today)).isEmpty();
        assertThat(alerts.check(new EntryAlertService.Owner("CHR-B", "pat", "Accountant", null), Kind.INCOME, today, new BigDecimal("1500"), today)).isEmpty();
        // permissions: only a login that may add that kind of entry has alerts
        assertThat(c.get("income", new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(c.get("income", req("m", "Member")).getStatusCode().value()).isEqualTo(403);
        assertThat(c.get("income", req("u", "User")).getStatusCode().value()).isEqualTo(403);
        MockHttpServletRequest noEdit = req("pat", "Accountant");
        noEdit.getSession().setAttribute("privileges", "{\"accounting.income.edit\":false}");
        assertThat(c.put("income", settings("futureEnabled", false), noEdit).getStatusCode().value()).isEqualTo(403);
        assertThat(c.get("donations", pat).getStatusCode().value()).isEqualTo(404);
    }

    // ── 3. the Income / Expense pages (and every other POST /api/income|expense caller) ──

    @Test @DisplayName("POST /api/income and /api/expense: an alerted entry → 409 ENTRY_ALERTS and NOT saved; saved after alertsConfirmed; no alerts → saved as before")
    void createEndpoints() {
        MockHttpServletRequest pat = req("pat", "Accountant");
        saveSettings(pat, "income", settings("futureEnabled", true, "futureDays", "7", "pastEnabled", true, "pastDays", "90",
                                             "maxEnabled", true, "maxAmount", "5000", "minEnabled", true, "minAmount", "1"));
        saveSettings(pat, "expense", settings("maxEnabled", true, "maxAmount", "2000"));
        IncomeService is = mock(IncomeService.class);
        when(is.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenAnswer(i -> { Income x = new Income(); x.setId(77); return x; });
        IncomeController ic = new IncomeController(is);
        ReflectionTestUtils.setField(ic, "entryAlerts", alerts);
        record Case(String label, LocalDate date, String amount, int status, String code) {}
        for (Case k : List.of(
                new Case("recent", today, "100", 200, null),
                new Case("backdated within 90 days", today.minusDays(60), "100", 200, null),
                new Case("backdated beyond 90 days", today.minusDays(120), "100", 409, "OLD_DATE"),
                new Case("future beyond 7 days", today.plusDays(10), "100", 409, "FUTURE_DATE"),
                new Case("above the maximum", today, "7500", 409, "AMOUNT_HIGH"),
                new Case("below the minimum", today, "0.50", 409, "AMOUNT_LOW"))) {
            clearInvocations(is);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("subSourceId", 1, "incomeDate", k.date().toString(), "transactionTypeId", 11,
                                                                  "amount", k.amount(), "guestName", "Anonymous"));
            ResponseEntity<Map<String, Object>> res = ic.createIncome(body, pat);
            assertThat(res.getStatusCode().value()).as(k.label()).isEqualTo(k.status());
            if (k.code() != null) {
                assertThat(res.getBody()).containsEntry("code", "ENTRY_ALERTS");
                assertThat(list(res.getBody().get("alerts"))).extracting(a -> a.get("code")).containsExactly(k.code());
                verifyNoInteractions(is);                                         // nothing saved
                body.put("alertsConfirmed", true);                               // the user pressed "Save anyway"
                assertThat(ic.createIncome(body, pat).getStatusCode().value()).as(k.label() + " confirmed").isEqualTo(200);
                verify(is, times(1)).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
            } else verify(is, times(1)).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
        }
        // existing validation still refuses before any alert (alerts never bypass business rules)
        assertThat(ic.createIncome(new LinkedHashMap<>(Map.of("subSourceId", 1, "incomeDate", today.toString(), "transactionTypeId", 11, "amount", "-5",
                "alertsConfirmed", true)), pat).getStatusCode().value()).isEqualTo(400);
        // another login without alerts: saved straight away
        clearInvocations(is);
        assertThat(ic.createIncome(new LinkedHashMap<>(Map.of("subSourceId", 1, "incomeDate", today.plusDays(300).toString(), "transactionTypeId", 11,
                "amount", "90000")), req("lee", "Accountant")).getStatusCode().value()).isEqualTo(200);

        ExpenseService es = mock(ExpenseService.class);
        when(es.createExpense(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean()))
            .thenAnswer(i -> { Expense x = new Expense(); x.setId(88); return x; });
        ExpenseController ec = new ExpenseController(es);
        ReflectionTestUtils.setField(ec, "entryAlerts", alerts);
        Map<String, Object> eb = new LinkedHashMap<>(Map.of("purposeId", 31, "mainSourceId", 21, "expenseDate", today.toString(), "transactionTypeId", 11, "amount", "2500"));
        ResponseEntity<Map<String, Object>> er = ec.create(eb, pat);
        assertThat(er.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(list(er.getBody().get("alerts")).get(0).get("message"))).contains("$2,500.00", "greater than $2,000.00", "expense alerts");
        verifyNoInteractions(es);
        eb.put("alertsConfirmed", true);
        assertThat(ec.create(eb, pat).getStatusCode().value()).isEqualTo(200);
        // the settings of the INCOME kind do not apply to expenses (backdated expense, no expense date alert)
        assertThat(ec.create(new LinkedHashMap<>(Map.of("purposeId", 31, "mainSourceId", 21, "expenseDate", today.minusDays(500).toString(),
                "transactionTypeId", 11, "amount", "20")), pat).getStatusCode().value()).isEqualTo(200);
    }

    // ── 4. Genius Assistant: the same alerts ─────────────────────────────────

    @Test @DisplayName("Genius Assistant uses the same alert service: same settings + entry → same alerts, shown before the card, continue / change / cancel")
    void assistantAlerts() {
        MockHttpServletRequest pat = req("pat", "Accountant");
        saveSettings(pat, "income", settings("maxEnabled", true, "maxAmount", "1000", "futureEnabled", true, "futureDays", "7"));
        Map<String, Object> m = say(pat, "Add an income of $2500 from Anson Mathew to the Tithe Fund by cash today");
        assertThat(m).containsEntry("asking", "alerts");
        assertThat(list(m.get("alerts"))).extracting(a -> a.get("message"))
            .containsExactlyElementsOf(alerts.check(new EntryAlertService.Owner(CID, "pat", "Accountant", null), Kind.INCOME, today, new BigDecimal("2500.00"), today)
                .stream().map(EntryAlertService.Alert::message).toList());
        assertThat(String.valueOf(m.get("answer"))).contains("one of your alerts was triggered", "greater than $1,000.00");
        assertThat(list(m.get("alerts"))).hasSize(1);
        assertThat((List<?>) m.get("choices")).asList().containsExactly("Yes, continue to review", "Change the amount", "Cancel");
        assertThat(store).isEmpty();                                           // no card, no proposal yet
        // change the amount → the alert is checked again; under the limit → straight on
        m = say(pat, "Change the amount");
        assertThat(m).containsEntry("asking", "amount");
        m = say(pat, "800");
        assertThat(m).containsEntry("asking", "optional");                  // no alert any more → on to the optional fields
        assertThat(say(pat, "Skip and review")).containsEntry("intent", "guided.confirm");
        // a second entry above the limit: continue → the card → saved once
        MockHttpServletRequest r2 = req("pat", "Accountant");
        m = say(r2, "Add an income of $2500 from Anson Mathew to the Tithe Fund by cash today");
        m = say(r2, "Yes, continue to review");
        assertThat(m).containsEntry("asking", "optional");
        m = say(r2, "Skip and review");
        assertThat(m).containsEntry("intent", "guided.confirm");
        m = say(r2, "yes");
        assertThat(m).containsEntry("intent", "action.done");
        verify(incomes, times(1)).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString());
        // no to the alerts → cancelled, nothing saved
        MockHttpServletRequest r3 = req("pat", "Accountant");
        say(r3, "Add an income of $2500 from Anson Mathew to the Tithe Fund by cash today");
        assertThat(say(r3, "No")).containsEntry("intent", "guided.cancelled");
        // another login without alerts → straight to the card
        assertThat(say(req("lee", "Accountant"), "Add an income of $2500 from Anson Mathew to the Tithe Fund by cash today")).containsEntry("asking", "optional");
        // a future-dated expense with this login's expense alert
        saveSettings(pat, "expense", settings("futureEnabled", true, "futureDays", "7"));
        MockHttpServletRequest r4 = req("pat", "Accountant");
        String later = US.format(today.plusDays(20));
        m = say(r4, "Add an expense of $50 for Utilities from the General Fund by cash on " + later);
        assertThat(m).containsEntry("asking", "date");                       // the conversation's own future-date question first
        m = say(r4, "Yes, keep it");
        assertThat(m).containsEntry("asking", "alerts");
        assertThat((List<?>) m.get("choices")).asList().contains("Change the date");
    }

    // ── 5. Genius Assistant: invalid input (spec §4) ─────────────────────────

    @Test @DisplayName("invalid date: “30/11/2026” → MM/DD/YYYY message + Did you mean 11/30/2026?; nothing saved until a valid date; the rest of the request kept")
    void invalidDate() {
        MockHttpServletRequest r = req("pat", "Accountant");
        int y = today.getYear() + 1;                                          // a future year keeps the date unambiguous
        Map<String, Object> m = say(r, "Add an income of $100 from Anson Mathew to the Tithe Fund by cash on 30/11/" + y + ".");
        assertThat(m).containsEntry("asking", "date").containsEntry("invalid", "date");
        assertThat(m.get("answer")).isEqualTo("The date format is invalid. Please enter the date in MM/DD/YYYY format. Did you mean 11/30/" + y + "?");
        assertThat((List<?>) m.get("choices")).asList().containsExactly("11/30/" + y, "Today");
        assertThat(store).isEmpty();
        // a second invalid date → said again, still nothing saved
        m = say(r, "31/02/" + y);
        assertThat(m.get("answer")).isEqualTo("The date format is invalid. Please enter the date in MM/DD/YYYY format.");
        // the suggested date → amount, contributor, fund and method were kept from the first message
        m = say(r, "11/30/" + y);
        assertThat(m).containsEntry("asking", "date");                       // the conversation's own future-date question
        m = say(r, "Yes, keep it");
        m = say(r, "Skip and review");
        assertThat(m).containsEntry("intent", "guided.confirm");
        assertThat(AiGuidedRecordTest.summary(m)).containsEntry("Amount", "$100.00").containsEntry("Contributor", "Anson Mathew")
            .containsEntry("Fund", "Tithe Fund").containsEntry("Method", "Cash");
        // no day-month guess either way: 13/45 has no suggestion
        assertThat(say(req("pat", "Accountant"), "Add an income of $100 from Anson Mathew on 13/45/2026").get("answer"))
            .isEqualTo("The date format is invalid. Please enter the date in MM/DD/YYYY format.");
        assertThat(AiGuidedRecordService.badDate("on 2026-02-30", today)).isNotNull();
        assertThat(AiGuidedRecordService.badDate("on 11/30/2026", today)).isNull();
        verifyNoInteractions(incomes);
    }

    @Test @DisplayName("invalid amount: “income of Anson” / “$abc” / “-50” / “fifty” / “10.555” → amount message; Anson is not guessed as the contributor")
    void invalidAmount() {
        MockHttpServletRequest r = req("pat", "Accountant");
        Map<String, Object> m = say(r, "Add an income of Anson on the Tithe Fund.");
        assertThat(m).containsEntry("asking", "amount").containsEntry("invalid", "amount");
        assertThat(m.get("answer")).isEqualTo("The amount is invalid. Please enter a valid numeric amount, such as $100.");
        // the answer: still no number → said again
        assertThat(say(r, "a hundred").get("answer")).isEqualTo("The amount is invalid. Please enter a valid numeric amount, such as $100.");
        m = say(r, "$100");
        assertThat(m).as("next: the contributor — Anson was NOT taken from “income of Anson”").containsEntry("asking", "contributor");
        for (String q : List.of("Add an income of $abc from Anson Mathew", "Add an income of -50 from Anson Mathew", "Add an income of $10.555 from Anson Mathew",
                                "add an expense of $-20 for Utilities")) {
            Map<String, Object> x = say(req("pat", "Accountant"), q);
            assertThat(x).as(q).containsEntry("asking", "amount");
            assertThat(x.get("answer")).as(q).isEqualTo("The amount is invalid. Please enter a valid numeric amount, such as $100.");
        }
        // a correction with no number
        MockHttpServletRequest r2 = req("pat", "Accountant");
        say(r2, "Add an income of $100 from Anson Mathew to the Tithe Fund by cash today");
        m = say(r2, "change the amount to lots");
        assertThat(m.get("answer")).isEqualTo("The amount is invalid. Please enter a valid numeric amount, such as $100.");
        verifyNoInteractions(incomes);
    }

    @Test @DisplayName("missing / unknown values: asks only for what is missing; an unknown fund is named, never replaced by a guess")
    void missingAndUnknown() {
        MockHttpServletRequest r = req("pat", "Accountant");
        Map<String, Object> m = say(r, "Add an income of $100 to the Mission Fund by cash today");
        assertThat(m.get("asking")).isEqualTo("fund");
        assertThat(String.valueOf(m.get("answer"))).startsWith("I couldn't find a fund called “Mission Fund” in this church's funds.")
            .contains("Which fund is it for?");
        m = say(r, "Tithe Fund");
        assertThat(m).containsEntry("asking", "contributor");                 // only the missing contributor is asked
        m = say(r, "Anson Mathew");
        assertThat(m).containsEntry("asking", "optional");
        // a contributor who is not a member is never guessed — asked as a guest
        MockHttpServletRequest r2 = req("pat", "Accountant");
        m = say(r2, "Add an income of $100 from Jonny Unknown to the Tithe Fund by cash today");
        assertThat(String.valueOf(m.get("answer"))).contains("I couldn't find a member called “Jonny Unknown”");
        // nothing given → the first missing field
        assertThat(say(req("pat", "Accountant"), "add an expense")).containsEntry("asking", "category");
        verifyNoInteractions(incomes);
    }

    // ── 6. the saved confirmation ─────────────────────────────────────────────

    @Test @DisplayName("saved confirmation: details + Previous / Added / New Net Balance (the dashboard figure) — only after the save; no total when it cannot be read reliably")
    void confirmation() {
        MockHttpServletRequest r = req("pat", "Accountant");
        LocalDate d = today.minusDays(3);
        Map<String, Object> m = say(r, "Add an income of $100 from Anson Mathew to the Tithe Fund by cash on " + US.format(d));
        m = say(r, "Skip and review");
        assertThat(m).containsEntry("intent", "guided.confirm").doesNotContainKey("receipt");    // not before the save
        m = say(r, "yes");
        assertThat(m).containsEntry("intent", "action.done");
        Map<String, Object> rc = m(m.get("receipt"));
        assertThat(rc).containsEntry("title", "Income of $100.00 was added successfully.");
        assertThat(list(rc.get("rows"))).extracting(x -> x.get("label") + ": " + x.get("value"))
            .containsExactly("Contributor: Anson Mathew", "Fund: Tithe Fund", "Payment Method: Cash", "Transaction Date: " + US.format(d));
        // 2026-11-10: the dashboard's Net Balance (income minus expenses, all funds), not the fund's income total
        assertThat(m(rc.get("totals"))).containsEntry("label", "Net Balance (income minus expenses, all funds)")
            .containsEntry("previousLabel", "Previous Net Balance").containsEntry("previous", "$3,402.26")
            .containsEntry("addedLabel", "Added Income").containsEntry("added", "+$100.00")
            .containsEntry("nextLabel", "New Net Balance").containsEntry("next", "$3,502.26");
        assertThat(String.valueOf(m.get("answer"))).startsWith("Income of $100.00 was added successfully.")
            .contains("Previous Net Balance: $3,402.26", "Added Income: +$100.00", "New Net Balance: $3,502.26");
        // expense
        MockHttpServletRequest r2 = req("pat", "Accountant");
        say(r2, "Add an expense of $250.50 for Utilities from the General Fund by check today");
        say(r2, "Skip and review");
        m = say(r2, "yes");
        rc = m(m.get("receipt"));
        assertThat(rc).containsEntry("title", "Expense of $250.50 was added successfully.");
        assertThat(list(rc.get("rows"))).extracting(x -> x.get("label")).containsExactly("Expense Category", "Fund", "Payment Method", "Transaction Date");
        assertThat(m(rc.get("totals"))).containsEntry("previous", "$3,502.26").containsEntry("addedLabel", "Added Expense")
            .containsEntry("added", "\u2212$250.50").containsEntry("next", "$3,251.76");
        // totals that do not differ by exactly the amount (another entry at the same moment) → no figures, a note instead
        when(fin.netBalance(CID)).thenReturn(new BigDecimal("10.00"), new BigDecimal("999.00"));
        MockHttpServletRequest r3 = req("pat", "Accountant");
        say(r3, "Add an income of $100 from Anson Mathew to the Building Fund by cash today");
        say(r3, "Skip and review");
        rc = m(say(r3, "yes").get("receipt"));
        assertThat(rc).doesNotContainKey("totals");
        assertThat(String.valueOf(rc.get("totalsNote"))).contains("could not be shown reliably");
        // a lookup that fails → same
        when(fin.netBalance(CID)).thenReturn(null);
        MockHttpServletRequest r4 = req("pat", "Accountant");
        say(r4, "Add an income of $100 from Anson Mathew to the Building Fund by cash today");
        say(r4, "Skip and review");
        assertThat(m(say(r4, "yes").get("receipt"))).doesNotContainKey("totals");
    }

    @Test @DisplayName("no duplicate saves: a corrected request saves once; the spent token cannot save again; a failed save saves nothing")
    void noDuplicates() {
        MockHttpServletRequest r = req("pat", "Accountant");
        say(r, "Add an income of $100 from Anson Mathew to the Tithe Fund by cash on 30/11/2030");
        say(r, "11/30/2030"); say(r, "Yes, keep it");
        Map<String, Object> card = say(r, "Skip and review");
        String token = AiGuidedRecordTest.token(card);
        Map<String, Object> card2 = say(r, "change the amount to 120");                 // correction → new card, old token withdrawn
        String token2 = AiGuidedRecordTest.token(card2);
        assertThat(token2).isNotEqualTo(token);
        assertThat(actions.confirm(AiGuidedRecordTest.same(r), CID, token).get("intent")).isNotEqualTo("action.done");
        assertThat(actions.confirm(AiGuidedRecordTest.same(r), CID, token2)).containsEntry("intent", "action.done");
        assertThat(actions.confirm(AiGuidedRecordTest.same(r), CID, token2).get("intent")).isNotEqualTo("action.done");   // retry
        verify(incomes, times(1)).createIncome(any(), anyInt(), any(), anyInt(), any(), eq(new BigDecimal("120.00")), any(), any(), anyBoolean(), anyString(), anyString());
        // a save that fails → nothing kept, an honest message
        reset(incomes);
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        MockHttpServletRequest r2 = req("pat", "Accountant");
        say(r2, "Add an income of $100 from Anson Mathew to the Tithe Fund by cash today");
        say(r2, "Skip and review");
        Map<String, Object> fail = say(r2, "yes");
        assertThat(fail).containsEntry("intent", "action.failed").doesNotContainKey("receipt");
    }

    // ── 7. wiring ─────────────────────────────────────────────────────────────

    @Test @DisplayName("wiring: every entry page handles ENTRY_ALERTS through the shared script; one alert service; settings removed with the login; migration")
    void wiring() throws Exception {
        String inc = Files.readString(Path.of("src/main/resources/static/income.html"));
        String exp = Files.readString(Path.of("src/main/resources/static/expense.html"));
        String home = Files.readString(Path.of("src/main/resources/static/home.html"));
        String bank = Files.readString(Path.of("src/main/resources/static/bank-import.html"));
        String js = Files.readString(Path.of("src/main/resources/static/entry-alerts.js"));
        assertThat(inc).contains("<script src=\"/entry-alerts.js\"></script>", "CGP_entryAlerts.post(url, payload, { noun: 'income' })",
                                 "CGP_entryAlerts.mountSettings({ kind: 'income', anchor: 'formCard' })");
        assertThat(exp).contains("<script src=\"/entry-alerts.js\"></script>", "CGP_entryAlerts.post(url, payload, { noun: 'expense' })",
                                 "CGP_entryAlerts.mountSettings({ kind: 'expense', anchor: 'expenseFormCard' })");
        assertThat(home).contains("<script src=\"/entry-alerts.js\"></script>", "e.body.code === 'ENTRY_ALERTS'", "alertsConfirmed: true");
        assertThat(bank).contains("<script src=\"/entry-alerts.js\"></script>", "res.j.code==='ENTRY_ALERTS'", "payload.alertsConfirmed = true");
        assertThat(js).contains("code === 'ENTRY_ALERTS'", "alertsConfirmed: true", "'Save anyway'", "'Go back and review'", "textContent")
                      .doesNotContain("innerHTML", "alert(", "confirm(");
        String guided = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/guided/AiGuidedRecordService.java"));
        assertThat(guided).contains("entryAlerts.check(").doesNotContain("EntryAlertService.evaluate(");
        for (String ctl : List.of("IncomeController", "ExpenseController"))
            assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/" + ctl + ".java")))
                .contains("entryAlerts.check(EntryAlertService.owner(request)", "Boolean.TRUE.equals(body.get(\"alertsConfirmed\"))");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/StaffAccountDeletionService.java")))
            .contains("entryAlerts.deleteForLogin(appClientId, username)");
        assertThat(Files.readString(Path.of("scripts/sql/migrate_production.sql")))
            .contains("CREATE TABLE IF NOT EXISTS entry_alert_setting", "ux_entry_alert_login_role_kind UNIQUE (app_client_id, username, role, kind)");
        String engine = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(engine).contains("d.receipt && typeof d.receipt === 'object'", "class=\"aa-receipt\"", "class=\"aa-alerts\"", "Previous total", "New total");
        // highlight: only within the first 100 entries
        assertThat(inc).contains("const NEW_ROW_LIMIT = 100;", "idx < 0 || idx >= NEW_ROW_LIMIT", "It is an older transaction");
        assertThat(exp).contains("const NEW_ROW_LIMIT = 100;", "idx < 0 || idx >= NEW_ROW_LIMIT", "It is an older transaction");
    }
}

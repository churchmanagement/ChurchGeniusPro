package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.AiActionProposalRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-08 — guided "add an income / expense" in the AI Search bar: one question at a time, the optional
 * Reference Number / Notes question, the review question, the review card, explicit Save through the action agent's
 * one-time token, corrections, duplicates, future dates, permission (Income / Expense page write rule) on every
 * message and at confirm, plan gate, church scoping, the model fallback's inputs, guided-only tools.
 */
@DisplayName("AI Search — guided income and expense conversations")
class AiGuidedRecordTest {

    static final String CID = "CHR-A";
    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    static final String OPTIONAL_Q = "You haven't entered the optional Reference Number (useful for check payments) or Notes. "
            + "Would you like to add either of these, or should I skip them and proceed to review?";

    AiActionLookup people; AiFinanceLookup fin; IncomeService incomes; ExpenseService expenses; OpenAiVoiceService openAi;
    AiActionProposalRepository repo; Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiToolRegistry registry; AiActionService actions; AiAccessPolicy policy; AiGuidedRecordService svc;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = AppClock.today();
        people = mock(AiActionLookup.class); fin = mock(AiFinanceLookup.class); incomes = mock(IncomeService.class); expenses = mock(ExpenseService.class);
        openAi = mock(OpenAiVoiceService.class);
        when(people.members(eq(CID), anyString())).thenReturn(List.of());
        when(people.members(eq(CID), ci("john smith"))).thenReturn(List.of(new Hit(5, "John Smith", "1")));
        when(people.members(eq(CID), ci("mark"))).thenReturn(List.of(new Hit(6, "Mark Brooks", "2"), new Hit(7, "Mark Lee", "3")));
        when(people.memberById(eq(CID), anyLong())).thenAnswer(i -> switch ((int) (long) (Long) i.getArgument(1)) {
            case 5 -> new Hit(5, "John Smith", "1"); case 6 -> new Hit(6, "Mark Brooks", "2"); case 7 -> new Hit(7, "Mark Lee", "3"); default -> null; });
        List<Hit> funds = List.of(new Hit(1, "Tithe", null), new Hit(2, "Building Fund", null), new Hit(3, "General", null), new Hit(4, "Missions", null));
        List<Hit> xfunds = List.of(new Hit(21, "General Fund", null), new Hit(22, "Building Fund", null));
        List<Hit> cats = List.of(new Hit(31, "Utilities", null), new Hit(32, "Office Supplies", null), new Hit(33, "Youth Ministry", null));
        List<Hit> methods = List.of(new Hit(11, "Cash", null), new Hit(12, "Check", null), new Hit(13, "Zelle", null));
        when(fin.incomeFunds(CID)).thenReturn(funds); when(fin.expenseFunds(CID)).thenReturn(xfunds);
        when(fin.expenseCategories(CID)).thenReturn(cats); when(fin.methods(CID)).thenReturn(methods);
        when(fin.incomeFundById(eq(CID), anyLong())).thenAnswer(i -> byId(funds, i.getArgument(1)));
        when(fin.expenseFundById(eq(CID), anyLong())).thenAnswer(i -> byId(xfunds, i.getArgument(1)));
        when(fin.expenseCategoryById(eq(CID), anyLong())).thenAnswer(i -> byId(cats, i.getArgument(1)));
        when(fin.methodById(eq(CID), anyLong())).thenAnswer(i -> byId(methods, i.getArgument(1)));
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString()))
                .thenAnswer(i -> { Income x = new Income(); x.setId(501); return x; });
        when(expenses.createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString()))
                .thenAnswer(i -> { Expense x = new Expense(); x.setId(601); return x; });
        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        registry = new AiToolRegistry(List.of(new AiFinanceTools.CreateIncome(people, fin, incomes), new AiFinanceTools.CreateExpense(fin, expenses)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        policy = spy(new AiAccessPolicy());
        actions = new AiActionService(registry, repo, policy, openAi, new TransactionTemplate(tm), true, true, 10);
        svc = new AiGuidedRecordService(policy, actions, people, fin, openAi);
        svc.llmFallback = false;
    }

    static Hit byId(List<Hit> list, Object id) { long v = (Long) id; return list.stream().filter(h -> h.id() == v).findFirst().orElse(null); }
    static String ci(String expected) { return argThat(a -> a != null && a.trim().replaceAll("\\s+", " ").equalsIgnoreCase(expected)); }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        if (privileges != null) r.getSession().setAttribute("privileges", privileges);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    Map<String, Object> say(MockHttpServletRequest r, String text) { return svc.handle(same(r), CID, text); }
    @SuppressWarnings("unchecked") static Map<String, Object> confirm(Map<String, Object> m) { return (Map<String, Object>) m.get("confirm"); }
    @SuppressWarnings("unchecked") static Map<String, String> summary(Map<String, Object> m) { return (Map<String, String>) confirm(m).get("summary"); }
    static String token(Map<String, Object> m) { return String.valueOf(confirm(m).get("token")); }

    // ── routing ───────────────────────────────────────────────────────────────

    @Test @DisplayName("start phrases: create / add / record / enter an income or expense (donation, offering, gift = income); questions never")
    void startDetection() {
        for (String q : List.of("Create an income", "add income", "Record a donation of $25", "enter an expense", "please add a new expense",
                "log an offering", "Add $50 income from John Smith", "I want to add an income", "new expense entry"))
            assertThat(AiGuidedRecordService.isStartRequest(q)).as(q).isTrue();
        for (String q : List.of("How do I add an income?", "what was the income last month", "show expenses", "Add John Smith to the Income Committee",
                "income report", "create a meeting", "add Sarah Lee to Welcome Team"))
            assertThat(AiGuidedRecordService.isStartRequest(q)).as(q).isFalse();
        assertThat(svc.handle(req("Accountant", null), CID, "what was the income last month")).isNull();
    }

    // ── income ────────────────────────────────────────────────────────────────

    @Test @DisplayName("one sentence with everything (incl. the reference number) → straight to the card; Save writes once through IncomeService")
    void oneShotIncome() {
        MockHttpServletRequest r = req("Accountant", null);
        LocalDate d = today.minusDays(2);
        Map<String, Object> m = svc.handle(r, CID, "Add an income of $50 from John Smith to Tithe by check #1234 on " + d);
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(m.get("pending")).isEqualTo(true);
        assertThat(confirm(m).get("label")).isEqualTo("Save income");
        assertThat(confirm(m).get("endpoint")).isEqualTo("/api/ai/actions/confirm");
        assertThat(summary(m)).containsEntry("Contributor", "John Smith").containsEntry("Fund", "Tithe").containsEntry("Amount", "$50.00")
                .containsEntry("Date", D.format(d)).containsEntry("Method", "Check").containsEntry("Reference number", "1234")
                .containsEntry("Notes", "Not entered (optional)");
        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = (List<Map<String, Object>>) confirm(m).get("fields");
        assertThat(fields).extracting(f -> f.get("key")).containsExactly("contributor", "fund", "amount", "date", "method", "refNo", "note");
        assertThat(fields).extracting(f -> f.get("required")).containsExactly(true, true, true, true, true, false, false);
        assertThat(fields.get(2).get("edit")).isEqualTo("Change amount to ");
        verifyNoInteractions(incomes);

        Map<String, Object> done = say(r, "yes");
        assertThat(done.get("intent")).isEqualTo("action.done");
        assertThat(done.get("kind")).isEqualTo("income");
        verify(incomes, times(1)).createIncome(eq(5), eq(1), eq(d), eq(12), eq("1234"), eq(new BigDecimal("50.00")), isNull(), isNull(), eq(false), eq(CID), eq("pat"));
        // a repeated "yes" or a second button press saves nothing more
        assertThat(say(r, "yes")).isNull();
        assertThat(actions.confirm(same(r), CID, token(m)).get("intent")).isEqualTo("action.expired");
        verify(incomes, times(1)).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString());
    }

    @Test @DisplayName("step by step: contributor (several Marks) → amount → fund (by number) → date → method → optional question → ref → review question → card → button")
    void stepByStepIncome() {
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add an income");
        assertThat(m.get("intent")).isEqualTo("guided.ask");
        assertThat(m.get("asking")).isEqualTo("contributor");
        assertThat(m.get("choices")).isEqualTo(List.of("Anonymous", "Loose Offering"));
        assertThat(m.get("missing")).isEqualTo(List.of("contributor", "amount", "fund", "date", "method"));
        m = say(r, "Mark");
        assertThat(m.get("asking")).isEqualTo("contributor");
        assertThat(m.get("choices")).isEqualTo(List.of("Mark Brooks", "Mark Lee"));
        m = say(r, "Mark Lee");
        assertThat(m.get("asking")).isEqualTo("amount");
        m = say(r, "75");
        assertThat(m.get("asking")).isEqualTo("fund");
        assertThat(m.get("choices")).isEqualTo(List.of("Tithe", "Building Fund", "General", "Missions"));
        m = say(r, "2");
        assertThat(m.get("asking")).isEqualTo("date");
        m = say(r, "today");
        assertThat(m.get("asking")).isEqualTo("method");
        m = say(r, "Cash");
        assertThat(m.get("asking")).isEqualTo("optional");
        assertThat(m.get("answer")).isEqualTo(OPTIONAL_Q);
        assertThat(m.get("choices")).isEqualTo(List.of("Add a reference number", "Add notes", "Skip and review"));
        m = say(r, "Add a reference number");
        assertThat(m.get("asking")).isEqualTo("refNo");
        m = say(r, "A-778");
        assertThat(m.get("asking")).isEqualTo("review");
        assertThat(m.get("answer")).isEqualTo("Are you missing anything, or are you ready to review the income details?");
        m = say(r, "ready");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(summary(m)).containsEntry("Contributor", "Mark Lee").containsEntry("Fund", "Building Fund").containsEntry("Amount", "$75.00")
                .containsEntry("Method", "Cash").containsEntry("Reference number", "A-778");
        verifyNoInteractions(incomes);
        // the card's button (POST /api/ai/actions/confirm) saves; the conversation then ends by itself
        assertThat(actions.confirm(same(r), CID, token(m)).get("intent")).isEqualTo("action.done");
        verify(incomes).createIncome(eq(7), eq(2), eq(today), eq(11), eq("A-778"), eq(new BigDecimal("75.00")), isNull(), isNull(), eq(false), eq(CID), eq("pat"));
        assertThat(say(r, "yes")).isNull();
        assertThat(r.getSession().getAttribute(AiGuidedRecordService.SESSION_KEY)).isNull();
    }

    @Test @DisplayName("contributors: unknown name → record as guest?; Anonymous / Loose Offering; \"guest Mary\"; skip goes straight to the card")
    void guests() {
        MockHttpServletRequest r = req("SuperAdmin", null);
        svc.handle(r, CID, "add an income of $20 to General by cash today");
        Map<String, Object> m = say(r, "Zed Quinn");
        assertThat(String.valueOf(m.get("answer"))).contains("couldn't find a member called “Zed Quinn”");
        assertThat(m.get("choices")).isEqualTo(List.of("Yes, record as a guest", "No, choose another name"));
        m = say(r, "Yes, record as a guest");
        assertThat(m.get("asking")).isEqualTo("optional");
        m = say(r, "Skip and review");
        assertThat(m.get("intent")).as("skip = proceed to review").isEqualTo("guided.confirm");
        assertThat(summary(m).get("Contributor")).isEqualTo("Zed Quinn (guest)");
        say(r, "yes");
        verify(incomes).createIncome(isNull(), eq(3), eq(today), eq(11), isNull(), eq(new BigDecimal("20.00")), isNull(), eq("Zed Quinn"), eq(false), eq(CID), eq("pat"));

        MockHttpServletRequest r2 = req("SuperAdmin", null);
        m = svc.handle(r2, CID, "add an income of $40 from Loose Offering to Tithe by cash today note: Sunday plate");
        assertThat(summary(m).get("Contributor")).isEqualTo("Loose Offering (guest)");
        assertThat(summary(m).get("Notes")).isEqualTo("Sunday plate");
        MockHttpServletRequest r3 = req("SuperAdmin", null);
        svc.handle(r3, CID, "add an income");
        m = say(r3, "Anonymous");
        assertThat(m.get("asking")).isEqualTo("amount");
        MockHttpServletRequest r4 = req("SuperAdmin", null);
        svc.handle(r4, CID, "add an income");
        say(r4, "guest Mary Jones");
        m = say(r4, "$10");
        assertThat(m.get("asking")).isEqualTo("fund");
        verify(people, never()).members(eq(CID), ci("mary jones"));
    }

    // ── expense + corrections ─────────────────────────────────────────────────

    @Test @DisplayName("expense: category, fund, method from one sentence; skip optional → card; corrections replace the card and withdraw the old token")
    void expenseAndCorrections() {
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "Record an expense of $120.50 for Utilities from General Fund by Zelle yesterday");
        assertThat(m.get("asking")).isEqualTo("optional");
        m = say(r, "skip");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(confirm(m).get("label")).isEqualTo("Save expense");
        assertThat(summary(m)).containsEntry("Expense category", "Utilities").containsEntry("Fund", "General Fund").containsEntry("Amount", "$120.50")
                .containsEntry("Method", "Zelle").containsEntry("Date", D.format(today.minusDays(1)));
        String old = token(m);
        m = say(r, "change the amount to 99");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(summary(m).get("Amount")).isEqualTo("$99.00");
        assertThat(actions.confirm(same(r), CID, old).get("intent")).as("the replaced card cannot save").isEqualTo("action.expired");
        m = say(r, "actually Check");
        assertThat(summary(m).get("Method")).isEqualTo("Check");
        m = say(r, "change category to Office Supplies");
        assertThat(summary(m).get("Expense category")).isEqualTo("Office Supplies");
        m = say(r, "change fund to Building Fund");
        assertThat(summary(m).get("Fund")).isEqualTo("Building Fund");
        m = say(r, "change notes to printer paper");
        assertThat(summary(m).get("Notes")).isEqualTo("printer paper");
        m = say(r, "change the date");
        assertThat(m.get("asking")).isEqualTo("date");
        m = say(r, "Nov 2");
        assertThat(m.get("intent")).as("already reviewed → back to the card").isEqualTo("guided.confirm");
        verifyNoInteractions(expenses);
        say(r, "save");
        LocalDate nov2 = LocalDate.of(today.getYear(), 11, 2);
        if (nov2.isAfter(today.plusDays(7))) nov2 = nov2.minusYears(1);
        verify(expenses, times(1)).createExpense(eq(32), eq(22), eq(nov2), eq(12), isNull(), eq(new BigDecimal("99.00")), eq("printer paper"), eq(false), eq(CID), eq("pat"));
    }

    @Test @DisplayName("future date confirmed once; amount must be > 0, ≤ the page maximum, two decimals; duplicate needs \"Save anyway\"")
    void checks() {
        MockHttpServletRequest r = req("Accountant", null);
        LocalDate fut = today.plusDays(3);
        Map<String, Object> m = svc.handle(r, CID, "add an income of $30 from John Smith to Tithe by cash on " + fut + " ref 55");
        assertThat(String.valueOf(m.get("answer"))).contains("is in the future");
        m = say(r, "Use a different date");
        assertThat(m.get("asking")).isEqualTo("date");
        m = say(r, "today");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        MockHttpServletRequest r2 = req("Accountant", null);
        svc.handle(r2, CID, "add an income of $30 from John Smith to Tithe by cash on " + fut + " ref 55");
        m = say(r2, "Yes, keep it");
        assertThat(summary(m).get("Date")).isEqualTo(D.format(fut));

        for (String bad : List.of("$0", "$0.00", "$99999999999999", "$5.123")) {
            MockHttpServletRequest rb = req("Accountant", null);
            svc.handle(rb, CID, "add an income from John Smith to Tithe by cash today ref 1");
            Map<String, Object> a = say(rb, bad);
            assertThat(a.get("asking")).as(bad).isEqualTo("amount");
            assertThat(a).as(bad).doesNotContainKey("confirm");
        }

        when(fin.incomeDuplicates(eq(CID), eq(today), eq(new BigDecimal("30.00")), eq(1L), eq(5L), isNull())).thenReturn(1);
        MockHttpServletRequest r3 = req("Accountant", null);
        m = svc.handle(r3, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        assertThat(String.valueOf(m.get("answer"))).contains("is already recorded").contains("Save another one anyway?");
        assertThat(m.get("choices")).isEqualTo(List.of("Save anyway", "Cancel"));
        m = say(r3, "Save anyway");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        MockHttpServletRequest r4 = req("Accountant", null);
        svc.handle(r4, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        assertThat(say(r4, "Cancel").get("intent")).isEqualTo("guided.cancelled");
        verifyNoInteractions(incomes);
    }

    @Test @DisplayName("cancel works at every step; another question leaves the draft; the card's Cancel ends it")
    void cancelAndOtherQuestions() {
        for (String stepAnswers : List.of("", "John Smith", "John Smith|$5|Tithe|today|cash", "John Smith|$5|Tithe|today|cash|skip")) {
            MockHttpServletRequest r = req("Accountant", null);
            svc.handle(r, CID, "add an income");
            if (!stepAnswers.isEmpty()) for (String a : stepAnswers.split("\\|")) say(r, a);
            assertThat(say(r, "cancel").get("intent")).as(stepAnswers).isEqualTo("guided.cancelled");
            assertThat(say(r, "yes")).as(stepAnswers).isNull();
        }
        MockHttpServletRequest r = req("Accountant", null);
        svc.handle(r, CID, "add an income");
        assertThat(say(r, "what was the income last month?")).isNull();
        assertThat(say(r, "John Smith").get("asking")).isEqualTo("amount");      // still there
        // the card's Cancel button (POST /api/ai/actions/reject) also ends the conversation
        MockHttpServletRequest r2 = req("Accountant", null);
        Map<String, Object> m = svc.handle(r2, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        actions.reject(same(r2), CID, token(m));
        assertThat(say(r2, "yes")).isNull();
        verifyNoInteractions(incomes);
    }

    // ── permission, plan, church ──────────────────────────────────────────────

    @Test @DisplayName("only logins with the Income / Expense write permission: role, page and edit key — checked first, every step and at Save")
    void permissions() {
        String noIncomeEdit = "{\"accounting\":true,\"accounting.income\":true,\"accounting.income.edit\":false,\"accounting.expense\":true,\"accounting.expense.edit\":true}";
        String noExpensePage = "{\"accounting\":true,\"accounting.income\":true,\"accounting.income.edit\":true,\"accounting.expense\":false,\"accounting.expense.edit\":true}";
        String adminWithIncome = "{\"accounting\":true,\"accounting.income\":true,\"accounting.income.edit\":true,\"accounting.expense\":false}";
        record C(String role, String privs, boolean income, boolean expense) {}
        for (C c : List.of(new C("SuperAdmin", null, true, true), new C("Accountant", null, true, true), new C("Admin", null, false, false),
                new C("Admin", adminWithIncome, true, false), new C("User", null, false, false), new C("Accountant", noIncomeEdit, false, true),
                new C("Accountant", noExpensePage, true, false))) {
            Map<String, Object> i = svc.handle(req(c.role(), c.privs()), CID, "add an income");
            Map<String, Object> e = svc.handle(req(c.role(), c.privs()), CID, "add an expense");
            assertThat(i.get("intent")).as(c + " income").isEqualTo(c.income() ? "guided.ask" : "guided.denied");
            assertThat(e.get("intent")).as(c + " expense").isEqualTo(c.expense() ? "guided.ask" : "guided.denied");
        }
        // member, Kids Portal and church logins (and every refusal above) look nothing up
        clearInvocations(fin, people);
        assertThat(svc.handle(req("User", null), CID, "add an income of $5 from John Smith to Tithe").get("intent")).isEqualTo("guided.denied");
        MockHttpServletRequest member = req("Member", null); member.getSession().setAttribute("memberId", 9);
        assertThat(svc.handle(member, CID, "add an income").get("intent")).isEqualTo("guided.denied");
        MockHttpServletRequest church = req("SuperAdmin", null); church.getSession().setAttribute("church", true);
        assertThat(svc.handle(church, CID, "add an income").get("intent")).isEqualTo("guided.denied");
        verifyNoInteractions(fin, people);          // nothing is looked up for a refused login

        // permission withdrawn mid-conversation → refused and the draft is gone
        MockHttpServletRequest r = req("Accountant", null);
        svc.handle(r, CID, "add an income");
        r.getSession().setAttribute("privileges", noIncomeEdit);
        assertThat(say(r, "John Smith").get("intent")).isEqualTo("guided.denied");
        assertThat(r.getSession().getAttribute(AiGuidedRecordService.SESSION_KEY)).isNull();
        // withdrawn between the card and Save → nothing saved
        MockHttpServletRequest r2 = req("Accountant", null);
        Map<String, Object> m = svc.handle(r2, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        r2.getSession().setAttribute("privileges", noIncomeEdit);
        assertThat(actions.confirm(same(r2), CID, token(m)).get("intent")).isEqualTo("action.denied");
        verifyNoInteractions(incomes);
    }

    @Test @DisplayName("plan without AI actions → told to use the page, nothing looked up; every lookup uses the signed-in church; tampered ids refused at Save")
    void planAndChurch() {
        doReturn(false).when(policy).feature(anyString(), eq(AiGuidedRecordService.FEATURE));
        assertThat(svc.handle(req("Accountant", null), CID, "add an income").get("intent")).isEqualTo("guided.unavailable");
        verifyNoInteractions(fin, people, openAi);
        doReturn(true).when(policy).feature(anyString(), anyString());

        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        verify(fin, atLeastOnce()).incomeFunds(CID);
        verify(fin, never()).incomeFunds(argThat(c -> !CID.equals(c)));
        verify(people, never()).members(argThat(c -> !CID.equals(c)), anyString());
        // the stored proposal points at another church's fund (tampered) → refused at Save, nothing written
        AiActionProposal p = store.values().iterator().next();
        p.setParamsJson(p.getParamsJson().replace("\"fundId\":1", "\"fundId\":999"));
        Map<String, Object> done = actions.confirm(same(r), CID, token(m));
        assertThat(done.get("intent")).isEqualTo("action.refused");
        verifyNoInteractions(incomes);
        // a token of church A presented in church B finds nothing
        MockHttpServletRequest r2 = req("Accountant", null);
        Map<String, Object> m2 = svc.handle(r2, CID, "add an income of $31 from John Smith to Tithe by cash today ref 9");
        assertThat(actions.confirm(same(r2), "CHR-B", token(m2)).get("intent")).isEqualTo("action.expired");
    }

    // ── model fallback, guided-only tools ─────────────────────────────────────

    @Test @DisplayName("model fallback only when the rules can't read the reply: fund/method names only (no member names); values checked against the church's lists")
    void llmFallback() throws Exception {
        svc.llmFallback = true;
        when(openAi.isEnabled()).thenReturn(true);
        when(openAi.chatJson(anyString(), anyString(), anyDouble())).thenReturn(new ObjectMapper().readTree(
                "{\"amount\":\"45\",\"fund\":\"Missions\",\"method\":\"Venmo\",\"contributor\":\"Johnny Walker\"}"));
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add an income");
        verifyNoInteractions(openAi);                                        // nothing to read → no model call
        say(r, "John Smith");
        m = say(r, "forty five bucks for the missionaries");
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class), user = ArgumentCaptor.forClass(String.class);
        verify(openAi, times(1)).chatJson(sys.capture(), user.capture(), eq(0.0));
        assertThat(sys.getValue()).contains("Missions", "Building Fund", "Cash", "Zelle").doesNotContain("John Smith", "Mark Lee", "Mark Brooks");
        assertThat(user.getValue()).isEqualTo("forty five bucks for the missionaries");
        assertThat(m.get("asking")).as("amount + fund taken; \"Venmo\" is not a method of this church").isEqualTo("date");
        m = say(r, "today"); m = say(r, "cash"); m = say(r, "skip");
        assertThat(summary(m)).containsEntry("Amount", "$45.00").containsEntry("Fund", "Missions").containsEntry("Contributor", "John Smith");
        // a contributor the model names but the user never wrote is ignored
        MockHttpServletRequest r2 = req("Accountant", null);
        svc.handle(r2, CID, "add an income");
        Map<String, Object> a = say(r2, "hmm let me think");
        assertThat(a.get("asking")).isEqualTo("contributor");
        // switched off → no model call at all
        svc.llmFallback = false; clearInvocations(openAi);
        MockHttpServletRequest r3 = req("Accountant", null);
        svc.handle(r3, CID, "add an income");
        say(r3, "John Smith"); say(r3, "forty five bucks for the missionaries");
        verify(openAi, never()).chatJson(anyString(), anyString(), anyDouble());
    }

    @Test @DisplayName("create_income / create_expense are guided-only: not in the LLM tool list, refused by propose(), skipped by MCP list_actions")
    void guidedOnly() throws Exception {
        assertThat(registry.describe()).isEmpty();
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> p = actions.propose(r, r.getSession(), policy.resolve(r, CID), CID, "create_income",
                Map.of("memberId", 5, "fundId", 1, "amount", "5", "date", today.toString(), "methodId", 11), "MCP");
        assertThat(p.get("intent")).isEqualTo("action.refused");
        assertThat(store).isEmpty();
        String mcp = Files.readString(Path.of("src/main/java/com/churchgeniuspro/mcp/McpToolCatalog.java"));
        assertThat(mcp).contains("t.guidedOnly() || !t.allowed(");
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        int start = ctl.indexOf("AiGuidedRecordService.isStartRequest(question)"), meeting = ctl.indexOf("meetingCreate.handle("), act = ctl.indexOf("actions.handle(");
        assertThat(start).isPositive().isLessThan(meeting);
        assertThat(ctl.indexOf("guided.handle(request, clientId, question)", meeting)).isBetween(meeting, act);
        // the proposal is stored as GUIDED and the tools use the pages' own service methods
        String tools = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/action/AiFinanceTools.java"));
        assertThat(tools).contains("incomes.createIncome(").contains("expenses.createExpense(").contains("\"accounting.income.edit\"").contains("\"accounting.expense.edit\"");
    }

    @Test @DisplayName("two Save presses at the same moment: one saves, the other is told the confirmation is no longer valid")
    void concurrentSave() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString()))
                .thenAnswer(i -> { entered.countDown(); release.await(); Income x = new Income(); x.setId(1); return x; });
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add an income of $30 from John Smith to Tithe by cash today ref 9");
        String t = token(m);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Map<String, Object>> first = ex.submit(() -> actions.confirm(same(r), CID, t));
        entered.await();
        Map<String, Object> second = actions.confirm(same(r), CID, t);
        release.countDown();
        assertThat(second.get("intent")).isEqualTo("action.expired");
        assertThat(first.get().get("intent")).isEqualTo("action.done");
        ex.shutdown();
        verify(incomes, times(1)).createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString());
    }
}

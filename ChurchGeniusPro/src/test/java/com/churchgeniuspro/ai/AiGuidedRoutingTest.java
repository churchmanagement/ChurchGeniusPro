package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.ai.guided.AiGuidedSettingService;
import com.churchgeniuspro.controller.AiDataSearchController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-08 final regression — one AI Search route (POST /api/ai-search/data → AiDataSearchController) with the REAL
 * meeting, income / expense, Account Settings and action-agent services behind it: every settings phrase enters the
 * settings conversation, every income / expense phrase the record conversation, meetings stay meetings, answers go to
 * the conversation that asked, starting one conversation ends the other, and each keeps its own permission rule.
 */
@DisplayName("AI Search — routing between meeting, income / expense and Account Settings conversations")
class AiGuidedRoutingTest {

    static final String CID = "CHR-A";
    AiDataSearchService data; AiDataSearchController ctl;
    IncomeService incomes; ExpenseService expenses; TransactionTypeService typeService; PurposeService purposeService; SourceService sourceService;
    MeetingService meetingService;
    Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        // lookups shared by both conversations
        AiActionLookup people = mock(AiActionLookup.class);
        when(people.members(eq(CID), anyString())).thenReturn(List.of());
        when(people.members(eq(CID), argThat(n -> n != null && n.trim().equalsIgnoreCase("john smith")))).thenReturn(List.of(new Hit(5, "John Smith", "1")));
        when(people.memberById(eq(CID), eq(5L))).thenReturn(new Hit(5, "John Smith", "1"));
        AiFinanceLookup fin = mock(AiFinanceLookup.class);
        List<Hit> funds = List.of(new Hit(1, "Tithe", null), new Hit(2, "Building Fund", null));
        List<Hit> mains = List.of(new Hit(21, "General Fund", null), new Hit(22, "Building Fund", null));
        List<Hit> cats = List.of(new Hit(31, "Utilities", null));
        List<Hit> methods = List.of(new Hit(11, "Cash", null), new Hit(12, "Check", null));
        when(fin.incomeFunds(CID)).thenReturn(funds); when(fin.expenseFunds(CID)).thenReturn(mains);
        when(fin.expenseCategories(CID)).thenReturn(cats); when(fin.methods(CID)).thenReturn(methods);
        when(fin.incomeFundById(eq(CID), anyLong())).thenAnswer(i -> by(funds, i.getArgument(1)));
        when(fin.expenseFundById(eq(CID), anyLong())).thenAnswer(i -> by(mains, i.getArgument(1)));
        when(fin.expenseCategoryById(eq(CID), anyLong())).thenAnswer(i -> by(cats, i.getArgument(1)));
        when(fin.methodById(eq(CID), anyLong())).thenAnswer(i -> by(methods, i.getArgument(1)));
        // the pages' services
        incomes = mock(IncomeService.class); expenses = mock(ExpenseService.class);
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString())).thenAnswer(i -> { Income x = new Income(); x.setId(1); return x; });
        when(expenses.createExpense(anyInt(), anyInt(), any(), anyInt(), any(), any(), any(), anyBoolean(), anyString(), anyString())).thenAnswer(i -> { Expense x = new Expense(); x.setId(1); return x; });
        typeService = mock(TransactionTypeService.class); purposeService = mock(PurposeService.class); sourceService = mock(SourceService.class);
        when(typeService.create(anyString(), anyString())).thenAnswer(i -> { TransactionType t = new TransactionType(); t.setId(1); return t; });
        when(purposeService.create(anyString(), anyString())).thenAnswer(i -> { Purpose t = new Purpose(); t.setId(1); return t; });
        when(sourceService.createMainSource(anyString(), anyString())).thenAnswer(i -> { MainSource t = new MainSource(); t.setId(1); return t; });
        when(sourceService.createSubSource(anyInt(), anyString(), anyString())).thenAnswer(i -> { SubSource t = new SubSource(); t.setId(1); return t; });
        // action agent with the guided tools
        AiActionProposalRepository repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        AiToolRegistry registry = new AiToolRegistry(List.of(new AiFinanceTools.CreateIncome(people, fin, incomes), new AiFinanceTools.CreateExpense(fin, expenses),
                new AiSettingsTools.CreateTransactionType(mock(TransactionTypeRepository.class), typeService), new AiSettingsTools.CreatePurpose(mock(PurposeRepository.class), purposeService),
                new AiSettingsTools.CreateFund(mock(MainSourceRepository.class), sourceService), new AiSettingsTools.CreateSubFund(mock(SubSourceRepository.class), fin, sourceService)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AiAccessPolicy policy = new AiAccessPolicy();
        AiActionService actions = new AiActionService(registry, repo, policy, mock(OpenAiVoiceService.class), new TransactionTemplate(tm), true, false, 10);
        AiGuidedRecordService guided = new AiGuidedRecordService(policy, actions, people, fin, null);
        guided.llmFallback = false;
        AiGuidedSettingService settings = new AiGuidedSettingService(policy, actions, fin);
        // meetings
        MeetingTypeRepository types = mock(MeetingTypeRepository.class);
        MeetingType prayer = new MeetingType(); prayer.setId(7); prayer.setTypeName("Prayer Meeting"); prayer.setAppClientId(CID);
        MeetingType bible = new MeetingType(); bible.setId(8); bible.setTypeName("Bible Study"); bible.setAppClientId(CID);
        when(types.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(CID)).thenReturn(List.of(bible, prayer));
        when(types.findByIdAndAppClientIdAndDeleteFlagFalse(eq(8), eq(CID))).thenReturn(Optional.of(bible));
        when(types.findByIdAndAppClientIdAndDeleteFlagFalse(eq(7), eq(CID))).thenReturn(Optional.of(prayer));
        meetingService = mock(MeetingService.class);
        AiMeetingCreateService meetings = new AiMeetingCreateService(types, mock(MeetingRepository.class), meetingService, policy);
        // the data search answers everything else
        data = mock(AiDataSearchService.class);
        when(data.answer(any(), anyString(), anyString())).thenReturn(new LinkedHashMap<>(Map.of("handled", true, "intent", "data.answer", "answer", "data")));
        ctl = new AiDataSearchController(data, meetings);
        ReflectionTestUtils.setField(ctl, "actions", actions);
        ReflectionTestUtils.setField(ctl, "guided", guided);
        ReflectionTestUtils.setField(ctl, "settings", settings);
        ReflectionTestUtils.setField(ctl, "manage", new com.churchgeniuspro.ai.guided.AiManageRedirectService(fin));
    }

    static Hit by(List<Hit> l, Object id) { long v = (Long) id; return l.stream().filter(h -> h.id() == v).findFirst().orElse(null); }

    static MockHttpServletRequest staff(String role, String privs) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        if (privs != null) r.getSession().setAttribute("privileges", privs);
        return r;
    }
    Map<String, Object> ask(MockHttpServletRequest r, String q) {
        MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession());
        return ctl.data(new LinkedHashMap<>(Map.of("query", q)), n).getBody();
    }
    static boolean settingsDraft(MockHttpServletRequest r) { return r.getSession().getAttribute(AiGuidedSettingService.SESSION_KEY) != null; }
    static boolean recordDraft(MockHttpServletRequest r) { return r.getSession().getAttribute(AiGuidedRecordService.SESSION_KEY) != null; }
    static boolean meetingDraft(MockHttpServletRequest r) { return r.getSession().getAttribute("aiMeetingDraft") != null; }
    static final Set<String> SETTING_KINDS = Set.of("fund", "main fund", "sub-fund", "purpose", "transaction type");

    @Test @DisplayName("1. every settings phrase enters the Account Settings conversation (never income / expense)")
    void settingsPhrases() {
        for (String q : List.of("Add a new fund called Building Fund2", "add fund Missions", "Create a new transaction type called Special Offering",
                "create a transaction type", "new payment method Zelle", "Add a purpose named Youth Camp", "add a new expense category Travel",
                "add an expense purpose: Repairs", "add an expense type Travel", "Add an expense fund called Reserve", "add an income fund called Missions",
                "add a giving category Easter", "add an offering type Special", "add a donation type Online", "add a sub-fund Roof Repair under Building Fund",
                "add a sub fund called Pews", "Set up a main fund called Outreach", "register a new sub-category Choir", "please add a new fund", "add a payment type Card",
                "add a new fund called Mens' Fellowship")) {
            MockHttpServletRequest r = staff("Accountant", null);
            Map<String, Object> m = ask(r, q);
            assertThat(String.valueOf(m.get("intent"))).as(q).startsWith("guided.");
            assertThat(SETTING_KINDS).as(q + " → " + m.get("kind")).contains(String.valueOf(m.get("kind")));
            assertThat(settingsDraft(r) || "guided.confirm".equals(m.get("intent"))).as(q).isTrue();
            assertThat(recordDraft(r)).as(q).isFalse();
        }
        // what each phrase becomes
        assertThat(ask(staff("Accountant", null), "add an income fund called Missions").get("asking")).isEqualTo("parent");      // income → sub-fund
        assertThat(ask(staff("Accountant", null), "Add an expense fund called Reserve").get("intent")).isEqualTo("guided.confirm");  // expense → main fund
        assertThat(ask(staff("Accountant", null), "add an offering type Special").get("kind")).isEqualTo("transaction type");
        assertThat(ask(staff("Accountant", null), "add an expense type Travel").get("kind")).isEqualTo("purpose");
        verifyNoInteractions(incomes, expenses, data);
    }

    @Test @DisplayName("2. income / expense phrases behave as before and never enter settings")
    void recordPhrases() {
        for (String q : List.of("add an income", "Add an income of $50 from John Smith to Tithe by cash today", "record a donation of $25", "log an offering",
                "Add $50 income from John Smith", "add an expense", "Record an expense of $120 for Utilities from General Fund by check yesterday",
                "add an income for the Building Fund", "add an expense of $40 to the Building Fund", "new expense entry", "add a tithe")) {
            MockHttpServletRequest r = staff("Accountant", null);
            Map<String, Object> m = ask(r, q);
            assertThat(String.valueOf(m.get("intent"))).as(q).startsWith("guided.");
            assertThat(m.get("kind")).as(q).isIn("income", "expense");
            assertThat(settingsDraft(r)).as(q).isFalse();
        }
        // a full income conversation through the route, with a fund name as an answer (it stays in the income conversation)
        MockHttpServletRequest r = staff("Accountant", null);
        assertThat(ask(r, "add an income").get("asking")).isEqualTo("contributor");
        assertThat(ask(r, "John Smith").get("asking")).isEqualTo("amount");
        assertThat(ask(r, "50").get("asking")).isEqualTo("fund");
        assertThat(ask(r, "Building Fund").get("asking")).isEqualTo("date");
        assertThat(ask(r, "today").get("asking")).isEqualTo("method");
        assertThat(ask(r, "cash").get("asking")).isEqualTo("optional");
        assertThat(ask(r, "Skip and review").get("intent")).isEqualTo("guided.confirm");
        assertThat(ask(r, "yes").get("intent")).isEqualTo("action.done");
        verify(incomes, times(1)).createIncome(eq(5), eq(2), any(), eq(11), isNull(), any(), isNull(), isNull(), eq(false), eq(CID), eq("pat"));
        verifyNoInteractions(typeService, purposeService, sourceService);
    }

    @Test @DisplayName("3. no overlap: starting one conversation ends the other; answers go to the one that asked; meetings stay meetings")
    void noOverlap() {
        // phrase-level: at most one of the three start rules claims any phrase
        for (String q : List.of("add a new fund called X", "add an income", "add an expense category X", "add an expense", "create a meeting tomorrow",
                "add an income fund X", "add a donation", "add a donation type Online", "create a new transaction type", "fund raising expense of $40",
                "add fund raising expense", "how do I add a fund?", "what is the Building Fund balance?"))
            assertThat((AiGuidedSettingService.isStartRequest(q) ? 1 : 0) + (AiGuidedRecordService.isStartRequest(q) ? 1 : 0)
                    + (AiMeetingCreateService.isCreateRequest(q) ? 1 : 0)).as(q).isLessThanOrEqualTo(1);
        assertThat(AiGuidedSettingService.isStartRequest("add fund raising expense")).isFalse();

        MockHttpServletRequest r = staff("SuperAdmin", "{\"general\":true,\"general.meetings\":true,\"general.meetings.edit\":true}");
        ask(r, "add an income");
        assertThat(recordDraft(r)).isTrue();
        Map<String, Object> m = ask(r, "add a new purpose");                         // switch to settings
        assertThat(m.get("kind")).isEqualTo("purpose");
        assertThat(recordDraft(r)).as("income draft dropped").isFalse();
        assertThat(ask(r, "Tithe").get("intent")).as("the answer is the purpose's name").isEqualTo("guided.confirm");
        ask(r, "add an expense");                                                    // switch back
        assertThat(settingsDraft(r)).as("settings draft dropped").isFalse();
        assertThat(ask(r, "Utilities").get("asking")).as("the answer is the expense category").isEqualTo("amount");
        m = ask(r, "Create a Prayer Meeting next Friday at 7pm in the hall");        // a meeting ends both
        assertThat(String.valueOf(m.get("intent"))).startsWith("meeting.");
        assertThat(recordDraft(r) || settingsDraft(r)).isFalse();
        ask(r, "add a new fund called Outreach");
        assertThat(meetingDraft(r)).as("meeting draft dropped").isFalse();
        // a plain data question with no conversation open goes to the data search
        MockHttpServletRequest q = staff("Accountant", null);
        assertThat(ask(q, "what was the income last month").get("intent")).isEqualTo("data.answer");
        verifyNoInteractions(incomes, expenses, typeService, purposeService, sourceService);
    }

    @Test @DisplayName("4. permissions unchanged and separate: settings = accounting.settings; income / expense = their pages; meetings = meetings")
    void permissions() {
        String noSettings = "{\"accounting\":true,\"accounting.settings\":false,\"accounting.income\":true,\"accounting.income.edit\":true,\"accounting.expense\":true,\"accounting.expense.edit\":true}";
        String settingsOnly = "{\"accounting\":true,\"accounting.settings\":true,\"accounting.income\":false,\"accounting.expense\":false}";
        assertThat(ask(staff("Accountant", noSettings), "add a new fund called X").get("intent")).isEqualTo("guided.denied");
        assertThat(ask(staff("Accountant", noSettings), "add an income").get("intent")).isEqualTo("guided.ask");
        assertThat(ask(staff("Accountant", settingsOnly), "add a new fund called X").get("intent")).isEqualTo("guided.ask");
        assertThat(ask(staff("Accountant", settingsOnly), "add an income").get("intent")).isEqualTo("guided.denied");
        assertThat(ask(staff("Accountant", settingsOnly), "add an expense").get("intent")).isEqualTo("guided.denied");
        for (String q : List.of("add a new fund called X", "add an income", "add an expense"))
            assertThat(ask(staff("User", null), q).get("intent")).as("User: " + q).isEqualTo("guided.denied");
        MockHttpServletRequest member = staff("Member", null); member.getSession().setAttribute("memberId", 9);
        member.getSession().setAttribute("memberPrivileges", "{\"more.aiassistant\":true}");
        for (String q : List.of("add a new fund called X", "add an income"))
            assertThat(ask(member, q).get("denied")).as("member: " + q).isEqualTo(true);
        // meetings keep their own rule: a staff login without meeting edit is denied a meeting, not touched by these changes
        assertThat(ask(staff("Accountant", "{\"general\":true,\"general.meetings\":true,\"general.meetings.edit\":false}"), "Create a Prayer Meeting next Friday at 7pm")
                .get("intent")).isEqualTo("meeting.denied");
        verifyNoInteractions(incomes, expenses, typeService, purposeService, sourceService, meetingService);
    }

    @Test @DisplayName("names are kept exactly as typed — trailing apostrophes included; only a full stop or a matching pair of quotes around the name is removed")
    void namesExact() {
        assertThat(AiSettingsTools.cleanName("Mens'")).isEqualTo("Mens'");
        assertThat(AiSettingsTools.cleanName("Mens’")).isEqualTo("Mens’");
        assertThat(AiSettingsTools.cleanName("Saints' Fund")).isEqualTo("Saints' Fund");
        assertThat(AiSettingsTools.cleanName("Mens'.")).isEqualTo("Mens'");
        assertThat(AiSettingsTools.cleanName("\"Mens'\"")).isEqualTo("Mens'");
        assertThat(AiSettingsTools.cleanName("'Youth Camp'")).isEqualTo("Youth Camp");
        assertThat(AiSettingsTools.cleanName("“Youth Camp”.")).isEqualTo("Youth Camp");
        assertThat(AiSettingsTools.cleanName("'Tis the Season")).isEqualTo("'Tis the Season");
        assertThat(AiSettingsTools.cleanName("O'Brien Memorial")).isEqualTo("O'Brien Memorial");
        assertThat(AiSettingsTools.cleanName("  Women's   Ministry  ")).isEqualTo("Women's Ministry");
        // through the conversation, from the first message and as an answer, to the page's service
        MockHttpServletRequest r = staff("Accountant", null);
        Map<String, Object> m = ask(r, "Add a purpose named Mens'");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(ask(r, "yes").get("intent")).isEqualTo("action.done");
        verify(purposeService).create("Mens'", CID);
        MockHttpServletRequest r2 = staff("Accountant", null);
        ask(r2, "add a new transaction type");
        ask(r2, "Elders'");
        assertThat(ask(r2, "yes").get("intent")).isEqualTo("action.done");
        verify(typeService).create("Elders'", CID);
        MockHttpServletRequest r3 = staff("Accountant", null);
        m = ask(r3, "add a sub-fund Saints' Fund under General Fund");
        ask(r3, "save");
        verify(sourceService).createSubSource(21, "Saints' Fund", CID);
    }

    @SuppressWarnings("unchecked") static String url(Map<String, Object> m) { Object l = m.get("link"); return l == null ? null : String.valueOf(((Map<String, Object>) l).get("url")); }

    @Test @DisplayName("5. edit / delete requests are never carried out: a message + a link to the page that manages the record")
    void editDeleteRedirects() {
        String[][] cases = {
            {"Delete the Building Fund", "/fund"}, {"rename the fund Tithe to Tithes", "/fund"}, {"please delete the main fund General Fund", "/fund"},
            {"update the sub-fund Tithe to not tax-deductible", "/fund"}, {"can you remove the giving category Easter", "/fund"},
            {"change the Building Fund to a sub-fund", "/fund"}, {"delete Tithe", "/fund"},
            {"remove the Cash payment method", "/transactiontype"}, {"please delete the transaction type Special Offering", "/transactiontype"},
            {"deactivate the offering type Special", "/transactiontype"}, {"delete Check", "/transactiontype"}, {"rename Cash to Cash Only", "/transactiontype"},
            {"edit purpose Utilities", "/purpose"}, {"change the expense category Utilities to Power", "/purpose"}, {"delete Utilities", "/purpose"},
            {"delete the $50 expense from Building Fund", "/expense"}, {"edit yesterday's expense", "/expense"},
            {"delete the donation from John Smith", "/income"}, {"remove the income entry for John", "/income"}, {"change the tithe John gave on Sunday", "/income"},
            {"delete that $25 transaction", "/income"},
            {"change the prayer meeting on Friday to 8 pm", "/meetings"}, {"delete the meeting on Friday", "/meetings"}};
        for (String[] c : cases) {
            MockHttpServletRequest r = staff("Accountant", null);
            Map<String, Object> m = ask(r, c[0]);
            assertThat(m.get("intent")).as(c[0]).isEqualTo("manage.redirect");
            assertThat(url(m)).as(c[0]).isEqualTo(c[1]);
            assertThat(String.valueOf(m.get("answer"))).as(c[0]).contains("can't change or delete").containsIgnoringCase("open the").doesNotContain("Done");
            assertThat(m.get("confirm")).as(c[0]).isNull();
            assertThat(settingsDraft(r) || recordDraft(r) || meetingDraft(r)).as(c[0]).isFalse();
        }
        assertThat(ask(staff("Accountant", null), "Delete the Building Fund").get("record")).isEqualTo("Building Fund");
        assertThat(String.valueOf(ask(staff("Accountant", null), "delete Check").get("answer"))).contains("To delete “Check”, open the Transaction Types page (Account Settings → Transaction Type)");
        assertThat(store).as("no proposal, no token").isEmpty();
        verifyNoInteractions(typeService, purposeService, sourceService, incomes, expenses, meetingService, data);

        // not edit / delete requests of a record — every one keeps its old route
        for (String q : List.of("how do I delete a fund", "remove Sarah from the Welcome Team", "change my password", "delete my account", "what funds do we have",
                "update my profile")) {
            assertThat(ask(staff("Accountant", null), q).get("intent")).as(q).isNotEqualTo("manage.redirect");
        }
        assertThat(String.valueOf(ask(staff("Accountant", null), "add a new fund called Building Fund2").get("intent"))).startsWith("guided.");
        MockHttpServletRequest r = staff("Accountant", null);
        assertThat(String.valueOf(ask(r, "add an expense of $50").get("intent"))).startsWith("guided.");
        assertThat(recordDraft(r)).isTrue();
    }

    @Test @DisplayName("6. the link only when this login may open the page; names are looked up only for Account Settings users")
    void redirectPermissions() {
        Map<String, Object> m = ask(staff("User", null), "Delete the Building Fund");                     // User: no Account Settings, no Income / Expense
        assertThat(m.get("intent")).isEqualTo("manage.redirect");
        assertThat(m.get("link")).isNull();
        assertThat(m.get("record")).isNull();
        assertThat(String.valueOf(m.get("answer"))).contains("You don't have access to the Funds page");
        assertThat(ask(staff("User", null), "delete the $50 expense").get("link")).isNull();
        assertThat(url(ask(staff("User", null), "delete the meeting on Friday"))).isEqualTo("/meetings");
        String noSettings = "{\"accounting.settings\":false}";
        m = ask(staff("Accountant", noSettings), "rename transaction type Cash to Cash Only");
        assertThat(m.get("link")).isNull();
        assertThat(String.valueOf(m.get("answer"))).contains("You don't have access to the Transaction Types page");
        assertThat(ask(staff("Accountant", noSettings), "delete Check").get("intent")).as("no name lookup without Account Settings").isNotEqualTo("manage.redirect");
        assertThat(url(ask(staff("Accountant", noSettings), "delete the donation from John Smith"))).isEqualTo("/income");
        assertThat(url(ask(staff("Accountant", "{\"accounting.income\":false}"), "delete that $25 transaction"))).isEqualTo("/expense");
        verifyNoInteractions(typeService, purposeService, sourceService, incomes, expenses, meetingService);
    }

    @Test @DisplayName("7. open conversations: their own corrections stay with them; a request about an existing record is redirected and the draft kept")
    void redirectDuringConversations() {
        MockHttpServletRequest r = staff("Accountant", null);
        ask(r, "add a fund called Outreach");                                                              // asks main fund or sub-fund
        Map<String, Object> m = ask(r, "delete the Building Fund");
        assertThat(m.get("intent")).isEqualTo("manage.redirect");
        assertThat(m.get("pending")).isEqualTo(true);
        assertThat(String.valueOf(m.get("answer"))).contains("still open");
        assertThat(settingsDraft(r)).isTrue();
        assertThat(String.valueOf(ask(r, "change the name to Outreach Ministry").get("intent"))).startsWith("guided.");   // own correction
        assertThat(String.valueOf(ask(r, "rename it to Outreach Team").get("intent"))).startsWith("guided.");
        assertThat(settingsDraft(r)).isTrue();

        MockHttpServletRequest r2 = staff("Accountant", null);
        ask(r2, "add an income of $50 from John Smith");
        assertThat(recordDraft(r2)).isTrue();
        assertThat(String.valueOf(ask(r2, "change the amount to 60").get("intent"))).startsWith("guided.");
        assertThat(String.valueOf(ask(r2, "change the fund to Building Fund").get("intent"))).startsWith("guided.");
        m = ask(r2, "delete the Tithe fund");
        assertThat(m.get("intent")).isEqualTo("manage.redirect");
        assertThat(recordDraft(r2)).isTrue();

        MockHttpServletRequest r3 = staff("Admin", null);
        ask(r3, "create a prayer meeting");
        assertThat(meetingDraft(r3)).isTrue();
        assertThat(ask(r3, "change the time to 8 pm").get("intent")).isNotEqualTo("manage.redirect");
        assertThat(meetingDraft(r3)).isTrue();
        verifyNoInteractions(typeService, purposeService, sourceService, incomes, expenses);
    }

    @Test @DisplayName("8. every example in the AI Search Help starts the conversation the help promises")
    void helpExamplesWork() {
        Object[][] ex = {
            {"Create a meeting", "meeting.", null},
            {"Schedule a Bible Study next Sunday at 10 AM.", "meeting.", null},
            {"Record an income of $500 for Sunday offerings.", "guided.", "record"},
            {"Record an expense of $150 for electricity.", "guided.", "record"},
            {"Create a transaction type called Mission Offering.", "guided.", "settings"},
            {"Create a fund called Building Fund.", "guided.", "settings"},
            {"Create a subfund called Youth Ministry under the Building Fund.", "guided.", "settings"}};
        for (Object[] e : ex) {
            MockHttpServletRequest r = staff("Admin", "{\"accounting\":true,\"accounting.income\":true,\"accounting.income.edit\":true,\"accounting.expense\":true,\"accounting.expense.edit\":true,\"accounting.settings\":true}");
            Map<String, Object> m = ask(r, (String) e[0]);
            assertThat(String.valueOf(m.get("intent"))).as(e[0] + " → " + m.get("answer")).startsWith((String) e[1]).doesNotContain("denied").doesNotContain("refused");
            if ("record".equals(e[2])) assertThat(recordDraft(r) || "guided.confirm".equals(m.get("intent"))).as((String) e[0]).isTrue();
            if ("settings".equals(e[2])) assertThat(settingsDraft(r) || "guided.confirm".equals(m.get("intent"))).as((String) e[0]).isTrue();
        }
        MockHttpServletRequest rm = staff("Admin", null);
        Map<String, Object> m = ask(rm, "Schedule a Bible Study next Sunday at 10 AM.");
        assertThat(m.get("asking")).as("category, date and time taken from the sentence").isEqualTo("location");
        m = ask(rm, "no location");
        if (!"meeting.confirm".equals(m.get("intent"))) m = ask(rm, "Review the details");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        assertThat(m.toString()).contains("Bible Study").contains("10:00 AM").contains("Sun");
        m = ask(staff("Accountant", null), "Create a subfund called Youth Ministry under the Building Fund.");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(m.toString()).contains("Youth Ministry").contains("Building Fund");
        m = ask(staff("Accountant", null), "Create a fund called Building Fund.");
        assertThat(m.get("asking")).isEqualTo("level");                                     // main fund or sub-fund?
        // a category name followed by another noun is not a meeting
        assertThat(meetingDraft(withAsk(staff("Admin", null), "create a Bible Study group"))).isFalse();
        verifyNoInteractions(incomes, expenses, typeService, purposeService, sourceService, meetingService);
    }
    MockHttpServletRequest withAsk(MockHttpServletRequest r, String q) { ask(r, q); return r; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> field(Map<String, Object> card, String key) {
        for (Map<String, Object> f : (List<Map<String, Object>>) ((Map<String, Object>) card.get("confirm")).get("fields"))
            if (key.equals(f.get("key"))) return f;
        return null;
    }

    @Test @DisplayName("9. Genius Assistant additions: short question above the choices (S1), Cancel on the review question (S2), Edit re-asks a field with its choices (S3)")
    void geniusAssistantAdditions() {
        // meeting — the user's example shape
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = ask(r, "Create a meeting at 7:30 PM.");
        assertThat(m.get("asking")).isEqualTo("category");
        assertThat(m.get("question")).as("S1 short question").isEqualTo("Which category is this meeting?");
        assertThat(String.valueOf(m.get("answer"))).as("the full sentence still lists them (voice)").contains("Bible Study").contains("Prayer Meeting");
        assertThat((List<String>) m.get("choices")).containsExactly("Bible Study", "Prayer Meeting");
        m = ask(r, "Prayer Meeting");
        assertThat(m.get("asking")).isEqualTo("date");
        m = ask(r, "11/20/2026");
        for (int i = 0; i < 4 && !"review".equals(m.get("asking")) && !"meeting.confirm".equals(m.get("intent")); i++)
            m = ask(r, "location".equals(m.get("asking")) ? "no location" : "no");
        assertThat(m.get("asking")).isEqualTo("review");
        assertThat((List<String>) m.get("choices")).as("S2").containsExactly("Review the details", "Add a note", "Cancel");
        ask(r, "Add a note");
        m = ask(r, "Please come and attend.");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        assertThat(((Map<String, Object>) m.get("confirm")).get("label")).isEqualTo("Create Meeting");
        assertThat(field(m, "category").get("ask")).as("S3").isEqualTo("Change the category");
        assertThat(field(m, "date").get("ask")).as("a free field keeps the message-box words").isNull();
        m = ask(r, "Change the category");
        assertThat(m.get("asking")).isEqualTo("category");
        assertThat((List<String>) m.get("choices")).containsExactly("Bible Study", "Prayer Meeting");
        m = ask(r, "Bible Study");
        assertThat(m.get("intent")).as("straight back to the card").isEqualTo("meeting.confirm");
        assertThat(field(m, "category").get("value")).isEqualTo("Bible Study");
        assertThat(field(m, "note").get("value")).as("the rest is kept").isEqualTo("Please come and attend.");
        // Cancel from the review question saves nothing
        MockHttpServletRequest rc = staff("Admin", null);
        ask(rc, "Create a meeting at 7:30 PM."); ask(rc, "Prayer Meeting");
        m = ask(rc, "11/20/2026");
        for (int i = 0; i < 4 && !"review".equals(m.get("asking")); i++) m = ask(rc, "location".equals(m.get("asking")) ? "no location" : "no");
        m = ask(rc, "Cancel");
        assertThat(String.valueOf(m.get("intent"))).contains("cancel");
        assertThat(meetingDraft(rc)).isFalse();
        verifyNoInteractions(meetingService);

        // income — Edit on the fund re-asks with the church's sub-funds; Cancel offered at review
        MockHttpServletRequest ri = staff("Accountant", null);
        m = ask(ri, "Add an income of $50 from John Smith to Tithe by cash today");
        for (int i = 0; i < 6 && !"guided.confirm".equals(m.get("intent")); i++) {
            if ("review".equals(m.get("asking"))) assertThat((List<String>) m.get("choices")).contains("Cancel");
            m = ask(ri, "review".equals(m.get("asking")) ? "Review the details" : "Skip and review");
        }
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(field(m, "fund").get("ask")).isEqualTo("Change the fund");
        assertThat(field(m, "method").get("ask")).isEqualTo("Change the payment method");
        m = ask(ri, "Change the fund");
        assertThat(m.get("asking")).isEqualTo("fund");
        assertThat((List<String>) m.get("choices")).contains("Tithe", "Building Fund");
        assertThat(m.get("question")).isNotNull();
        m = ask(ri, "Building Fund");
        for (int i = 0; i < 3 && !"guided.confirm".equals(m.get("intent")); i++) m = ask(ri, "Review the details");
        assertThat(field(m, "fund").get("value")).isEqualTo("Building Fund");

        // settings — Edit on the main fund of a sub-fund re-asks with the main funds
        MockHttpServletRequest rs = staff("Accountant", null);
        m = ask(rs, "Create a subfund called Youth Ministry under the Building Fund.");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(field(m, "parent").get("ask")).isEqualTo("Change the main fund");
        m = ask(rs, "Change the main fund");
        assertThat(m.get("asking")).isEqualTo("parent");
        assertThat((List<String>) m.get("choices")).contains("General Fund", "Building Fund");
        m = ask(rs, "General Fund");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(field(m, "parent").get("value")).isEqualTo("General Fund");
        verifyNoInteractions(incomes, typeService, purposeService, sourceService);
    }
}

package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.ai.guided.AiGuidedSettingService;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.service.PurposeService;
import com.churchgeniuspro.service.SourceService;
import com.churchgeniuspro.service.TransactionTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-08 — "Add a new fund called Building Fund" / "Create a new transaction type called Special Offering" /
 * purposes / sub-funds from the AI Search bar: questions only for what is missing, review card, explicit Save through
 * the action agent into the Account Settings pages' own services; permission = accounting.settings page rule.
 */
@DisplayName("AI Search — guided Account Settings entries (funds, sub-funds, purposes, transaction types)")
class AiGuidedSettingTest {

    static final String CID = "CHR-A";
    TransactionTypeRepository typeRepo; PurposeRepository purposeRepo; MainSourceRepository mainRepo; SubSourceRepository subRepo;
    TransactionTypeService typeService; PurposeService purposeService; SourceService sourceService; AiFinanceLookup fin;
    AiActionProposalRepository repo; Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiToolRegistry registry; AiActionService actions; AiAccessPolicy policy; AiGuidedSettingService svc;

    @BeforeEach
    void setUp() {
        typeRepo = mock(TransactionTypeRepository.class); purposeRepo = mock(PurposeRepository.class); mainRepo = mock(MainSourceRepository.class); subRepo = mock(SubSourceRepository.class);
        typeService = mock(TransactionTypeService.class); purposeService = mock(PurposeService.class); sourceService = mock(SourceService.class); fin = mock(AiFinanceLookup.class);
        when(typeRepo.existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(argThat(n -> "Cash".equalsIgnoreCase(n)), eq(CID))).thenReturn(true);
        when(purposeRepo.existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(argThat(n -> "Utilities".equalsIgnoreCase(n)), eq(CID))).thenReturn(true);
        when(mainRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(argThat(n -> "General Fund".equalsIgnoreCase(n)), eq(CID))).thenReturn(true);
        when(subRepo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(argThat(n -> "Tithe".equalsIgnoreCase(n)), eq(21))).thenReturn(true);
        List<Hit> mains = List.of(new Hit(21, "General Fund", null), new Hit(22, "Building Fund", null));
        when(fin.expenseFunds(CID)).thenReturn(mains);
        when(fin.expenseFundById(eq(CID), anyLong())).thenAnswer(i -> mains.stream().filter(h -> h.id() == (Long) i.getArgument(1)).findFirst().orElse(null));
        when(typeService.create(anyString(), anyString())).thenAnswer(i -> { TransactionType t = new TransactionType(); t.setId(1); return t; });
        when(purposeService.create(anyString(), anyString())).thenAnswer(i -> { Purpose t = new Purpose(); t.setId(2); return t; });
        when(sourceService.createMainSource(anyString(), anyString())).thenAnswer(i -> { MainSource t = new MainSource(); t.setId(3); return t; });
        when(sourceService.createSubSource(anyInt(), anyString(), anyString())).thenAnswer(i -> { SubSource t = new SubSource(); t.setId(4); return t; });
        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        registry = new AiToolRegistry(List.of(new AiSettingsTools.CreateTransactionType(typeRepo, typeService), new AiSettingsTools.CreatePurpose(purposeRepo, purposeService),
                new AiSettingsTools.CreateFund(mainRepo, sourceService), new AiSettingsTools.CreateSubFund(subRepo, fin, sourceService)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        policy = spy(new AiAccessPolicy());
        actions = new AiActionService(registry, repo, policy, mock(OpenAiVoiceService.class), new TransactionTemplate(tm), true, false, 10);
        svc = new AiGuidedSettingService(policy, actions, fin);
    }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        if (privileges != null) r.getSession().setAttribute("privileges", privileges);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    Map<String, Object> say(MockHttpServletRequest r, String t) { return svc.handle(same(r), CID, t); }
    @SuppressWarnings("unchecked") static Map<String, Object> confirm(Map<String, Object> m) { return (Map<String, Object>) m.get("confirm"); }
    @SuppressWarnings("unchecked") static Map<String, String> summary(Map<String, Object> m) { return (Map<String, String>) confirm(m).get("summary"); }
    static String token(Map<String, Object> m) { return String.valueOf(confirm(m).get("token")); }

    @Test @DisplayName("start phrases; income / expense records and meetings are not taken")
    void detection() {
        for (String q : List.of("Add a new fund called Building Fund", "Create a new transaction type called Special Offering", "add a purpose named Youth Camp",
                "please add a new expense category: Utilities", "new payment method Zelle", "add a sub-fund Roof Repair under Building Fund", "set up a main fund called Missions"))
            assertThat(AiGuidedSettingService.isStartRequest(q)).as(q).isTrue();
        for (String q : List.of("add an income of $50", "add an expense", "how do I add a fund?", "show the funds", "create a meeting", "what is the Building Fund balance"))
            assertThat(AiGuidedSettingService.isStartRequest(q)).as(q).isFalse();
        // the income / expense conversation leaves settings phrases alone
        for (String q : List.of("add a new expense category Utilities", "add an expense type Travel", "add an offering type", "add an income fund called Missions"))
            assertThat(AiGuidedRecordService.isStartRequest(q)).as(q).isFalse();
    }

    @Test @DisplayName("transaction type in one sentence → card → yes saves once through TransactionTypeService")
    void transactionType() {
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "Create a new transaction type called Special Offering");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(confirm(m).get("label")).isEqualTo("Save transaction type");
        assertThat(summary(m)).containsEntry("Transaction type", "Special Offering");
        verifyNoInteractions(typeService);
        assertThat(say(r, "yes").get("intent")).isEqualTo("action.done");
        verify(typeService, times(1)).create("Special Offering", CID);
        assertThat(say(r, "yes")).isNull();
        assertThat(actions.confirm(same(r), CID, token(m)).get("intent")).isEqualTo("action.expired");
    }

    @Test @DisplayName("purpose: name asked when missing; an existing name is reported and a new one asked; quotes trimmed")
    void purposeNameAsked() {
        MockHttpServletRequest r = req("SuperAdmin", null);
        Map<String, Object> m = svc.handle(r, CID, "add a new purpose");
        assertThat(m.get("asking")).isEqualTo("name");
        assertThat(m.get("answer")).isEqualTo("What should the new purpose be called?");
        m = say(r, "Utilities");
        assertThat(String.valueOf(m.get("answer"))).contains("already exists").contains("What name should I use instead?");
        m = say(r, "“Youth Camp”");
        assertThat(summary(m)).containsEntry("Purpose (expense category)", "Youth Camp");
        actions.confirm(same(r), CID, token(m));
        verify(purposeService).create("Youth Camp", CID);
    }

    @Test @DisplayName("fund: main or sub-fund asked; sub-fund asks which main fund (choices); tax-deductible optional; corrections")
    void funds() {
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "Add a new fund called Roof Repair");
        assertThat(m.get("asking")).isEqualTo("level");
        assertThat(m.get("choices")).isEqualTo(List.of("Main fund", "Sub-fund"));
        m = say(r, "Sub-fund");
        assertThat(m.get("asking")).isEqualTo("parent");
        assertThat(m.get("choices")).isEqualTo(List.of("General Fund", "Building Fund"));
        m = say(r, "2");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(summary(m)).containsEntry("Sub-fund", "Roof Repair").containsEntry("Main fund", "Building Fund").containsEntry("Tax-deductible", "Yes");
        String old = token(m);
        m = say(r, "change tax-deductible to no");
        assertThat(summary(m)).containsEntry("Tax-deductible", "No");
        assertThat(actions.confirm(same(r), CID, old).get("intent")).as("replaced card").isEqualTo("action.expired");
        m = say(r, "change the main fund to General Fund");
        assertThat(summary(m)).containsEntry("Main fund", "General Fund");
        m = say(r, "change the name to Roof & Gutter Repair");
        assertThat(summary(m)).containsEntry("Sub-fund", "Roof & Gutter Repair");
        assertThat(say(r, "save").get("intent")).isEqualTo("action.done");
        verify(sourceService).createSubSource(21, "Roof & Gutter Repair", CID);
        verify(sourceService).setTaxDeductible(4, false, CID);

        // one sentence with the parent, and "make it a main fund"
        MockHttpServletRequest r2 = req("Accountant", null);
        m = svc.handle(r2, CID, "add a sub-fund Christmas Offering under the Building Fund");
        assertThat(summary(m)).containsEntry("Sub-fund", "Christmas Offering").containsEntry("Main fund", "Building Fund");
        m = say(r2, "make it a main fund");
        assertThat(summary(m)).containsEntry("Main fund", "Christmas Offering").containsEntry("Fund level", "Main fund");
        actions.confirm(same(r2), CID, token(m));
        verify(sourceService).createMainSource("Christmas Offering", CID);

        // a sub-fund name that already exists under that main fund
        MockHttpServletRequest r3 = req("Accountant", null);
        m = svc.handle(r3, CID, "add a sub fund called Tithe under General Fund");
        assertThat(String.valueOf(m.get("answer"))).contains("already exists under General Fund");
        // unknown main fund → asked with the church's own list
        MockHttpServletRequest r4 = req("Accountant", null);
        m = svc.handle(r4, CID, "add a sub-fund Pews under Other Church Fund");
        assertThat(m.get("asking")).isEqualTo("parent");
        // no main funds at all → offer a main fund instead
        when(fin.expenseFunds(CID)).thenReturn(List.of());
        MockHttpServletRequest r5 = req("Accountant", null);
        m = svc.handle(r5, CID, "add a sub-fund called Pews");
        assertThat(m.get("choices")).isEqualTo(List.of("Make it a main fund", "Cancel"));
        m = say(r5, "Make it a main fund");
        assertThat(summary(m)).containsEntry("Fund level", "Main fund");
    }

    @Test @DisplayName("cancel at every step and the card's Cancel; another question leaves the draft")
    void cancel() {
        MockHttpServletRequest r = req("Accountant", null);
        svc.handle(r, CID, "add a new fund called Missions");
        assertThat(say(r, "what is the Building Fund balance?")).isNull();
        assertThat(say(r, "cancel").get("intent")).isEqualTo("guided.cancelled");
        assertThat(say(r, "Main fund")).isNull();
        MockHttpServletRequest r2 = req("Accountant", null);
        Map<String, Object> m = svc.handle(r2, CID, "add a payment method called Venmo");
        actions.reject(same(r2), CID, token(m));
        assertThat(say(r2, "yes")).isNull();
        verifyNoInteractions(typeService, sourceService);
    }

    @Test @DisplayName("permission = Account Settings page rule (role + accounting.settings), checked first, every message and at Save; plan")
    void permissions() {
        String settingsOff = "{\"accounting\":true,\"accounting.settings\":false}";
        String settingsOn = "{\"accounting\":true,\"accounting.settings\":true}";
        record C(String role, String privs, boolean ok) {}
        for (C c : List.of(new C("SuperAdmin", null, true), new C("Accountant", null, true), new C("Admin", null, false), new C("Admin", settingsOn, true),
                new C("User", null, false), new C("Accountant", settingsOff, false)))
            assertThat(svc.handle(req(c.role(), c.privs()), CID, "add a new fund called X").get("intent")).as(c.toString()).isEqualTo(c.ok() ? "guided.ask" : "guided.denied");
        MockHttpServletRequest church = req("SuperAdmin", null); church.getSession().setAttribute("church", true);
        assertThat(svc.handle(church, CID, "add a new fund called X").get("intent")).isEqualTo("guided.denied");
        // withdrawn between the card and Save
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add a payment method called Venmo");
        r.getSession().setAttribute("privileges", settingsOff);
        assertThat(actions.confirm(same(r), CID, token(m)).get("intent")).isEqualTo("action.denied");
        verifyNoInteractions(typeService);
        // plan without AI actions
        doReturn(false).when(policy).feature(anyString(), eq(AiGuidedRecordService.FEATURE));
        assertThat(svc.handle(req("Accountant", null), CID, "add a new fund called X").get("intent")).isEqualTo("guided.unavailable");
    }

    @Test @DisplayName("tools are guided-only, use the pages' services and check duplicates again at Save; controller routes settings first")
    void wiring() throws Exception {
        assertThat(registry.describe()).isEmpty();
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add a payment method called Venmo");
        when(typeRepo.existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(eq("Venmo"), eq(CID))).thenReturn(true);   // created elsewhere meanwhile
        assertThat(actions.confirm(same(r), CID, token(m)).get("intent")).isEqualTo("action.refused");
        verifyNoInteractions(typeService);
        assertThat(AiSettingsTools.cleanName("  \"Building Fund\". ")).isEqualTo("Building Fund");
        assertThat(AiSettingsTools.cleanName("x".repeat(101))).isNull();
        assertThat(AiSettingsTools.cleanName("!!!")).isNull();
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        assertThat(ctl.indexOf("AiGuidedSettingService.isStartRequest(question)")).isPositive().isLessThan(ctl.indexOf("AiGuidedRecordService.isStartRequest(question)"));
        String tools = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/action/AiSettingsTools.java"));
        assertThat(tools).contains("service.create(").contains("service.createMainSource(").contains("service.createSubSource(").contains("\"accounting.settings\"");
    }

    @Test @DisplayName("an existing name is never created: refused before the card (any case / spacing / full stop), again at Save, and when the page's own service refuses")
    void duplicatesNeverSaved() {
        for (String[] c : new String[][]{
                {"add a transaction type called cash", "A transaction type called “cash” already exists."},
                {"add a payment method named  CASH.", "A transaction type called “CASH” already exists."},
                {"add a purpose named utilities", "A purpose called “utilities” already exists."},
                {"add a main fund called General   Fund", "A main fund called “General Fund” already exists."},
                {"add a sub-fund Tithe under General Fund", "A sub-fund called “Tithe” already exists under General Fund."}}) {
            MockHttpServletRequest r = req("Accountant", null);
            Map<String, Object> m = svc.handle(r, CID, c[0]);
            assertThat(String.valueOf(m.get("answer"))).as(c[0]).startsWith(c[1]).endsWith("What name should I use instead?");
            assertThat(m.get("confirm")).as(c[0]).isNull();                                  // no card, nothing to Save
            assertThat(m.get("asking")).as(c[0]).isEqualTo("name");
            assertThat(say(r, "yes").get("intent")).as(c[0] + " — a yes is not a Save").isNotEqualTo("action.done");
        }
        // the page's service refuses a name added in another tab between the check and the Save
        when(typeService.create(eq("Venmo2"), anyString())).thenThrow(new IllegalArgumentException("A transaction type \"Venmo2\" already exists."));
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = svc.handle(r, CID, "add a payment method called Venmo2");
        Map<String, Object> done = actions.confirm(same(r), CID, token(m));
        assertThat(done.get("intent")).isEqualTo("action.refused");
        assertThat(String.valueOf(done.get("answer"))).contains("already exists").contains("nothing was saved");
        verify(sourceService, never()).createMainSource(anyString(), anyString());
        verify(purposeService, never()).create(anyString(), anyString());
    }

    @Test @DisplayName("no AI tool edits or deletes master data or financial records; edit / delete requests are routed first to the redirect")
    void noEditOrDeleteTools() throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("public String name\\(\\) \\{ return \"([a-z_]+)\"").matcher("");
        List<String> names = new ArrayList<>();
        try (var files = Files.list(Path.of("src/main/java/com/churchgeniuspro/ai/action"))) {
            for (Path f : files.toList()) { m.reset(Files.readString(f)); while (m.find()) names.add(m.group(1)); }
        }
        assertThat(names).contains("create_fund", "create_income").allSatisfy(n ->
            assertThat(n).doesNotContainPattern("^(edit|update|change|rename|delete|remove|archive|deactivate|merge)_"));
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        assertThat(ctl.indexOf("manage.handle(")).isPositive().isLessThan(ctl.indexOf("AiGuidedSettingService.isStartRequest(question)"));
        assertThat(com.churchgeniuspro.ai.guided.AiManageRedirectService.detect("add a new fund called Building Fund")).isNull();
        assertThat(com.churchgeniuspro.ai.guided.AiManageRedirectService.detect("set up a transaction type Zelle")).isNull();
        assertThat(com.churchgeniuspro.ai.guided.AiManageRedirectService.detect("mark the follow-up for the Lees as done")).isNull();
    }
}

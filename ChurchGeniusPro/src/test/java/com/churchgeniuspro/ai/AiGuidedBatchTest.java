package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.guided.AiGuidedBatchService;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService;
import com.churchgeniuspro.ai.guided.GuidedDrafts;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.repository.AiActionProposalRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-20 — "I received 20 checks and $2,000 in cash this Sunday. Please add them to the General Fund."
 * The conversation (cash as one entry or split, checks from the existing scan, summary of what was saved) through
 * the real batch service, the real guided-income service and the real action agent; only the lookups and the
 * Income service are stand-ins. Every entry is saved by its own Confirm &amp; Save and by nothing else.
 */
@DisplayName("Genius Assistant — several donations in one conversation")
class AiGuidedBatchTest {

    static final String CID = "CHR-A";
    static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    static final String REQUEST = "I have received 20 checks and $2,000 in cash as offerings this Sunday. Please add these to the General Fund.";

    AiActionLookup people; AiFinanceLookup fin; IncomeService incomes;
    AiActionProposalRepository repo; Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiActionService actions; AiAccessPolicy policy; AiGuidedRecordService records; AiGuidedBatchService batch;
    AtomicReference<BigDecimal> net = new AtomicReference<>(new BigDecimal("3402.26"));
    AtomicInteger incomeIds = new AtomicInteger(500);
    List<Object[]> savedCalls = new ArrayList<>();
    LocalDate today, lastSunday;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        today = AppClock.today();
        lastSunday = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
        people = mock(AiActionLookup.class); fin = mock(AiFinanceLookup.class); incomes = mock(IncomeService.class);
        when(people.members(eq(CID), anyString())).thenReturn(List.of());
        when(people.members(eq(CID), argThat(a -> a != null && a.trim().equalsIgnoreCase("john smith")))).thenReturn(List.of(new Hit(5, "John Smith", "1")));
        when(people.memberById(eq(CID), anyLong())).thenAnswer(i -> (Long) i.getArgument(1) == 5L ? new Hit(5, "John Smith", "1") : null);
        List<Hit> funds = List.of(new Hit(1, "Tithe", null), new Hit(2, "Building Fund", null), new Hit(3, "General Fund", null));
        List<Hit> methods = List.of(new Hit(11, "Cash", null), new Hit(12, "Check", null), new Hit(13, "Zelle", null));
        when(fin.incomeFunds(CID)).thenReturn(funds); when(fin.methods(CID)).thenReturn(methods);
        when(fin.incomeFundById(eq(CID), anyLong())).thenAnswer(i -> funds.stream().filter(h -> h.id() == (Long) i.getArgument(1)).findFirst().orElse(null));
        when(fin.methodById(eq(CID), anyLong())).thenAnswer(i -> methods.stream().filter(h -> h.id() == (Long) i.getArgument(1)).findFirst().orElse(null));
        when(fin.netBalance(CID)).thenAnswer(i -> net.get());
        when(incomes.createIncome(any(), anyInt(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), anyString(), anyString())).thenAnswer(i -> {
            savedCalls.add(i.getArguments());
            net.set(net.get().add(i.getArgument(5)));
            Income x = new Income(); x.setId(incomeIds.incrementAndGet()); return x; });
        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        AiToolRegistry registry = new AiToolRegistry(List.of(new AiFinanceTools.CreateIncome(people, fin, incomes), new AiFinanceTools.CreateExpense(fin, mock(ExpenseService.class))));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        policy = spy(new AiAccessPolicy());
        actions = new AiActionService(registry, repo, policy, mock(OpenAiVoiceService.class), new TransactionTemplate(tm), true, false, 10);
        records = new AiGuidedRecordService(policy, actions, people, fin, null);
        records.llmFallback = false;
        batch = new AiGuidedBatchService(records, fin, actions);
        ObjectProvider<AiActionFollowUp> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of((AiActionFollowUp) batch));
        ReflectionTestUtils.setField(actions, "followUps", provider);
    }

    static MockHttpServletRequest req(String role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }

    /** One message, routed the way the controller routes it: the batch first, then the entry in progress. */
    Map<String, Object> say(MockHttpServletRequest r, String text) {
        if (AiGuidedBatchService.isStartRequest(text) || AiGuidedBatchService.hasDraft(r.getSession())) {
            Map<String, Object> m = batch.handle(same(r), CID, text);
            if (m != null) return m;
        }
        return records.handle(same(r), CID, text);
    }
    Map<String, Object> press(MockHttpServletRequest r, Map<String, Object> card) { return actions.confirm(same(r), CID, token(card)); }
    Map<String, Object> scan(MockHttpServletRequest r, Map<String, Object> fields, Long member) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("readable", true); body.put("fields", fields);
        Map<String, Object> c = new LinkedHashMap<>(); c.put("autoSelectId", member); body.put("contributor", c);
        return batch.checkScanned(same(r), CID, body);
    }
    @SuppressWarnings("unchecked") static Map<String, Object> confirm(Map<String, Object> m) { return (Map<String, Object>) m.get("confirm"); }
    @SuppressWarnings("unchecked") static Map<String, String> card(Map<String, Object> m) { return (Map<String, String>) confirm(m).get("summary"); }
    @SuppressWarnings("unchecked") static List<String> choices(Map<String, Object> m) { return (List<String>) m.get("choices"); }
    @SuppressWarnings("unchecked") static Map<String, Object> sum(Map<String, Object> m) { return (Map<String, Object>) m.get("summary"); }
    static String token(Map<String, Object> m) { return String.valueOf(confirm(m).get("token")); }
    static String ans(Map<String, Object> m) { return String.valueOf(m.get("answer")); }
    static Map<String, Object> check(String amount, String no, String payer) {
        Map<String, Object> f = new LinkedHashMap<>(); f.put("amount", amount); f.put("checkNo", no); if (payer != null) f.put("payorName", payer); f.put("date", "2026-11-15"); return f;
    }

    // ── the start ─────────────────────────────────────────────────────────────

    @Test @DisplayName("start phrases: counted checks and/or cash that was received; questions and single entries never")
    void startDetection() {
        for (String q : List.of(REQUEST, "I received 20 checks and $2,000 in cash this Sunday. Please add them to the General Fund.",
                "We got 5 checks and $350.50 cash today", "Record 12 checks to the Building Fund", "I have twenty checks to enter", "We collected $1,250 in cash this morning",
                "add several offerings", "Record multiple donations", "enter the Sunday offerings", "I have a few checks and cash of $400"))
            assertThat(AiGuidedBatchService.isStartRequest(q)).as(q).isTrue();
        for (String q : List.of("How many checks did we receive last Sunday?", "Add an income of $50 from John Smith", "Record a donation of $25", "show the checks from October",
                "what was the cash income last month", "Add John Smith to the Welcome Team", "create a meeting", "Did we get 5 checks from John?", "add an expense of $40 cash"))
            assertThat(AiGuidedBatchService.isStartRequest(q)).as(q).isFalse();
        // a single guided income keeps its own route
        assertThat(AiGuidedRecordService.isStartRequest("Add an income of $50 from John Smith")).isTrue();
    }

    @Test @DisplayName("the example conversation, step by step: guide → cash as one entry → date / contributor / optional choices → review with balances → Confirm & Save")
    void exampleConversationCash() {
        MockHttpServletRequest r = req("Accountant");
        Map<String, Object> m = say(r, REQUEST);
        assertThat(ans(m)).contains("20 checks").contains("$2,000.00 in cash").contains("General Fund").contains("guide you through adding them one by one");
        assertThat(choices(m)).containsExactly("Yes, please", "Cancel");
        assertThat(m.get("pending")).isEqualTo(true);

        m = say(r, "Yes, please");
        assertThat(ans(m)).isEqualTo("Certainly! First, how would you like to record the $2,000.00 cash offering?");
        assertThat(choices(m)).containsExactly("Single transaction", "Split into multiple transactions");

        m = say(r, "Single transaction");
        assertThat(ans(m)).isEqualTo("What is the transaction date?");
        assertThat(choices(m)).hasSize(2).anyMatch(c -> c.startsWith("Last Sunday (")).anyMatch(c -> c.startsWith("Today ("));
        assertThat(m.get("dateInput")).isEqualTo(true);

        String last = choices(m).stream().filter(c -> c.startsWith("Last Sunday")).findFirst().orElseThrow();
        m = say(r, last);
        assertThat(ans(m)).startsWith("Who should be recorded as the contributor?");
        assertThat(choices(m)).containsExactly("Anonymous", "Loose Offering", "No Contributor", "Select a Member", "Enter a Guest Name");

        m = say(r, "No Contributor");
        assertThat(ans(m)).isEqualTo("Would you like to add an optional Reference Number or Notes? A reference number can be useful for check payments.");
        assertThat(choices(m)).containsExactly("Add Reference Number", "Add Notes", "Skip & Review");

        m = say(r, "Skip & Review");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(ans(m)).startsWith("Your transaction is ready for review.");
        assertThat(confirm(m).get("label")).isEqualTo("Confirm & Save");
        assertThat(card(m)).containsEntry("Fund", "General Fund").containsEntry("Amount", "$2,000.00").containsEntry("Method", "Cash")
                .containsEntry("Date", DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH).format(lastSunday)).containsEntry("Contributor", "No contributor")
                .containsEntry("Reference number", "Not entered (optional)").containsEntry("Notes", "Not entered (optional)");
        @SuppressWarnings("unchecked") Map<String, Object> bal = (Map<String, Object>) confirm(m).get("balance");
        assertThat(bal).containsEntry("previous", "$3,402.26").containsEntry("next", "$5,402.26").containsEntry("change", "+$2,000.00");
        assertThat(savedCalls).as("nothing is saved before Confirm & Save").isEmpty();

        Map<String, Object> done = press(r, m);
        assertThat(savedCalls).hasSize(1);
        Object[] a = savedCalls.get(0);
        assertThat(a[0]).as("no member").isNull();
        assertThat(a[1]).isEqualTo(3); assertThat(a[2]).isEqualTo(lastSunday); assertThat(a[3]).isEqualTo(11);
        assertThat((BigDecimal) a[5]).isEqualByComparingTo("2000"); assertThat(a[7]).as("no guest name").isNull();
        assertThat(done.get("executed")).isEqualTo(true);
        assertThat(done.get("receipt")).isInstanceOf(Map.class);
        assertThat(String.valueOf(done.get("question"))).startsWith("Excellent! The cash offering has been recorded. Now, we can make recording the 20 checks much easier.")
                .contains("take a photo of each check").endsWith("Would you like me to help you do that?");
        assertThat(choices(done)).containsExactly("Yes", "No, finish & review summary", "Cancel");
        assertThat(done.get("pending")).isEqualTo(true);
        assertThat(done.get("incomeId")).isEqualTo(501L);

        // a second press of the same button saves nothing more
        assertThat(actions.confirm(same(r), CID, token(m)).get("executed")).isNotEqualTo(true);
        assertThat(savedCalls).hasSize(1);
    }

    /** The cash entry saved, the checks offered and accepted: ready for the first photo. */
    MockHttpServletRequest atScan() {
        MockHttpServletRequest r = req("Accountant");
        say(r, REQUEST); say(r, "Yes, please"); say(r, "Single transaction");
        say(r, "Last Sunday"); say(r, "Loose Offering");
        Map<String, Object> card = say(r, "Skip & Review");
        press(r, card);
        Map<String, Object> m = say(r, "Yes");
        assertThat(ans(m)).isEqualTo("Great! Click the Take Photo button whenever you're ready.");
        assertThat(m.get("scan")).isInstanceOf(Map.class);
        assertThat(choices(m)).containsExactly("Enter it by hand", "Finish & Review Summary", "Cancel");
        return r;
    }

    @Test @DisplayName("checks: each scan becomes a review card with the batch's fund, date and method Check; each is saved only by its own Confirm & Save; the summary counts what was saved")
    void checksAndSummary() {
        MockHttpServletRequest r = atScan();
        @SuppressWarnings("unchecked") Map<String, Object> scanInfo = (Map<String, Object>) say(r, "Scan Next Check").get("scan");
        assertThat(scanInfo).containsEntry("scanEndpoint", "/api/income/check-scan").containsEntry("submitEndpoint", "/api/ai/actions/batch-check");

        // check 1: the scan matched a member
        Map<String, Object> c1 = scan(r, check("150.00", "1042", "John Smith"), 5L);
        assertThat(c1.get("intent")).isEqualTo("guided.confirm");
        assertThat(String.valueOf(c1.get("scanned"))).contains("amount $150.00").contains("check #1042").contains("from John Smith");
        assertThat(card(c1)).containsEntry("Contributor", "John Smith").containsEntry("Fund", "General Fund").containsEntry("Amount", "$150.00")
                .containsEntry("Method", "Check").containsEntry("Reference number", "1042")
                .containsEntry("Date", DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH).format(lastSunday));
        assertThat(savedCalls).hasSize(1);                                   // only the cash so far
        Map<String, Object> d1 = press(r, c1);
        assertThat(savedCalls).hasSize(2);
        assertThat(savedCalls.get(1)[0]).isEqualTo(5); assertThat(savedCalls.get(1)[3]).isEqualTo(12); assertThat(savedCalls.get(1)[4]).isEqualTo("1042");
        assertThat(String.valueOf(d1.get("question"))).isEqualTo("Check #1042 for $150.00 has been recorded (1 of 20). Would you like to scan the next check?");
        assertThat(choices(d1)).containsExactly("Scan Next Check", "Finish & Review Summary", "Cancel");
        assertThat(d1.get("scan")).isInstanceOf(Map.class);

        // check 2: the payer is not a member → asked, recorded as a guest; the amount is corrected before saving
        Map<String, Object> c2 = scan(r, check("75.00", "2210", "Mary Jones"), null);
        assertThat(ans(c2)).contains("I couldn't find a member called “Mary Jones”");
        c2 = say(r, "Yes, record as a guest");
        assertThat(card(c2)).containsEntry("Contributor", "Mary Jones (guest)").containsEntry("Amount", "$75.00");
        c2 = say(r, "Change amount to 57");
        assertThat(card(c2)).containsEntry("Amount", "$57.00").containsEntry("Reference number", "2210");
        press(r, c2);
        assertThat(savedCalls).hasSize(3);
        assertThat((BigDecimal) savedCalls.get(2)[5]).isEqualByComparingTo("57"); assertThat(savedCalls.get(2)[7]).isEqualTo("Mary Jones");

        // check 3: cancelled on its card → not saved, and the conversation goes on
        Map<String, Object> c3 = scan(r, check("999.00", "3001", "John Smith"), 5L);
        Map<String, Object> x3 = actions.reject(same(r), CID, token(c3));
        assertThat(ans(x3)).startsWith("Okay — I have not saved that check.");
        assertThat(choices(x3)).contains("Scan Next Check", "Enter it by hand", "Finish & Review Summary");
        assertThat(savedCalls).hasSize(3);

        Map<String, Object> s = say(r, "Finish & Review Summary");
        assertThat(s.get("intent")).isEqualTo("guided.summary");
        assertThat(sum(s)).containsEntry("Cash Offering", "$2,000.00").containsEntry("Checks Recorded", "2 of 20").containsEntry("Total Check Amount", "$207.00")
                .containsEntry("Total Donations Recorded", "$2,207.00").containsEntry("Fund", "General Fund").containsEntry("Transaction Date", US.format(lastSunday));
        assertThat(ans(s)).contains("3 entries totalling $2,207.00").contains("18 of the 20 checks were not recorded").contains("Only entries you confirmed are counted");
        @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) s.get("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsExactly("1", "Cash", "Loose Offering (guest)", "$2,000.00", "", US.format(lastSunday));
        assertThat(rows.get(1)).containsExactly("2", "Check", "John Smith", "$150.00", "1042", US.format(lastSunday));
        assertThat(rows.get(2)).containsExactly("3", "Check", "Mary Jones (guest)", "$57.00", "2210", US.format(lastSunday));
        assertThat(s.get("pending")).isNull();
        assertThat(AiGuidedBatchService.hasDraft(r.getSession())).as("the conversation is over").isFalse();
        assertThat(AiGuidedRecordService.hasDraft(r.getSession())).isFalse();
        assertThat(savedCalls).as("the summary saves nothing").hasSize(3);
    }

    @Test @DisplayName("a check already recorded (in this conversation, or earlier in Income) is questioned before it is offered again")
    void duplicateChecks() {
        MockHttpServletRequest r = atScan();
        press(r, scan(r, check("150.00", "1042", "John Smith"), 5L));
        assertThat(savedCalls).hasSize(2);
        Map<String, Object> again = scan(r, check("150.00", "1042", "John Smith"), 5L);
        assertThat(ans(again)).isEqualTo("Check #1042 for $150.00 was already recorded a moment ago in this conversation. Do you want to record it again?");
        assertThat(choices(again)).containsExactly("No, scan a different check", "Yes, record it again", "Finish & Review Summary");
        Map<String, Object> no = say(r, "No, scan a different check");
        assertThat(ans(no)).startsWith("Okay — that check was not recorded again.");
        assertThat(no.get("scan")).isInstanceOf(Map.class);
        assertThat(savedCalls).hasSize(2);

        // recorded on an earlier day
        when(fin.incomeDateForCheck(eq(CID), eq("7788"), any())).thenReturn(LocalDate.of(2026, 11, 8));
        Map<String, Object> old = scan(r, check("40.00", "7788", "John Smith"), 5L);
        assertThat(ans(old)).contains("Check #7788 for $40.00 was already recorded on Sun, Nov 8, 2026");
        Map<String, Object> card = say(r, "Yes, record it again");
        assertThat(card.get("intent")).as("the entry's own duplicate question or its card — never a silent save").isIn("guided.confirm", "guided.ask");
        assertThat(savedCalls).hasSize(2);
        // the same number with a different amount is a different check
        assertThat(scan(req2(r), check("41.00", "9999", "John Smith"), 5L).get("intent")).isIn("guided.confirm", "guided.ask");
    }
    static MockHttpServletRequest req2(MockHttpServletRequest r) { return r; }

    @Test @DisplayName("a photo that could not be read: said plainly, another photo or typing it in — nothing is guessed")
    void unreadablePhotoAndManualEntry() {
        MockHttpServletRequest r = atScan();
        Map<String, Object> body = new LinkedHashMap<>(); body.put("readable", false); body.put("fields", Map.of()); body.put("error", "The photo is too dark.");
        Map<String, Object> m = batch.checkScanned(same(r), CID, body);
        assertThat(ans(m)).isEqualTo("I couldn't read that check. The photo is too dark. You can take another photo, or enter this check by hand.");
        assertThat(m.get("scanFailed")).isEqualTo(true); assertThat(m.get("scan")).isInstanceOf(Map.class);
        assertThat(choices(m)).containsExactly("Enter it by hand", "Finish & Review Summary", "Cancel");

        m = say(r, "Enter it by hand");
        assertThat(ans(m)).startsWith("Who should be recorded as the contributor?");
        m = say(r, "Select a Member");
        assertThat(ans(m)).isEqualTo("Type the member's first or last name.");
        m = say(r, "John Smith");
        assertThat(ans(m)).isEqualTo("What is the amount?");
        m = say(r, "$80");
        assertThat(ans(m)).startsWith("Would you like to add an optional Reference Number");
        m = say(r, "Add Reference Number");
        m = say(r, "5521");
        assertThat(card(m)).containsEntry("Contributor", "John Smith").containsEntry("Amount", "$80.00").containsEntry("Method", "Check").containsEntry("Reference number", "5521");
        press(r, m);
        assertThat(savedCalls).hasSize(2);
        // a partly read check: what was read is used, the rest is asked
        Map<String, Object> f = new LinkedHashMap<>(); f.put("checkNo", "6001");
        Map<String, Object> part = scan(r, f, null);
        assertThat(ans(part)).startsWith("Who should be recorded as the contributor?");
        m = say(r, "Enter a Guest Name");
        assertThat(ans(m)).isEqualTo("What is the guest's name?");
        m = say(r, "Add Notes");                                             // whatever is typed now is the name — even words that mean something elsewhere
        assertThat(ans(m)).isEqualTo("What is the amount?");
        m = say(r, "fifty");
        assertThat(ans(m)).isEqualTo("The amount is invalid. Please enter a valid numeric amount, such as $100.");
    }

    @Test @DisplayName("cash split into several entries: each part is its own confirmed entry; the rest can be taken in one step; a part larger than what is left is refused")
    void splitCash() {
        MockHttpServletRequest r = req("Accountant");
        say(r, "We collected $2,000 in cash today, add it to the General Fund");
        Map<String, Object> m = say(r, "Yes, please");
        assertThat(choices(m)).containsExactly("Single transaction", "Split into multiple transactions");
        m = say(r, "Split into multiple transactions");
        assertThat(ans(m)).isEqualTo("The cash total is $2,000.00. What is the amount of the first part?");
        assertThat(ans(say(r, "$2,500"))).startsWith("$2,500.00 is more than the $2,000.00 of cash that is left.");
        assertThat(ans(say(r, "plenty"))).startsWith("Please enter an amount, such as $500.");
        m = say(r, "$500");
        assertThat(ans(m)).isEqualTo("What is the transaction date?");
        m = say(r, "Today"); m = say(r, "Anonymous"); m = say(r, "Skip & Review");
        assertThat(card(m)).containsEntry("Amount", "$500.00").containsEntry("Contributor", "Anonymous (guest)");
        Map<String, Object> d = press(r, m);
        assertThat(String.valueOf(d.get("question"))).isEqualTo("$500.00 of the cash has been recorded. $1,500.00 is left. What is the amount of the next part?");
        assertThat(choices(d)).containsExactly("The rest ($1,500.00)", "Stop splitting");
        m = say(r, "The rest ($1,500.00)");
        assertThat(ans(m)).as("the date of the first part is offered — the contributor is asked").startsWith("Who should be recorded as the contributor?");
        m = say(r, "No Contributor"); m = say(r, "Skip & Review");
        assertThat(card(m)).containsEntry("Amount", "$1,500.00").containsEntry("Date", DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH).format(today));
        Map<String, Object> end = press(r, m);                               // no checks were named → the summary
        assertThat(savedCalls).hasSize(2);
        assertThat(sum(end)).containsEntry("Cash Offering", "$2,000.00 (2 entries)").containsEntry("Total Donations Recorded", "$2,000.00").doesNotContainKey("Checks Recorded");
        assertThat(end.get("pending")).isNull();
        assertThat(end.get("receipt")).as("the last entry's own confirmation is still shown").isInstanceOf(Map.class);
    }

    @Test @DisplayName("cancel: before anything is saved → nothing saved; during an entry → only that entry is dropped; later → what was confirmed stays, nothing more is saved")
    void cancelling() {
        MockHttpServletRequest r = req("Accountant");
        say(r, REQUEST);
        Map<String, Object> m = say(r, "Cancel");
        assertThat(m.get("intent")).isEqualTo("guided.cancelled"); assertThat(ans(m)).isEqualTo("Okay — I've stopped. Nothing was saved.");
        assertThat(AiGuidedBatchService.hasDraft(r.getSession())).isFalse();

        r = req("Accountant");
        say(r, REQUEST); say(r, "Yes, please"); say(r, "Single transaction"); say(r, "Today"); say(r, "Anonymous");
        Map<String, Object> card = say(r, "Skip & Review");
        m = say(r, "cancel");                                                 // typed while the card is open
        assertThat(ans(m)).startsWith("Okay — I have not saved that cash offering. What would you like to do next?");
        assertThat(choices(m)).containsExactly("Record the cash again", "Continue to the checks", "Finish & Review Summary", "Cancel");
        assertThat(actions.confirm(same(r), CID, token(card)).get("executed")).as("the withdrawn card cannot be saved").isNotEqualTo(true);
        assertThat(savedCalls).isEmpty();
        m = say(r, "Record the cash again");
        assertThat(ans(m)).isEqualTo("What is the transaction date?");
        say(r, "Today"); say(r, "Anonymous");
        press(r, say(r, "Skip & Review"));
        say(r, "Yes");
        press(r, scan(r, check("20.00", "1", "John Smith"), 5L));
        assertThat(savedCalls).hasSize(2);
        m = say(r, "Cancel");
        assertThat(m.get("intent")).isEqualTo("guided.summary");
        assertThat(ans(m)).startsWith("Okay — I've stopped. Nothing more was saved; the entries you already confirmed stay recorded.");
        assertThat(sum(m)).containsEntry("Checks Recorded", "1 of 20").containsEntry("Total Donations Recorded", "$2,020.00");
        assertThat(savedCalls).hasSize(2);
        assertThat(AiGuidedBatchService.hasDraft(r.getSession())).isFalse();
        // a photo after the conversation ended is not used
        assertThat(scan(r, check("5.00", "2", null), null).get("intent")).isEqualTo("guided.expired");
        assertThat(savedCalls).hasSize(2);
    }

    @Test @DisplayName("permissions and plan: the Income page's write rule, checked at the start, on every message and when a photo arrives; no fund named → asked")
    void permissionsAndFund() {
        MockHttpServletRequest user = req("User");
        Map<String, Object> m = say(user, REQUEST);
        assertThat(m.get("denied")).isEqualTo(true); assertThat(ans(m)).contains("doesn't have permission to add income").contains("Nothing was saved");
        assertThat(AiGuidedBatchService.hasDraft(user.getSession())).isFalse();
        verify(fin, never()).incomeFunds(anyString());

        MockHttpServletRequest noEdit = req("Accountant");
        noEdit.getSession().setAttribute("privileges", "{\"accounting.income.edit\":false}");
        assertThat(say(noEdit, REQUEST).get("denied")).isEqualTo(true);

        doReturn(false).when(policy).feature(anyString(), eq(AiGuidedRecordService.FEATURE));
        assertThat(say(req("Accountant"), REQUEST).get("intent")).isEqualTo("guided.unavailable");
        doReturn(true).when(policy).feature(anyString(), anyString());

        // permission taken away in the middle
        MockHttpServletRequest r = atScan();
        r.getSession().setAttribute("privileges", "{\"accounting.income.edit\":false}");
        Map<String, Object> gone = scan(r, check("10.00", "77", "John Smith"), 5L);
        assertThat(gone.get("denied")).isEqualTo(true);
        assertThat(AiGuidedBatchService.hasDraft(r.getSession())).isFalse();
        assertThat(savedCalls).hasSize(1);

        // no fund in the request
        MockHttpServletRequest f = req("SuperAdmin");
        m = say(f, "I received 3 checks and $90 in cash");
        assertThat(ans(m)).isEqualTo("Which fund should these donations go to?");
        assertThat(choices(m)).containsExactly("Tithe", "Building Fund", "General Fund");
        assertThat(ans(say(f, "Roof"))).startsWith("I couldn't match “Roof” to one of your funds.");
        m = say(f, "Building Fund");
        assertThat(ans(m)).contains("3 checks and $90.00 in cash in the Building Fund");
    }

    @Test @DisplayName("an entry left unfinished, another conversation started, and what the existing single-income conversation still does")
    void edgesAndExistingBehaviour() {
        // another conversation's start drops the batch (GuidedDrafts)
        MockHttpServletRequest r = req("Accountant");
        say(r, REQUEST);
        GuidedDrafts.clearOthers(r.getSession(), GuidedDrafts.RECORD);
        assertThat(AiGuidedBatchService.hasDraft(r.getSession())).isFalse();

        // the single guided income is unchanged: its own questions, choices and button
        MockHttpServletRequest s = req("Accountant");
        Map<String, Object> m = records.handle(s, CID, "Add an income of $50 to Tithe by cash");
        assertThat(ans(m)).isEqualTo("Who is the income from? Give a member's name, or a guest name (Anonymous and Loose Offering are fine).");
        assertThat(choices(m)).containsExactly("Anonymous", "Loose Offering");
        m = records.handle(same(s), CID, "No Contributor");
        assertThat(ans(m)).as("'No Contributor' is offered only in the several-donations conversation").contains("I couldn't find a member called");
        m = records.handle(same(s), CID, "No, choose another name"); m = records.handle(same(s), CID, "Anonymous");
        assertThat(ans(m)).isEqualTo("What date should I record? (for example today, yesterday or Nov 3)");
        assertThat(choices(m)).containsExactly("Today"); assertThat(m.get("dateInput")).isNull();
        m = records.handle(same(s), CID, "Today");
        assertThat(choices(m)).containsExactly("Add a reference number", "Add notes", "Skip and review");
        m = records.handle(same(s), CID, "Skip and review");
        assertThat(confirm(m).get("label")).isEqualTo("Save income");
        assertThat(confirm(m).get("balance")).as("the balance preview is on every income card").isInstanceOf(Map.class);
        Map<String, Object> done = actions.confirm(same(s), CID, token(m));
        assertThat(done.get("pending")).as("no follow-up for a single income").isNull();
        assertThat(done.get("question")).isNull();

        // the tool still refuses an entry with no contributor unless "No Contributor" was chosen
        AiTool tool = new AiFinanceTools.CreateIncome(people, fin, incomes);
        Map<String, Object> raw = new LinkedHashMap<>(Map.of("fundId", 3, "amount", "10", "date", today.toString(), "methodId", 11));
        assertThat(tool.resolve(raw, CID, "pat").isOk()).isFalse();
        raw.put("noContributor", true);
        assertThat(tool.resolve(raw, CID, "pat").isOk()).isTrue();
    }

    @Test @DisplayName("wiring: the route order, the scan hand-over endpoint, the chat's GUI pieces and the help text")
    void wiring() throws Exception {
        String c = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        assertThat(c.indexOf("batch.handle(request, clientId, question)")).isGreaterThan(c.indexOf("manage.handle(request, clientId, question"))
                .isLessThan(c.indexOf("AiGuidedRecordService.isStartRequest(question)"));
        String a = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiActionController.java"));
        assertThat(a).contains("@PostMapping(\"/api/ai/actions/batch-check\")").contains("AiWritingController.gate(req, \"AI actions\")");
        String svc = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/guided/AiGuidedBatchService.java"));
        assertThat(svc).as("the batch conversation never writes: no Income service, no repository").doesNotContain("IncomeService").doesNotContain("Repository").doesNotContain("createIncome");
        String js = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(js).contains("aa-scan").contains("aa-date-in").contains("aa-balance").contains("function scanCheckPhoto(").contains("/api/income/check-scan");
    }
}

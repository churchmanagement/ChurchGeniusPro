package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionLookup.Person;
import com.churchgeniuspro.ai.guided.AiGuidedEmailService;
import com.churchgeniuspro.ai.guided.AiGuidedGroupService;
import com.churchgeniuspro.ai.guided.AiGuidedPledgeService;
import com.churchgeniuspro.ai.guided.AiPageLinkService;
import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.repository.AiActionProposalRepository;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.GroupService;
import com.churchgeniuspro.service.NotifyEmailService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-11 — Genius Assistant: guided "add a group" (with members), pledge campaigns, composing and sending an
 * e-mail, and links to the pages it does not do in the chat (Add Family, Bank Import, Payroll, Events…).
 */
@DisplayName("Genius Assistant — guided groups, pledge campaigns, e-mail and page links")
class GeniusGuidedGroupsPledgesEmailTest {

    static final String CID = "CHR-A";
    GroupRepository groupRepo; GroupService groupService; GroupMemberService groupMembers; AiActionLookup lookup;
    PledgeCampaignRepository campaigns; AiFinanceLookup funds; NotifyEmailService mail; AiWritingService writer;
    AiActionProposalRepository repo; Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiToolRegistry registry; AiActionService actions; AiAccessPolicy policy;
    AiGuidedGroupService groups; AiGuidedPledgeService pledges; AiGuidedEmailService emails; AiPageLinkService links;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        groupRepo = mock(GroupRepository.class); groupService = mock(GroupService.class); groupMembers = mock(GroupMemberService.class); lookup = mock(AiActionLookup.class);
        campaigns = mock(PledgeCampaignRepository.class); funds = mock(AiFinanceLookup.class); mail = mock(NotifyEmailService.class); writer = mock(AiWritingService.class);
        when(groupRepo.existsByGroupNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(argThat(n -> "Youth".equalsIgnoreCase(n)), eq(CID))).thenReturn(true);
        when(groupService.create(anyString(), eq(CID))).thenAnswer(i -> { Group g = new Group(); g.setId(70); g.setGroupName(i.getArgument(0)); return g; });
        when(lookup.groupById(eq(CID), eq(70L))).thenReturn(new Hit(70, "Test", null));
        Person davis = new Person(5, "Daniel", "Davis", "daniel.davis@example.test"), manager = new Person(6, "Daniel", "Manager", "dm@example.test");
        when(lookup.searchPeople(eq(CID), argThat(t -> t != null && t.toLowerCase().contains("daniel")), anyInt())).thenReturn(List.of(davis, manager));
        when(lookup.searchPeople(eq(CID), argThat(t -> t != null && !t.toLowerCase().contains("daniel")), anyInt())).thenReturn(List.of());
        when(lookup.personById(CID, 5)).thenReturn(davis); when(lookup.personById(CID, 6)).thenReturn(manager);
        when(lookup.groupSize(eq(CID), anyLong())).thenReturn(2);
        List<Hit> income = List.of(new Hit(31, "Building Fund", null), new Hit(32, "Tithe", null));
        when(funds.incomeFunds(CID)).thenReturn(income);
        when(funds.incomeFundById(eq(CID), anyLong())).thenAnswer(i -> income.stream().filter(h -> h.id() == (Long) i.getArgument(1)).findFirst().orElse(null));
        when(campaigns.findByClientId(CID)).thenReturn(List.of());
        when(campaigns.save(any())).thenAnswer(i -> { PledgeCampaign c = i.getArgument(0); c.setId(9); return c; });
        when(mail.getRecipients(eq("members"), any(), eq(CID))).thenReturn(List.of(Map.of("email", "a@x.test"), Map.of("email", "b@x.test"), Map.of("email", "c@x.test")));
        when(mail.getRecipients(eq("church-guests"), any(), eq(CID))).thenReturn(List.of());
        when(mail.sendEmail(any(), any(), any(), any(), any(), any(), any())).thenReturn(3);
        when(writer.isEnabled()).thenReturn(true);
        when(writer.write(any())).thenAnswer(i -> { AiWritingService.Request r = i.getArgument(0);
            return new AiWritingService.Draft("Christmas Eve", "[" + r.action() + "] Dear church family, join us.", 40, null); });

        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        registry = new AiToolRegistry(List.of(new AiGroupTools.CreateGroup(groupRepo, groupService), new AiGroupTools.AddGroupPerson(lookup, groupMembers),
                new AiPledgeTools.CreateCampaign(campaigns, funds)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        policy = spy(new AiAccessPolicy());
        actions = new AiActionService(registry, repo, policy, mock(OpenAiVoiceService.class), new TransactionTemplate(tm), true, false, 10);
        groups = new AiGuidedGroupService(policy, actions, lookup);
        pledges = new AiGuidedPledgeService(policy, actions, funds, campaigns);
        emails = new AiGuidedEmailService(policy, actions, mail, writer);
        links = new AiPageLinkService(policy);
        ObjectProvider<AiActionFollowUp> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of((AiActionFollowUp) groups));
        ReflectionTestUtils.setField(actions, "followUps", provider);
    }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CID); r.getSession().setAttribute("clientId", "USR-1");
        if (privileges != null) r.getSession().setAttribute("privileges", privileges);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    @SuppressWarnings("unchecked") static Map<String, Object> confirm(Map<String, Object> m) { return (Map<String, Object>) m.get("confirm"); }
    @SuppressWarnings("unchecked") static Map<String, Object> email(Map<String, Object> m) { return (Map<String, Object>) m.get("email"); }
    @SuppressWarnings("unchecked") static List<String> choices(Map<String, Object> m) { return (List<String>) m.get("choices"); }
    static String token(Map<String, Object> m) { return String.valueOf(confirm(m).get("token")); }
    static String text(Map<String, Object> m) { return String.valueOf(m.get("answer")); }
    Map<String, Object> g(MockHttpServletRequest r, String t) { return groups.handle(same(r), CID, t); }
    Map<String, Object> p(MockHttpServletRequest r, String t) { return pledges.handle(same(r), CID, t); }
    Map<String, Object> e(MockHttpServletRequest r, String t) { return emails.handle(same(r), CID, t); }
    Map<String, Object> save(MockHttpServletRequest r, Map<String, Object> card) { return actions.confirm(same(r), CID, token(card)); }

    /* ───────────────────────────── groups ───────────────────────────── */

    @Test @DisplayName("add group: name → Save → created → add a member (existing: search, no match, pick) → another (new, no e-mail) → done")
    void groupConversation() {
        MockHttpServletRequest r = req("Admin", null);
        Map<String, Object> m = groups.handle(r, CID, "Add Group");
        assertThat(m.get("asking")).isEqualTo("name");
        m = g(r, "Test");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(confirm(m).get("label")).isEqualTo("Save group");
        verifyNoInteractions(groupService);                                   // nothing before the explicit Save

        m = save(r, m);
        verify(groupService, times(1)).create("Test", CID);
        assertThat(m.get("intent")).isEqualTo("action.done");
        assertThat(text(m)).contains("was created").contains("add a member");
        assertThat(m.get("pending")).isEqualTo(true);
        assertThat(choices(m)).containsExactly("Yes", "No");
        assertThat(m).doesNotContainKey("_toolResult");

        m = g(r, "Yes");
        assertThat(choices(m)).containsExactly("Existing member", "New person");
        m = g(r, "existing");
        assertThat(text(m)).contains("first name, last name or email");
        m = g(r, "Zzz");
        assertThat(text(m)).contains("couldn't find anyone");
        assertThat(choices(m)).containsExactly("Try again", "New person", "Go to Groups", "Cancel");
        m = g(r, "Try again");
        m = g(r, "Daniel");
        assertThat(choices(m)).hasSize(3).anySatisfy(c -> assertThat(c).startsWith("Daniel Davis"));
        m = g(r, choices(m).get(0));
        assertThat(confirm(m).get("label")).isEqualTo("Add to group");
        verifyNoInteractions(groupMembers);
        m = save(r, m);
        verify(groupMembers, times(1)).addMember(eq(70), eq("Daniel"), eq("Davis"), eq("daniel.davis@example.test"), eq(CID), any());
        assertThat(text(m)).contains("Daniel Davis has been added").contains("another member");

        m = g(r, "yes"); m = g(r, "New");
        assertThat(text(m)).contains("Daniel Ben");
        m = g(r, "Daniel Ben");
        assertThat(m.get("asking")).isEqualTo("new_email");
        m = g(r, "skip");
        assertThat(text(m)).contains("haven't entered an email address").contains("won't be included");
        assertThat(choices(m)).containsExactly("Yes", "No");
        m = g(r, "Yes");
        assertThat(m.get("asking")).isEqualTo("new_email");
        m = g(r, "not-an-address");
        assertThat(m.get("asking")).isEqualTo("new_email");
        m = g(r, "daniel.ben@example.test");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        m = save(r, m);
        verify(groupMembers, times(1)).addMember(eq(70), eq("Daniel"), eq("Ben"), eq("daniel.ben@example.test"), eq(CID), isNull());
        m = g(r, "No");
        assertThat(m.get("intent")).isEqualTo("guided.done");
        assertThat(m.get("pending")).isNull();
        assertThat(g(r, "No")).isNull();                                      // the conversation is over
    }

    @Test @DisplayName("group: duplicate name asked again; Cancel on a person card asks for a different member; a card saves once")
    void groupEdges() {
        MockHttpServletRequest r = req("SuperAdmin", null);
        Map<String, Object> m = groups.handle(r, CID, "create a group called Youth");
        assertThat(text(m)).contains("already exists");
        assertThat(m.get("confirm")).isNull();
        m = g(r, "Test");
        Map<String, Object> card = m;
        save(r, card);
        assertThat(save(r, card).get("intent")).isEqualTo("action.expired");
        verify(groupService, times(1)).create(anyString(), anyString());

        g(r, "yes"); g(r, "Existing member"); m = g(r, "Daniel"); m = g(r, choices(m).get(1));
        m = actions.reject(same(r), CID, token(m));
        assertThat(text(m)).contains("was not added").contains("different member");
        assertThat(m.get("pending")).isEqualTo(true);
        verifyNoInteractions(groupMembers);
        m = g(r, "no");
        assertThat(m.get("intent")).isEqualTo("guided.done");
    }

    @Test @DisplayName("group: only logins that may edit Groups; the tools are guided-only")
    void groupPermissions() {
        assertThat(groups.handle(req("User", null), CID, "add group").get("denied")).isEqualTo(true);
        assertThat(groups.handle(req("Accountant", null), CID, "add group").get("denied")).isEqualTo(true);
        assertThat(groups.handle(req("Admin", "{\"admin.groups.edit\":false}"), CID, "add group").get("denied")).isEqualTo(true);
        MockHttpServletRequest church = req("SuperAdmin", null); church.getSession().setAttribute("church", true);
        assertThat(groups.handle(church, CID, "add group").get("denied")).isEqualTo(true);
        MockHttpServletRequest member = req("Member", null); member.getSession().setAttribute("memberId", 9);
        assertThat(groups.handle(member, CID, "add group").get("denied")).isEqualTo(true);
        verifyNoInteractions(groupService, groupMembers, lookup);
        for (AiTool t : registry.all()) assertThat(t.guidedOnly()).as(t.name()).isTrue();
        assertThat(String.valueOf(registry.describe())).doesNotContain("create_group").doesNotContain("add_group_person").doesNotContain("create_pledge_campaign");
        assertThat(AiGuidedGroupService.isStartRequest("how do I add a group")).isFalse();
        assertThat(AiGuidedGroupService.isStartRequest("I want to create a new group")).isTrue();
    }

    /* ───────────────────────────── pledge campaigns ───────────────────────────── */

    @Test @DisplayName("pledge campaign: name required, optional details offered, review card, Save stores an Active campaign")
    void pledgeCampaign() {
        MockHttpServletRequest r = req("Accountant", null);
        Map<String, Object> m = pledges.handle(r, CID, "create a pledge campaign");
        assertThat(m.get("asking")).isEqualTo("name");
        m = p(r, "Roof 2027");
        assertThat(m.get("asking")).isEqualTo("optional");
        assertThat(choices(m)).contains("Add a fund", "Add a target amount", "Add an end date", "Skip and review");
        m = p(r, "Add a target amount"); m = p(r, "abc");
        assertThat(m.get("asking")).isEqualTo("target");
        m = p(r, "$25,000");
        m = p(r, "Add a fund");
        assertThat(choices(m)).contains("Building Fund", "Tithe");
        m = p(r, "Building Fund");
        m = p(r, "Skip and review");
        assertThat(m.get("intent")).isEqualTo("guided.confirm");
        assertThat(confirm(m).get("label")).isEqualTo("Save campaign");
        verify(campaigns, never()).save(any());
        m = save(r, m);
        assertThat(m.get("intent")).isEqualTo("action.done");
        ArgumentCaptor<PledgeCampaign> c = ArgumentCaptor.forClass(PledgeCampaign.class);
        verify(campaigns, times(1)).save(c.capture());
        assertThat(c.getValue().getName()).isEqualTo("Roof 2027");
        assertThat(c.getValue().getClientId()).isEqualTo(CID);
        assertThat(c.getValue().getStatus()).isEqualTo("Active");
        assertThat(c.getValue().getTargetAmount()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(c.getValue().getSubSourceId()).isEqualTo(31);
    }

    @Test @DisplayName("pledge campaign: one sentence fills the fields; permission = the Pledges page's edit rule; a fund of another church is refused at Save")
    void pledgeEdges() {
        MockHttpServletRequest r = req("SuperAdmin", null);
        Map<String, Object> m = pledges.handle(r, CID, "start a building campaign called Hall Extension with a target of $5,000 ending 6/30/2027");
        m = p(r, "Skip and review");
        assertThat(String.valueOf(confirm(m).get("fields"))).contains("Hall Extension").contains("$5,000.00").contains("2027");
        assertThat(p(r, "cancel").get("intent")).isEqualTo("guided.cancelled");
        verify(campaigns, never()).save(any());

        assertThat(pledges.handle(req("User", null), CID, "create a pledge campaign").get("denied")).isEqualTo(true);
        assertThat(pledges.handle(req("Admin", "{\"accounting.pledges.edit\":false}"), CID, "create a pledge campaign").get("denied")).isEqualTo(true);
        assertThat(new AiPledgeTools.CreateCampaign(campaigns, funds).resolve(new HashMap<>(Map.of("name", "X", "fundId", 999)), CID, "pat").refusal()).contains("no longer one of this church's funds");
    }

    /* ───────────────────────────── page links ───────────────────────────── */

    @Test @DisplayName("add a family / payroll / bank import… → a sentence and the page link, only when the login may open the page")
    void pageLinks() {
        MockHttpServletRequest r = req("Admin", null);
        doReturn(Map.of("/viewfamily", true, "/payroll", false, "/events", true, "/bank-import", true)).when(policy).navAccess(any(), eq(CID));
        Map<String, Object> m = links.handle(r, CID, "create a family", false);
        assertThat(m.get("intent")).isEqualTo("page.link");
        assertThat(String.valueOf(m.get("link"))).contains("/family");
        m = links.handle(r, CID, "run payroll", false);
        assertThat(m.get("link")).isNull();
        assertThat(m.get("noAccess")).isEqualTo(true);
        assertThat(text(m)).contains("doesn't have access");
        assertThat(String.valueOf(links.handle(r, CID, "I want to create an event", false).get("link"))).contains("/events");
        assertThat(String.valueOf(links.handle(r, CID, "import a bank statement", true).get("answer"))).contains("still open");
        for (String q : List.of("add family", "sync the bank", "print a certificate", "set up a public screen", "raise a support ticket", "delete my account", "add a ministry class"))
            assertThat(AiPageLinkService.isLinkRequest(q)).as(q).isTrue();
        for (String q : List.of("how do I add a family", "add a member to the Youth group", "add an income of $50", "create a pledge campaign", "compose an email", "add group", "show families"))
            assertThat(AiPageLinkService.isLinkRequest(q)).as(q).isFalse();
    }

    /* ───────────────────────────── e-mail ───────────────────────────── */

    @Test @DisplayName("e-mail written by hand: subject, text, recipients (group e-mail addresses checked), review — sent only by the token, once")
    void emailManual() throws Exception {
        MockHttpServletRequest r = req("Admin", null);
        Map<String, Object> m = emails.handle(r, CID, "Compose Email");
        assertThat(choices(m)).containsExactly("Write with AI", "Write it myself");
        m = e(r, "Write it myself");
        assertThat(m.get("asking")).isEqualTo("subject");
        m = e(r, "Sunday picnic");
        assertThat(m.get("multiline")).isEqualTo(true);
        m = e(r, "Dear friends,\n\nJoin us.\n<script>x</script>");
        assertThat(choices(m)).containsExactly("Members", "Event Registrants", "Church Guests", "Group Email");
        m = e(r, "Group Email");
        m = e(r, "a@example.test, bad");
        assertThat(text(m)).contains("bad");
        m = e(r, "a@example.test, b@example.test");
        assertThat(m.get("intent")).isEqualTo("email.review");
        assertThat(email(m).get("count")).isEqualTo(2);
        String first = String.valueOf(email(m).get("token"));
        m = e(r, "Change the subject to Church picnic");
        assertThat(email(m).get("subject")).isEqualTo("Church picnic");
        String token = String.valueOf(email(m).get("token"));
        assertThat(token).isNotEqualTo(first);
        verify(mail, never()).sendEmail(any(), any(), any(), any(), any(), any(), any());

        assertThat(emails.send(same(r), CID, first, null).get("intent")).isEqualTo("email.expired");       // a replaced card's token
        verify(mail, never()).sendEmail(any(), any(), any(), any(), any(), any(), any());

        MockMultipartFile file = new MockMultipartFile("files", "note.txt", "text/plain", "hello".getBytes());
        m = emails.send(same(r), CID, token, List.of(file));
        assertThat(m.get("intent")).isEqualTo("email.sent");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mail, times(1)).sendEmail(eq("group-email"), isNull(), eq("a@example.test,b@example.test"), eq("Church picnic"), html.capture(), argThat(l -> l != null && l.size() == 1), eq(CID));
        assertThat(html.getValue()).contains("Dear friends,").doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(emails.send(same(r), CID, token, null).get("intent")).isEqualTo("email.expired");        // one use
        verify(mail, times(1)).sendEmail(any(), any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("e-mail with AI: subject, tone, length, language → draft shown; rewrite / shorten / edit; then recipients and review")
    void emailWithAi() throws Exception {
        MockHttpServletRequest r = req("SuperAdmin", null);
        Map<String, Object> m = emails.handle(r, CID, "I want to create an email");
        m = e(r, "Write with AI");
        m = e(r, "Christmas Eve service invitation");
        for (int i = 0; i < 6 && m.get("preview") == null; i++) {
            assertThat(m.get("pending")).isEqualTo(true);
            m = e(r, choices(m) != null && !choices(m).isEmpty() ? choices(m).get(0) : "Invite everyone to the service at 6 pm");
        }
        assertThat(String.valueOf(m.get("preview"))).contains("[draft]");
        assertThat(choices(m)).contains("Use this draft", "Rewrite", "Shorten", "Expand", "Fix grammar", "Translate", "Edit the text");
        m = e(r, "Shorten");
        assertThat(String.valueOf(m.get("preview"))).contains("[shorten]");
        m = e(r, "Edit the text"); m = e(r, "My own words.");
        assertThat(String.valueOf(m.get("preview"))).contains("My own words.");
        m = e(r, "Use this draft");
        assertThat(choices(m)).contains("Members", "Group Email");
        m = e(r, "Members");
        assertThat(m.get("intent")).isEqualTo("email.review");
        assertThat(email(m).get("count")).isEqualTo(3);
        m = e(r, "Change the recipients"); m = e(r, "Church Guests");
        assertThat(m.get("email")).isNull();                                   // nobody in that list → asked again
        assertThat(e(r, "cancel").get("intent")).isEqualTo("guided.cancelled");
        verify(mail, never()).sendEmail(any(), any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("e-mail: only logins that may use the Email page; no AI choice when AI writing is off; the draft never leaves without Send")
    void emailPermissions() throws Exception {
        assertThat(emails.handle(req("Accountant", null), CID, "compose an email").get("denied")).isEqualTo(true);
        assertThat(emails.handle(req("User", "{\"general.email\":false}"), CID, "compose an email").get("denied")).isEqualTo(true);
        MockHttpServletRequest member = req("Member", null); member.getSession().setAttribute("memberId", 9);
        assertThat(emails.handle(member, CID, "compose an email").get("denied")).isEqualTo(true);
        when(writer.isEnabled()).thenReturn(false);
        Map<String, Object> m = emails.handle(req("User", null), CID, "send an email to the members");
        assertThat(m.get("asking")).isEqualTo("subject");
        assertThat(text(m)).contains("AI writing isn't available");
        assertThat(AiGuidedEmailService.toHtml("a < b\n\nc")).doesNotContain("a < b").contains("a &lt; b");
        verify(mail, never()).sendEmail(any(), any(), any(), any(), any(), any(), any());
    }

    /* ───────────────────────────── wiring ───────────────────────────── */

    @Test @DisplayName("wiring: routes, the send endpoint, panel rendering and the audit channel")
    void wiring() throws Exception {
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiDataSearchController.java"));
        assertThat(ctl).contains("AiGuidedEmailService").contains("AiGuidedPledgeService").contains("AiGuidedGroupService").contains("AiPageLinkService");
        String send = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/AiEmailComposeController.java"));
        assertThat(send).contains("/api/ai/email/send").contains("mayCompose");
        String svc = Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/guided/AiGuidedEmailService.java"));
        assertThat(svc.split("mail\\.sendEmail\\(", -1)).hasSize(2);           // exactly one place sends
        for (String f : List.of("ai/action/AiGroupTools.java", "ai/action/AiPledgeTools.java", "ai/guided/AiGuidedGroupService.java", "ai/guided/AiGuidedPledgeService.java", "ai/guided/AiPageLinkService.java"))
            assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/" + f))).as(f).doesNotContain("EmailService").doesNotContain("SmsService").doesNotContain("WebPush");
        String js = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(js).contains("aa-send").contains("fd.append('token', token)").contains("aa-preview").contains("wireAttach").contains("_aaServerPending=isPendingAnswer(d)");
        String chat = Files.readString(Path.of("src/main/resources/static/ai-chat.js"));
        assertThat(chat).contains("ga-review-email").contains("outcomeOf");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/ai/audit/AiAuditService.java"))).contains("\"/api/ai/email\"");
    }
}

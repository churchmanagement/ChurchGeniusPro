package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.action.*;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.controller.AiActionController;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AI Phase 4 — the action agent: deterministic parser, propose → confirm state machine, re-checks at confirm, gates. */
@DisplayName("AI Phase 4 — action agent")
class AiActionAgentTest {

    static final String CID = "CHR-A";
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);   // a Thursday
    AiActionLookup lookup; GroupMemberService groups; FollowUpRepository followUps; NoteRepository notes; VolunteerAssignmentRepository assignments;
    MeetingTypeRepository types; MeetingRepository meetings; MeetingService meetingService; OpenAiVoiceService openAi;
    Map<Long, AiActionProposal> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AiActionProposalRepository repo; AiToolRegistry registry; AiActionService svc;

    @BeforeEach
    void setUp() {
        lookup = mock(AiActionLookup.class); groups = mock(GroupMemberService.class); followUps = mock(FollowUpRepository.class); notes = mock(NoteRepository.class);
        assignments = mock(VolunteerAssignmentRepository.class); types = mock(MeetingTypeRepository.class); meetings = mock(MeetingRepository.class); meetingService = mock(MeetingService.class);
        openAi = mock(OpenAiVoiceService.class);
        when(lookup.members(eq(CID), ci("sarah lee"))).thenReturn(List.of(new Hit(5, "Sarah Lee", "1")));
        when(lookup.members(eq(CID), ci("john"))).thenReturn(List.of(new Hit(6, "John Moe", "1"), new Hit(7, "John Ray", "2")));
        when(lookup.members(eq(CID), ci("john moe"))).thenReturn(List.of(new Hit(6, "John Moe", "1")));
        when(lookup.members(eq(CID), ci("nobody here"))).thenReturn(List.of());
        when(lookup.groups(eq(CID), ci("welcome team"))).thenReturn(List.of(new Hit(10, "Welcome Team", "")));
        when(lookup.groups(eq(CID), ci("choir"))).thenReturn(List.of(new Hit(11, "Choir", "")));
        when(lookup.families(eq(CID), ci("smiths"))).thenReturn(List.of(new Hit(20, "Sam Smith family", "Smith")));
        when(lookup.volunteerRoles(eq(CID), ci("usher"))).thenReturn(List.of(new Hit(30, "Usher", "Hospitality")));
        when(lookup.memberById(eq(CID), anyLong())).thenAnswer(i -> { long id = i.getArgument(1); return id == 5 ? new Hit(5, "Sarah Lee", "1") : id == 6 ? new Hit(6, "John Moe", "1") : id == 7 ? new Hit(7, "John Ray", "2") : null; });
        when(lookup.groupById(eq(CID), anyLong())).thenAnswer(i -> { long id = i.getArgument(1); return id == 10 ? new Hit(10, "Welcome Team", "") : id == 11 ? new Hit(11, "Choir", "") : null; });
        when(lookup.familyById(eq(CID), eq(20L))).thenReturn(new Hit(20, "Sam Smith family", "Smith"));
        when(lookup.volunteerRoleById(eq(CID), eq(30L))).thenReturn(new Hit(30, "Usher", "Hospitality"));
        when(lookup.openFollowUps(eq(CID), ci("the lees"), isNull())).thenReturn(List.of(new Hit(40, "Visit the Lees", "")));
        when(followUps.save(any())).thenAnswer(i -> { FollowUp f = i.getArgument(0); if (f.getId() == null) f.setId(99L); return f; });
        when(followUps.findByIdAndClientIdAndDeleteFlagFalse(40L, CID)).thenAnswer(i -> { FollowUp f = new FollowUp(); f.setId(40L); f.setTitle("Visit the Lees"); f.setStatus("PENDING"); return Optional.of(f); });
        when(groups.addMember(anyInt(), anyString(), anyString(), any(), anyString(), anyInt())).thenAnswer(i -> { GroupMember gm = new GroupMember(); gm.setId(77); return gm; });
        repo = mock(AiActionProposalRepository.class);
        when(repo.save(any())).thenAnswer(i -> { AiActionProposal p = i.getArgument(0); if (p.getId() == null) p.setId(ids.incrementAndGet()); store.put(p.getId(), p); return p; });
        when(repo.findByTokenAndAppClientIdAndStatus(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.values().stream().filter(p -> i.getArgument(0).equals(p.getToken()) && i.getArgument(1).equals(p.getAppClientId()) && i.getArgument(2).equals(p.getStatus())).findFirst());
        registry = new AiToolRegistry(List.of(new AiTools.AddFollowUp(lookup, followUps), new AiTools.CompleteFollowUp(lookup, followUps), new AiTools.AddFamilyNote(lookup, notes),
                new AiTools.AddGroupMember(lookup, groups), new AiTools.CreateMeeting(lookup, types, meetings, meetingService), new AiTools.AssignVolunteer(lookup, assignments)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        svc = new AiActionService(registry, repo, new AiAccessPolicy(), openAi, new TransactionTemplate(tm), true, true, 10);
    }

    /** Case-insensitive, whitespace-normalised name matcher (the real lookup normalises; the mock must too). */
    static String ci(String expected) { return argThat(a -> a != null && a.trim().replaceAll("\\s+", " ").equalsIgnoreCase(expected)); }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat"); r.getSession().setAttribute("role", role); r.getSession().setAttribute("appClientId", CID);
        if (privileges != null) r.getSession().setAttribute("privileges", privileges);
        return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }

    @Test @DisplayName("deterministic parser: the six phrasings, dates, how-to and meeting phrasing left alone")
    void parser() {
        var e = AiActionService.parse("Add Sarah Lee to Welcome Team", TODAY);
        assertThat(e.getKey()).isEqualTo("add_group_member"); assertThat(e.getValue()).containsEntry("member", "Sarah Lee").containsEntry("group", "Welcome Team");
        e = AiActionService.parse("please put John Moe in the Choir group", TODAY);
        assertThat(e.getKey()).isEqualTo("add_group_member"); assertThat(e.getValue()).containsEntry("member", "John Moe").containsEntry("group", "Choir");
        e = AiActionService.parse("Add a note to the Smiths: new baby arrived", TODAY);
        assertThat(e.getKey()).isEqualTo("add_family_note"); assertThat(e.getValue()).containsEntry("family", "Smiths").containsEntry("note", "new baby arrived");
        e = AiActionService.parse("add a follow-up for Sarah Lee: call about baptism, due Friday", TODAY);
        assertThat(e.getKey()).isEqualTo("add_follow_up"); assertThat(e.getValue()).containsEntry("member", "Sarah Lee").containsEntry("title", "call about baptism").containsEntry("dueDate", "2026-11-06");
        e = AiActionService.parse("Remind me to call Pat by 11/20", TODAY);
        assertThat(e.getKey()).isEqualTo("add_follow_up"); assertThat(e.getValue()).containsEntry("title", "call Pat").containsEntry("dueDate", "2026-11-20");
        e = AiActionService.parse("Mark the follow-up for the Lees as done", TODAY);
        assertThat(e.getKey()).isEqualTo("complete_follow_up"); assertThat(e.getValue()).containsEntry("match", "the Lees");
        e = AiActionService.parse("complete the follow up about Sarah Lee", TODAY);
        assertThat(e.getKey()).isEqualTo("complete_follow_up"); assertThat(e.getValue()).containsEntry("match", "Sarah Lee");
        e = AiActionService.parse("Assign John Moe as usher on Sunday", TODAY);
        assertThat(e.getKey()).isEqualTo("assign_volunteer"); assertThat(e.getValue()).containsEntry("member", "John Moe").containsEntry("role", "usher").containsEntry("date", "2026-11-08");
        e = AiActionService.parse("assign Sarah Lee to greeter for Nov 23", TODAY);
        assertThat(e.getValue()).containsEntry("role", "greeter").containsEntry("date", "2026-11-23");
        // not actions
        for (String q : List.of("How do I add someone to a group?", "Who is in Welcome Team?", "list follow-ups", "Create a deacons meeting on Nov 20", "show members at risk", "add"))
            assertThat(AiActionService.parse(q, TODAY)).as(q).isNull();
        // dates
        assertThat(AiActionService.date("tomorrow", TODAY)).isEqualTo("2026-11-06");
        assertThat(AiActionService.date("this thursday", TODAY)).isEqualTo("2026-11-05");
        assertThat(AiActionService.date("thursday", TODAY)).isEqualTo("2026-11-12");
        assertThat(AiActionService.date("Dec 25", TODAY)).isEqualTo("2026-12-25");
        assertThat(AiActionService.date("Jan 3", TODAY)).isEqualTo("2027-01-03");
        assertThat(AiActionService.date("3rd of January 2027", TODAY)).isEqualTo("2027-01-03");
        assertThat(AiActionService.date("someday", TODAY)).isEqualTo("someday");
        assertThat(AiActionService.pick("2", List.of("John Moe", "John Ray"))).isEqualTo("John Ray");
        assertThat(AiActionService.pick("moe", List.of("John Moe", "John Ray"))).isEqualTo("John Moe");
        assertThat(AiActionService.pick("john", List.of("John Moe", "John Ray"))).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("propose → confirm executes once: card with token, same session yes, double confirm refused, cancel, expiry, nothing written before confirm")
    void stateMachine() {
        MockHttpServletRequest r = req("Admin", null);
        Map<String, Object> m = svc.handle(r, CID, "Add Sarah Lee to Welcome Team");
        assertThat(m.get("intent")).isEqualTo("action.confirm");
        Map<String, Object> c = (Map<String, Object>) m.get("confirm");
        assertThat(c).containsEntry("label", "Add to group").containsEntry("endpoint", "/api/ai/actions/confirm");
        String token = (String) c.get("token");
        assertThat(store).hasSize(1); assertThat(store.get(1L).getStatus()).isEqualTo("PROPOSED"); assertThat(store.get(1L).getSummary()).isEqualTo("Add Sarah Lee to the group Welcome Team.");
        verifyNoInteractions(groups);                                                   // nothing written yet
        assertThat(((Map<String, String>) c.get("summary"))).containsEntry("Member", "Sarah Lee").containsEntry("Group", "Welcome Team");

        Map<String, Object> done = svc.confirm(same(r), CID, token);
        assertThat(done.get("intent")).isEqualTo("action.done");
        assertThat(done.get("executed")).isEqualTo(true);
        verify(groups).addMember(10, "Sarah", "Lee", null, CID, 5);
        assertThat(store.get(1L).getStatus()).isEqualTo("EXECUTED"); assertThat(store.get(1L).getToken()).isNull();
        // the same token again → expired, no second write
        assertThat(svc.confirm(same(r), CID, token).get("intent")).isEqualTo("action.expired");
        verify(groups, times(1)).addMember(anyInt(), anyString(), anyString(), any(), anyString(), anyInt());

        // spoken / typed yes confirms the open proposal through the data route
        MockHttpServletRequest r2 = req("Admin", null);
        svc.handle(r2, CID, "Add Sarah Lee to Welcome Team");
        assertThat(svc.handle(same(r2), CID, "yes please").get("intent")).isEqualTo("action.done");
        // no → rejected
        MockHttpServletRequest r3 = req("Admin", null);
        svc.handle(r3, CID, "Add Sarah Lee to Welcome Team");
        assertThat(svc.handle(same(r3), CID, "no").get("intent")).isEqualTo("action.cancelled");
        assertThat(store.values().stream().filter(p -> "REJECTED".equals(p.getStatus()))).hasSize(1);
        // expired proposal
        MockHttpServletRequest r4 = req("Admin", null);
        Map<String, Object> m4 = svc.handle(r4, CID, "Add Sarah Lee to Welcome Team");
        String t4 = (String) ((Map<String, Object>) m4.get("confirm")).get("token");
        store.values().stream().filter(p -> t4.equals(p.getToken())).findFirst().get().setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThat(svc.confirm(same(r4), CID, t4).get("intent")).isEqualTo("action.expired");
        assertThat(store.values().stream().filter(p -> "EXPIRED".equals(p.getStatus()))).hasSize(1);
        // another church / another user cannot confirm
        MockHttpServletRequest r5 = req("Admin", null);
        String t5 = (String) ((Map<String, Object>) svc.handle(r5, CID, "Add Sarah Lee to Welcome Team").get("confirm")).get("token");
        assertThat(svc.confirm(same(r5), "CHR-B", t5).get("intent")).isEqualTo("action.expired");
        MockHttpServletRequest other = req("Admin", null); other.getSession().setAttribute("username", "sam");
        assertThat(svc.confirm(other, CID, t5).get("intent")).isEqualTo("action.expired");
        assertThat(store.values().stream().filter(p -> t5.equals(p.getToken()))).as("still open for its owner").hasSize(1);
        verify(groups, times(2)).addMember(anyInt(), anyString(), anyString(), any(), anyString(), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("confirm re-checks: permission revoked between propose and confirm → rejected; target vanished → refused; execute failure → FAILED, nothing claimed")
    void recheck() {
        MockHttpServletRequest r = req("Admin", null);
        String token = (String) ((Map<String, Object>) svc.handle(r, CID, "Add Sarah Lee to Welcome Team").get("confirm")).get("token");
        r.getSession().setAttribute("privileges", "{\"admin.groups.edit\":false}");           // permission pulled meanwhile
        Map<String, Object> d = svc.confirm(same(r), CID, token);
        assertThat(d.get("intent")).isEqualTo("action.denied"); assertThat(d.get("denied")).isEqualTo(true);
        assertThat(store.get(1L).getStatus()).isEqualTo("REJECTED");
        verifyNoInteractions(groups);

        MockHttpServletRequest r2 = req("Admin", null);
        String t2 = (String) ((Map<String, Object>) svc.handle(r2, CID, "Add Sarah Lee to Welcome Team").get("confirm")).get("token");
        when(lookup.memberById(CID, 5)).thenReturn(null);                                  // member deleted meanwhile
        assertThat(svc.confirm(same(r2), CID, t2).get("intent")).isEqualTo("action.refused");
        verifyNoInteractions(groups);

        when(lookup.memberById(CID, 5)).thenReturn(new Hit(5, "Sarah Lee", "1"));
        when(groups.addMember(anyInt(), anyString(), anyString(), any(), anyString(), anyInt())).thenThrow(new IllegalStateException("db down"));
        MockHttpServletRequest r3 = req("Admin", null);
        String t3 = (String) ((Map<String, Object>) svc.handle(r3, CID, "Add Sarah Lee to Welcome Team").get("confirm")).get("token");
        Map<String, Object> f = svc.confirm(same(r3), CID, t3);
        assertThat(f.get("intent")).isEqualTo("action.failed"); assertThat(String.valueOf(f.get("answer"))).contains("nothing was saved");
        assertThat(store.values().stream().filter(p -> "FAILED".equals(p.getStatus()))).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("clarifying questions: ambiguous member → choices, answer by number continues; missing group asked; unknown member; duplicate refused; wrong tool permission denied")
    void questions() {
        MockHttpServletRequest r = req("Admin", null);
        Map<String, Object> m = svc.handle(r, CID, "Add John to Welcome Team");
        assertThat(m.get("intent")).isEqualTo("action.ask");
        assertThat((List<String>) m.get("choices")).containsExactly("John Moe", "John Ray");
        when(lookup.members(eq(CID), ci("john moe"))).thenReturn(List.of(new Hit(6, "John Moe", "1")));
        Map<String, Object> next = svc.handle(same(r), CID, "1");
        assertThat(next.get("intent")).isEqualTo("action.confirm");
        assertThat(store.get(1L).getSummary()).isEqualTo("Add John Moe to the group Welcome Team.");

        MockHttpServletRequest r2 = req("Admin", null);
        when(lookup.groups(eq(CID), ci("nowhere"))).thenReturn(List.of());
        Map<String, Object> ask = svc.handle(r2, CID, "Add Sarah Lee to Nowhere");
        assertThat(ask.get("intent")).isEqualTo("action.ask"); assertThat(String.valueOf(ask.get("answer"))).contains("couldn't find a group");
        Map<String, Object> cont = svc.handle(same(r2), CID, "Choir");
        assertThat(cont.get("intent")).isEqualTo("action.confirm"); assertThat(String.valueOf(cont.get("answer"))).contains("Choir");
        // a different request while a question is open replaces the draft
        MockHttpServletRequest r3 = req("Admin", null);
        svc.handle(r3, CID, "Add Nobody Here to Welcome Team");
        assertThat(svc.handle(same(r3), CID, "Add Sarah Lee to Choir").get("intent")).isEqualTo("action.confirm");
        // an unrelated question while a question is open → null (the normal search answers), draft dropped
        MockHttpServletRequest r4 = req("Admin", null);
        svc.handle(r4, CID, "Add John to Welcome Team");
        assertThat(svc.handle(same(r4), CID, "how many members do we have")).isNull();
        // duplicate membership refused
        when(lookup.inGroup(CID, 10, 5)).thenReturn(true);
        Map<String, Object> dup = svc.handle(req("Admin", null), CID, "Add Sarah Lee to Welcome Team");
        assertThat(dup.get("intent")).isEqualTo("action.refused"); assertThat(String.valueOf(dup.get("answer"))).contains("already in");
        // permission: a User may add follow-ups but not group members; an Accountant neither
        Map<String, Object> den = svc.handle(req("User", null), CID, "Add Sarah Lee to Choir");
        assertThat(den.get("intent")).isEqualTo("action.denied");
        Map<String, Object> fu = svc.handle(req("User", null), CID, "Remind me to call Pat by Friday");
        assertThat(fu.get("intent")).isEqualTo("action.confirm");
        assertThat(svc.handle(req("Accountant", null), CID, "Remind me to call Pat").get("intent")).isEqualTo("action.denied");
        // Member / church / temp sessions never get an action
        MockHttpServletRequest member = req("Member", null); member.getSession().setAttribute("memberId", 5);
        assertThat(svc.handle(member, CID, "Add Sarah Lee to Choir")).isNull();
        MockHttpServletRequest temp = req("Admin", null); temp.getSession().setAttribute("tempAccessId", 3);
        assertThat(svc.handle(temp, CID, "Add Sarah Lee to Choir")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("each tool executes through its own service with tenant-checked ids; follow-up complete; family note; volunteer assignment")
    void tools() {
        MockHttpServletRequest r = req("Admin", null);
        // complete follow-up
        String t = (String) ((Map<String, Object>) svc.handle(r, CID, "Mark the follow-up for the Lees as done").get("confirm")).get("token");
        assertThat(svc.confirm(same(r), CID, t).get("intent")).isEqualTo("action.done");
        ArgumentCaptor<FollowUp> fc = ArgumentCaptor.forClass(FollowUp.class);
        verify(followUps).save(fc.capture());
        assertThat(fc.getValue().getStatus()).isEqualTo("COMPLETED"); assertThat(fc.getValue().getId()).isEqualTo(40L);
        // add follow-up linked to a member with a due date
        MockHttpServletRequest r2 = req("User", null);
        String t2 = (String) ((Map<String, Object>) svc.handle(r2, CID, "add a follow-up for Sarah Lee: call about baptism, due 2026-11-20").get("confirm")).get("token");
        svc.confirm(same(r2), CID, t2);
        verify(followUps, times(2)).save(fc.capture());
        FollowUp added = fc.getAllValues().get(fc.getAllValues().size() - 1);
        assertThat(added.getTitle()).isEqualTo("call about baptism"); assertThat(added.getClientId()).isEqualTo(CID); assertThat(added.getStatus()).isEqualTo("PENDING");
        assertThat(added.getLinkedType()).isEqualTo("MEMBER"); assertThat(added.getLinkedId()).isEqualTo(5L); assertThat(added.getLinkedLabel()).isEqualTo("Sarah Lee"); assertThat(added.getCreatedBy()).isEqualTo("pat");
        // family note → Notes
        when(notes.save(any())).thenAnswer(i -> { var n = (com.churchgeniuspro.hibernate.Note) i.getArgument(0); n.setId(3); return n; });
        MockHttpServletRequest r3 = req("Admin", null);
        String t3 = (String) ((Map<String, Object>) svc.handle(r3, CID, "Add a note to the Smiths: new baby arrived").get("confirm")).get("token");
        svc.confirm(same(r3), CID, t3);
        ArgumentCaptor<com.churchgeniuspro.hibernate.Note> nc = ArgumentCaptor.forClass(com.churchgeniuspro.hibernate.Note.class);
        verify(notes).save(nc.capture());
        assertThat(nc.getValue().getTitle()).isEqualTo("Family note — Sam Smith family"); assertThat(nc.getValue().getBody()).isEqualTo("new baby arrived"); assertThat(nc.getValue().getAppClientId()).isEqualTo(CID);
        // volunteer assignment (Admin only)
        when(assignments.save(any())).thenAnswer(i -> { var a = (com.churchgeniuspro.hibernate.VolunteerAssignment) i.getArgument(0); a.setId(8L); return a; });
        MockHttpServletRequest r4 = req("Admin", null);
        Map<String, Object> prop = svc.handle(r4, CID, "Assign John Moe as usher on 2026-11-08");
        assertThat(String.valueOf(prop.get("answer"))).startsWith("Assign John Moe as Usher on Sun, Nov 8, 2026 — status pending.");
        svc.confirm(same(r4), CID, (String) ((Map<String, Object>) prop.get("confirm")).get("token"));
        ArgumentCaptor<com.churchgeniuspro.hibernate.VolunteerAssignment> ac = ArgumentCaptor.forClass(com.churchgeniuspro.hibernate.VolunteerAssignment.class);
        verify(assignments).save(ac.capture());
        assertThat(ac.getValue().getFamilyMemberId()).isEqualTo(6); assertThat(ac.getValue().getRoleId()).isEqualTo(30L); assertThat(ac.getValue().getAssignmentStatus()).isEqualTo("pending"); assertThat(ac.getValue().getAppClientId()).isEqualTo(CID);
        assertThat(svc.handle(req("User", null), CID, "Assign John Moe as usher on 2026-11-08").get("intent")).isEqualTo("action.denied");
    }

    @Test @DisplayName("D6 decision D: a church whose plan lacks AI actions never has the question sent to OpenAI")
    void planCheckBeforeFallback() throws Exception {
        when(openAi.isEnabled()).thenReturn(true);
        AiAccessPolicy noActions = org.mockito.Mockito.spy(new AiAccessPolicy());
        org.mockito.Mockito.doReturn(false).when(noActions).feature(anyString(), eq(AiActionService.FEATURE));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        AiActionService svc2 = new AiActionService(registry, repo, noActions, openAi, new TransactionTemplate(tm), true, true, 10);
        Map<String, Object> m = svc2.handle(req("Admin", null), CID, "Include Sarah Lee with the Welcome Team");
        assertThat(m.get("intent")).isEqualTo("action.unavailable");
        verify(openAi, org.mockito.Mockito.never()).chatJson(anyString(), anyString(), anyDouble());
        // a typed phrase the parser understands still answers "not on your plan", also without the model
        assertThat(svc2.handle(req("Admin", null), CID, "Add Sarah Lee to Welcome Team").get("intent")).isEqualTo("action.unavailable");
        verify(openAi, org.mockito.Mockito.never()).chatJson(anyString(), anyString(), anyDouble());
    }

    @Test @DisplayName("LLM fallback picks only a registered tool from the closed list; kill switch stops proposals and confirms; recent list; audit + plan catalog")
    void fallbackAndSwitches() throws Exception {
        when(openAi.isEnabled()).thenReturn(true);
        when(openAi.chatJson(anyString(), eq("Include Sarah Lee with the Welcome Team"), anyDouble())).thenReturn(new ObjectMapper().readTree("{\"tool\":\"add_group_member\",\"params\":{\"member\":\"Sarah Lee\",\"group\":\"Welcome Team\"}}"));
        Map<String, Object> m = svc.handle(req("Admin", null), CID, "Include Sarah Lee with the Welcome Team");
        assertThat(m.get("intent")).isEqualTo("action.confirm"); assertThat(store.get(1L).getSource()).isEqualTo("LLM");
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(openAi).chatJson(sys.capture(), anyString(), eq(0.0));
        assertThat(sys.getValue()).contains("\"name\":\"add_group_member\"").contains("asks to send/e-mail/text anyone (there is no sending tool)");
        when(openAi.chatJson(anyString(), eq("Add something strange"), anyDouble())).thenReturn(new ObjectMapper().readTree("{\"tool\":\"send_email\",\"params\":{}}"));
        assertThat(svc.handle(req("Admin", null), CID, "Add something strange")).as("unknown tool → not an action").isNull();
        when(openAi.chatJson(anyString(), eq("Mark something"), anyDouble())).thenReturn(new ObjectMapper().readTree("{\"tool\":null}"));
        assertThat(svc.handle(req("Admin", null), CID, "Mark something")).isNull();
        // a plain question never reaches the model
        assertThat(svc.handle(req("Admin", null), CID, "Who is in Welcome Team?")).isNull();
        verify(openAi, times(3)).chatJson(anyString(), anyString(), anyDouble());

        // kill switch
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        AiActionService off = new AiActionService(registry, repo, new AiAccessPolicy(), openAi, new TransactionTemplate(tm), false, true, 10);
        assertThat(off.handle(req("Admin", null), CID, "Add Sarah Lee to Welcome Team")).isNull();
        assertThat(off.confirm(req("Admin", null), CID, store.get(1L).getToken()).get("intent")).isEqualTo("action.unavailable");
        assertThat(store.get(1L).getStatus()).isEqualTo("PROPOSED");

        // recent list
        when(repo.findTop50ByAppClientIdOrderByCreatedAtDesc(CID)).thenReturn(new ArrayList<>(store.values()));
        List<Map<String, Object>> rows = svc.recent(CID, "pat", true);
        assertThat(rows).hasSize(1); assertThat(rows.get(0)).containsEntry("label", "Add to group").containsEntry("status", "PROPOSED").containsEntry("source", "LLM");
        assertThat(rows.get(0)).doesNotContainKey("token");

        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai/actions/confirm")).isEqualTo("aiActions");
        assertThat(AiAuditService.channelOf("/api/ai/actions/confirm")).isEqualTo("ACTIONS");
        assertThat(registry.names()).containsExactlyInAnyOrder("add_follow_up", "complete_follow_up", "add_family_note", "add_group_member", "create_meeting", "assign_volunteer");
    }

    @Test @DisplayName("endpoints: 401 / 403 gates (member, church, temp, AI Assistant off); confirm + reject + list + tools shapes")
    void endpoints() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiActionController(svc, registry)).build();
        mvc.perform(post("/api/ai/actions/confirm").contentType("application/json").content("{\"token\":\"x\"}")).andExpect(status().isUnauthorized());
        MockHttpSession member = new MockHttpSession(); member.setAttribute("username", "m"); member.setAttribute("role", "Member"); member.setAttribute("appClientId", CID); member.setAttribute("memberId", 5);
        mvc.perform(post("/api/ai/actions/confirm").session(member).contentType("application/json").content("{\"token\":\"x\"}")).andExpect(status().isForbidden());
        MockHttpSession church = new MockHttpSession(); church.setAttribute("username", "c"); church.setAttribute("role", "SuperAdmin"); church.setAttribute("appClientId", CID); church.setAttribute("church", true);
        mvc.perform(get("/api/ai/actions").session(church)).andExpect(status().isForbidden());
        MockHttpSession temp = new MockHttpSession(); temp.setAttribute("username", "t"); temp.setAttribute("role", "User"); temp.setAttribute("appClientId", CID); temp.setAttribute("tempAccessId", 1);
        mvc.perform(get("/api/ai/actions").session(temp)).andExpect(status().isForbidden());
        MockHttpSession noAi = new MockHttpSession(); noAi.setAttribute("username", "pat"); noAi.setAttribute("role", "Admin"); noAi.setAttribute("appClientId", CID); noAi.setAttribute("privileges", "{\"more.aiassistant\":false}");
        mvc.perform(get("/api/ai/actions/tools").session(noAi)).andExpect(status().isForbidden());

        MockHttpSession admin = new MockHttpSession(); admin.setAttribute("username", "pat"); admin.setAttribute("role", "Admin"); admin.setAttribute("appClientId", CID);
        mvc.perform(post("/api/ai/actions/confirm").session(admin).contentType("application/json").content("{\"token\":\"nope\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("action.expired"));
        mvc.perform(post("/api/ai/actions/reject").session(admin).contentType("application/json").content("{\"token\":\"nope\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("action.cancelled"));
        when(repo.findTop50ByAppClientIdOrderByCreatedAtDesc(CID)).thenReturn(List.of());
        mvc.perform(get("/api/ai/actions").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.all").value(true)).andExpect(jsonPath("$.enabled").value(true));
        MockHttpSession user = new MockHttpSession(); user.setAttribute("username", "u"); user.setAttribute("role", "User"); user.setAttribute("appClientId", CID);
        when(repo.findTop50ByAppClientIdAndUsernameOrderByCreatedAtDesc(CID, "u")).thenReturn(List.of());
        mvc.perform(get("/api/ai/actions").session(user)).andExpect(status().isOk()).andExpect(jsonPath("$.all").value(false));
        mvc.perform(get("/api/ai/actions/tools").session(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.tools.length()").value(6));
    }
}

package com.churchgeniuspro.mobile;

import com.churchgeniuspro.hibernate.ContentReport;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MemberBlock;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.repository.ContentReportRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MemberBlockRepository;
import com.churchgeniuspro.repository.MemberMessageRepository;
import com.churchgeniuspro.service.MemberSafetyService;
import com.churchgeniuspro.service.PublicSubmissionNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Mobile Phase 2, M5: report & block semantics over in-memory repositories. */
class MemberSafetyServiceTest {

    static final String CHURCH = "CHR-1", OTHER = "CHR-2";

    final Map<Long, MemberBlock>    blocks   = new HashMap<>();
    final Map<Long, ContentReport>  reports  = new HashMap<>();
    final Map<Long, MemberMessage>  messages = new HashMap<>();
    final Map<Integer, FamilyMember> members = new HashMap<>();
    final AtomicLong ids = new AtomicLong();

    MemberBlockRepository blockRepo; ContentReportRepository reportRepo; MemberMessageRepository msgRepo;
    FamilyMemberRepository memberRepo; PublicSubmissionNotificationService notifications;
    MemberSafetyService svc;

    @BeforeEach
    void setUp() {
        blockRepo = mock(MemberBlockRepository.class);
        when(blockRepo.save(any())).thenAnswer(i -> { MemberBlock b = i.getArgument(0); if (b.getId() == null) b.setId(ids.incrementAndGet()); blocks.put(b.getId(), b); return b; });
        doAnswer(i -> { blocks.values().removeIf(b -> b == i.getArgument(0)); return null; }).when(blockRepo).delete(any());
        when(blockRepo.existsByAppClientIdAndBlockerMemberIdAndBlockedMemberId(anyString(), anyInt(), anyInt())).thenAnswer(i ->
                blocks.values().stream().anyMatch(b -> b.getAppClientId().equals(i.getArgument(0)) && b.getBlockerMemberId().equals(i.getArgument(1)) && b.getBlockedMemberId().equals(i.getArgument(2))));
        when(blockRepo.findByAppClientIdAndBlockerMemberIdAndBlockedMemberId(anyString(), anyInt(), anyInt())).thenAnswer(i ->
                blocks.values().stream().filter(b -> b.getAppClientId().equals(i.getArgument(0)) && b.getBlockerMemberId().equals(i.getArgument(1)) && b.getBlockedMemberId().equals(i.getArgument(2))).findFirst());
        when(blockRepo.blockedIds(anyString(), anyInt())).thenAnswer(i ->
                blocks.values().stream().filter(b -> b.getAppClientId().equals(i.getArgument(0)) && b.getBlockerMemberId().equals(i.getArgument(1))).map(MemberBlock::getBlockedMemberId).toList());
        when(blockRepo.findByAppClientIdAndBlockerMemberIdOrderByCreatedAtDesc(anyString(), anyInt())).thenAnswer(i ->
                blocks.values().stream().filter(b -> b.getAppClientId().equals(i.getArgument(0)) && b.getBlockerMemberId().equals(i.getArgument(1))).toList());

        reportRepo = mock(ContentReportRepository.class);
        when(reportRepo.save(any())).thenAnswer(i -> { ContentReport r = i.getArgument(0); if (r.getId() == null) r.setId(ids.incrementAndGet()); reports.put(r.getId(), r); return r; });
        when(reportRepo.existsByAppClientIdAndReporterMemberIdAndContentTypeAndContentIdAndStatus(anyString(), anyInt(), anyString(), anyLong(), anyString())).thenAnswer(i ->
                reports.values().stream().anyMatch(r -> r.getAppClientId().equals(i.getArgument(0)) && r.getReporterMemberId().equals(i.getArgument(1)) && r.getContentId().equals(i.getArgument(3)) && r.getStatus().equals(i.getArgument(4))));
        when(reportRepo.findByIdAndAppClientId(anyLong(), anyString())).thenAnswer(i ->
                Optional.ofNullable(reports.get((Long) i.getArgument(0))).filter(r -> r.getAppClientId().equals(i.getArgument(1))));
        when(reportRepo.findByAppClientIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> reports.values().stream().filter(r -> r.getAppClientId().equals(i.getArgument(0))).toList());
        when(reportRepo.findByAppClientIdAndStatusOrderByCreatedAtDesc(anyString(), anyString())).thenAnswer(i -> reports.values().stream().filter(r -> r.getAppClientId().equals(i.getArgument(0)) && r.getStatus().equals(i.getArgument(1))).toList());
        when(reportRepo.countByAppClientIdAndStatus(anyString(), anyString())).thenAnswer(i -> reports.values().stream().filter(r -> r.getAppClientId().equals(i.getArgument(0)) && r.getStatus().equals(i.getArgument(1))).count());

        msgRepo = mock(MemberMessageRepository.class);
        when(msgRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(messages.get((Long) i.getArgument(0))));
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        memberRepo = mock(FamilyMemberRepository.class);
        when(memberRepo.findByIdAndTenant(anyInt(), anyString())).thenAnswer(i ->
                Optional.ofNullable(members.get((Integer) i.getArgument(0))).filter(m -> i.getArgument(1).equals(m.getAppClientId())));

        notifications = mock(PublicSubmissionNotificationService.class);
        svc = new MemberSafetyService(blockRepo, reportRepo, msgRepo, memberRepo, notifications);

        member(1, CHURCH, "Ann", "Able"); member(2, CHURCH, "Bob", "Baker"); member(3, OTHER, "Cy", "Cross");
    }

    void member(int id, String church, String first, String last) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setAppClientId(church); m.setFirstName(first); m.setLastName(last);
        members.put(id, m);
    }
    MemberMessage msg(long id, String church, int from, int to, String body) {
        MemberMessage m = new MemberMessage(); m.setId(id); m.setAppClientId(church); m.setSenderMemberId(from); m.setRecipientMemberId(to); m.setBody(body); m.setSenderName("Sender " + from);
        messages.put(id, m); return m;
    }

    @Test
    @DisplayName("block: recipient's block refuses the sender, is idempotent, and is invisible the other way round")
    void blockSemantics() {
        assertThat(svc.block(CHURCH, 1, 2)).isPresent();
        assertThat(svc.block(CHURCH, 1, 2)).isPresent();
        assertThat(blocks).hasSize(1);
        assertThat(svc.isBlocked(CHURCH, 2, 1)).as("Bob → Ann refused").isTrue();
        assertThat(svc.isBlocked(CHURCH, 1, 2)).as("Ann may still message Bob").isFalse();
        assertThat(svc.blockedBy(CHURCH, 1)).containsExactly(2);
        assertThat(svc.listBlocks(CHURCH, 1)).hasSize(1).first().satisfies(b -> assertThat(b.get("name")).isEqualTo("Bob Baker"));
        assertThat(svc.unblock(CHURCH, 1, 2)).isTrue();
        assertThat(svc.unblock(CHURCH, 1, 2)).isFalse();
        assertThat(svc.isBlocked(CHURCH, 2, 1)).isFalse();
    }

    @Test
    @DisplayName("block: cannot block yourself or a member of another church")
    void blockGuards() {
        assertThat(svc.block(CHURCH, 1, 1)).isEmpty();
        assertThat(svc.block(CHURCH, 1, 3)).as("other church's member").isEmpty();
        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("report: creates one open report per message per reporter and raises the staff bell")
    void reportCreates() {
        msg(10, CHURCH, 2, 1, "rude words");
        MemberSafetyService.ReportOutcome out = svc.reportMessage(CHURCH, 1, 10L, "harassment", "please look");
        assertThat(out.ok()).isTrue();
        ContentReport r = reports.get(out.reportId());
        assertThat(r.getStatus()).isEqualTo("OPEN");
        assertThat(r.getReportedMemberId()).isEqualTo(2);
        assertThat(r.getReportedName()).isEqualTo("Bob Baker");
        assertThat(r.getReporterName()).isEqualTo("Ann Able");
        assertThat(r.getContentExcerpt()).isEqualTo("rude words");
        assertThat(r.getReason()).isEqualTo("HARASSMENT");
        verify(notifications).record(eq(CHURCH), eq(PublicSubmissionNotificationService.Type.CONTENT_REPORT), anyString(), anyString(), eq(r.getId()));

        MemberSafetyService.ReportOutcome again = svc.reportMessage(CHURCH, 1, 10L, "SPAM", null);
        assertThat(again.ok()).isTrue();
        assertThat(reports).as("no duplicate while the first is open").hasSize(1);
    }

    @Test
    @DisplayName("report: refused for non-participants, own messages, other churches and bad reasons")
    void reportGuards() {
        msg(10, CHURCH, 2, 1, "x");
        msg(11, OTHER,  3, 3, "y");
        assertThat(svc.reportMessage(CHURCH, 1, 10L, "nonsense", null).status()).isEqualTo(400);
        assertThat(svc.reportMessage(CHURCH, 2, 10L, "SPAM", null).status()).as("own message").isEqualTo(400);
        assertThat(svc.reportMessage(CHURCH, 1, 11L, "SPAM", null).status()).as("other church").isEqualTo(404);
        assertThat(svc.reportMessage(CHURCH, 1, 999L, "SPAM", null).status()).isEqualTo(404);
        assertThat(reports).isEmpty();
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("staff: remove message soft-deletes it for both parties and closes the report; tenant-scoped")
    void staffResolve() {
        MemberMessage m = msg(10, CHURCH, 2, 1, "rude");
        long id = svc.reportMessage(CHURCH, 1, 10L, "INAPPROPRIATE", null).reportId();

        assertThat(svc.resolve(OTHER, id, "DELETE_MESSAGE", null, "staff@other")).as("other church").isEmpty();
        assertThat(m.isDeletedBySender()).isFalse();

        Map<String, Object> r = svc.resolve(CHURCH, id, "DELETE_MESSAGE", null, "pastor").orElseThrow();
        assertThat(m.isDeletedBySender()).isTrue();
        assertThat(m.isDeletedByRecipient()).isTrue();
        assertThat(r.get("status")).isEqualTo("REVIEWED");
        assertThat(r.get("actionNote")).isEqualTo("Message removed");
        assertThat(r.get("reviewedBy")).isEqualTo("pastor");
        assertThat(r.get("messageDeleted")).isEqualTo(true);

        assertThat(svc.resolve(CHURCH, id, "REOPEN", null, "pastor").orElseThrow().get("status")).isEqualTo("OPEN");
        assertThat(svc.resolve(CHURCH, id, "DISMISS", "not a problem", "pastor").orElseThrow().get("status")).isEqualTo("DISMISSED");
        assertThat(svc.resolve(CHURCH, id, "EXPLODE", null, "pastor")).isEmpty();
        assertThat(svc.reportCounts(CHURCH)).containsEntry("DISMISSED", 1L).containsEntry("OPEN", 0L);
        assertThat(svc.listReports(OTHER, "All")).isEmpty();
    }
}

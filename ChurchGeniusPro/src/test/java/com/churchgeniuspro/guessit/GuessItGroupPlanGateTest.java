package com.churchgeniuspro.guessit;

import com.churchgeniuspro.controller.GuessItGroupController;
import com.churchgeniuspro.hibernate.GuessItGroup;
import com.churchgeniuspro.hibernate.GuessItGroupParticipant;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.GuessItGameRepository;
import com.churchgeniuspro.repository.GuessItGroupParticipantRepository;
import com.churchgeniuspro.repository.GuessItGroupRepository;
import com.churchgeniuspro.repository.GuessItParticipantRepository;
import com.churchgeniuspro.service.GuessItPlayService;
import com.churchgeniuspro.service.SubscriptionService;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * H2: a group stops being playable when its church loses Activity Corner.
 *
 * <p>{@code /api/guess-it/group/**} is excluded from {@code SubscriptionFeatureFilter}
 * because participants reach it without a session, and the catalog justified that by
 * arguing a group can only exist if an admin created it through the gated admin API.
 * That holds at creation and not afterwards. A church moved onto the Trial plan, or a
 * demo tenant whose groups were seeded before the evaluation overlay existed, kept
 * live, anonymously joinable boards indefinitely — the very thing
 * {@code PublicPagePolicy} refuses those tenants a public link for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Guess It group play — plan gate (H2)")
class GuessItGroupPlanGateTest {

    private static final String CODE   = "A1768G";
    private static final String TENANT = "CHR-church-01";

    @Mock GuessItGroupRepository            groupRepo;
    @Mock GuessItGroupParticipantRepository groupParticipantRepo;
    @Mock GuessItGameRepository             gameRepo;
    @Mock GuessItParticipantRepository      participantRepo;
    @Mock FamilyMemberRepository            familyMemberRepo;
    @Mock SubscriptionService               subs;

    private GuessItGroupController controller;
    private GuessItGroup group;

    @BeforeEach
    void setUp() {
        controller = new GuessItGroupController(groupRepo, groupParticipantRepo, gameRepo,
                participantRepo, familyMemberRepo,
                new GuessItPlayService(participantRepo, groupParticipantRepo),
                new com.churchgeniuspro.util.PublicSendLimiter());
        controller.setSubscriptionService(subs);

        group = new GuessItGroup();
        group.setId(7L);
        group.setClientId(TENANT);
        group.setName("Sunday Quiz");
        group.setCode(CODE);
        group.setStatus("active");
        when(groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse(CODE, "active"))
                .thenReturn(Optional.of(group));
        when(groupRepo.findFirstByCodeAndDeleteFlagFalseOrderByCreatedAtDesc(CODE))
                .thenReturn(Optional.of(group));
        when(groupRepo.findById(7L)).thenReturn(Optional.of(group));

        GuessItGroupParticipant p = new GuessItGroupParticipant();
        p.setId(1L);
        p.setGroupId(7L);
        p.setToken("tok-1");
        p.setDisplayName("Sam");
        when(groupParticipantRepo.findByToken("tok-1")).thenReturn(Optional.of(p));
        when(groupParticipantRepo.findByGroupId(7L)).thenReturn(List.of(p));
        when(groupParticipantRepo.findByGroupIdOrderByScoreDescDisplayNameAsc(7L)).thenReturn(List.of(p));
        when(gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(7L, "active")).thenReturn(Optional.empty());
        when(gameRepo.findPublishedByGroupIdOrderBySequenceOrder(7L)).thenReturn(List.of());
    }

    private void activityCorner(boolean enabled) {
        when(subs.isFeatureEnabled(TENANT, "activityCorner")).thenReturn(enabled);
    }

    private static MockHttpServletRequest anonymous() {
        return new MockHttpServletRequest("POST", "/api/guess-it/group/verify");
    }

    private static String bodyOf(ResponseEntity<?> res) {
        return String.valueOf(res.getBody());
    }

    /* ── the four public entry points ───────────────────────────────────── */

    @Test
    @DisplayName("a code cannot be verified once the church loses the feature")
    void verifyRefused() {
        activityCorner(false);
        ResponseEntity<?> res = controller.verifyCode(Map.of("code", CODE), anonymous());
        assertThat(bodyOf(res)).contains("no longer available");
        assertThat(bodyOf(res)).doesNotContain("Sunday Quiz");
    }

    @Test
    @DisplayName("nobody new can join")
    void joinRefused() {
        activityCorner(false);
        ResponseEntity<?> res = controller.join(Map.of("code", CODE, "name", "Sam"), anonymous());
        assertThat(bodyOf(res)).contains("no longer available");
    }

    @Test
    @DisplayName("a participant already holding a token cannot read the board")
    void stateRefusedForHeldToken() {
        activityCorner(false);
        ResponseEntity<?> res = controller.groupState("tok-1", null, anonymous());
        assertThat(bodyOf(res)).contains("no longer available");
    }

    @Test
    @DisplayName("...and cannot submit an answer")
    void actionRefusedForHeldToken() {
        activityCorner(false);
        ResponseEntity<?> res = controller.groupAction(
                Map.of("token", "tok-1", "gameId", 1, "option", "A"), anonymous());
        assertThat(bodyOf(res)).contains("no longer available");
    }

    /* ── and what must not change ───────────────────────────────────────── */

    @Test
    @DisplayName("a church that has the feature plays normally")
    void enabledChurchPlaysOn() {
        activityCorner(true);
        assertThat(bodyOf(controller.verifyCode(Map.of("code", CODE), anonymous())))
                .contains("Sunday Quiz");
        assertThat(controller.groupState("tok-1", null, anonymous()).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a plan lookup that fails does not end a game in progress")
    void lookupFailureFailsOpen() {
        when(subs.isFeatureEnabled(anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(bodyOf(controller.verifyCode(Map.of("code", CODE), anonymous())))
                .contains("Sunday Quiz");
    }

    @Test
    @DisplayName("without a plan service at all, play is unaffected")
    void noPlanServiceIsUnaffected() {
        controller.setSubscriptionService(null);
        assertThat(bodyOf(controller.verifyCode(Map.of("code", CODE), anonymous())))
                .contains("Sunday Quiz");
    }

    @Test
    @DisplayName("an unrecognised code still reads as unrecognised, not as a plan problem")
    void unknownCodeUnchanged() {
        activityCorner(false);
        ResponseEntity<?> res = controller.verifyCode(Map.of("code", "ZZZZZZ"), anonymous());
        assertThat(bodyOf(res)).contains("not recognised");
    }
}

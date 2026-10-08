package com.churchgeniuspro.guessit;

import com.churchgeniuspro.controller.GuessItGroupController;
import com.churchgeniuspro.hibernate.GuessItGame;
import com.churchgeniuspro.hibernate.GuessItGroup;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.GuessItGameRepository;
import com.churchgeniuspro.repository.GuessItGroupParticipantRepository;
import com.churchgeniuspro.repository.GuessItGroupRepository;
import com.churchgeniuspro.repository.GuessItParticipantRepository;
import com.churchgeniuspro.service.GuessItPlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The per-clue duration of a Guess It group, changed after the group exists.
 *
 * <p>The behaviour under test is deliberately narrow, because it is the part a
 * host reaches for mid-session and the part that is easy to get subtly wrong:
 *
 * <ul>
 *   <li>three minutes is the ceiling, and it is enforced on the server rather
 *       than only in the dropdown — anything can POST to this endpoint;</li>
 *   <li>the value is stored on the <em>group</em>, so rounds added afterwards
 *       inherit it instead of quietly running on a different clock;</li>
 *   <li>it re-applies to the rounds that have not finished, including the one
 *       currently on screen, but never to a completed round, whose stored
 *       duration is the record of how it was actually played;</li>
 *   <li>and it is tenant-scoped, like every other admin endpoint here.</li>
 * </ul>
 */
class GuessItGroupDurationTest {

    private static final String CLIENT = "CLIENT-1";

    private GuessItGroupRepository            groupRepo;
    private GuessItGroupParticipantRepository groupParticipantRepo;
    private GuessItGameRepository             gameRepo;
    private GuessItParticipantRepository      participantRepo;
    private FamilyMemberRepository            familyMemberRepo;
    private MockMvc                           mvc;

    private GuessItGroup group;
    private static final java.util.concurrent.atomic.AtomicLong GAME_IDS =
            new java.util.concurrent.atomic.AtomicLong(900);

    @BeforeEach
    void setUp() {
        groupRepo            = mock(GuessItGroupRepository.class);
        groupParticipantRepo = mock(GuessItGroupParticipantRepository.class);
        gameRepo             = mock(GuessItGameRepository.class);
        participantRepo      = mock(GuessItParticipantRepository.class);
        familyMemberRepo     = mock(FamilyMemberRepository.class);

        group = new GuessItGroup();
        group.setId(7L);
        group.setClientId(CLIENT);
        group.setCode("A1768G");
        group.setStatus("active");
        group.setTimerSecs(60);

        when(groupRepo.findByIdAndClientIdAndDeleteFlagFalse(7L, CLIENT)).thenReturn(Optional.of(group));
        when(groupRepo.save(any(GuessItGroup.class))).thenAnswer(i -> i.getArgument(0));
        // Stand in for the sequence: a saved game comes back with an id, which is
        // what the create endpoint echoes to the page.
        when(gameRepo.save(any(GuessItGame.class))).thenAnswer(i -> {
            GuessItGame g = i.getArgument(0);
            if (g.getId() == null) g.setId(GAME_IDS.incrementAndGet());
            return g;
        });
        when(gameRepo.findAllByGroupIdOrderBySequenceOrder(anyLong())).thenReturn(List.of());

        GuessItGroupController controller = new GuessItGroupController(
                groupRepo, groupParticipantRepo, gameRepo, participantRepo, familyMemberRepo,
                new GuessItPlayService(participantRepo, groupParticipantRepo),
                new com.churchgeniuspro.util.PublicSendLimiter());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** An admin session for the org that owns the group. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return request -> {
            request.getSession().setAttribute("username", "pastor");
            request.getSession().setAttribute("appClientId", CLIENT);
            return request;
        };
    }

    private GuessItGame game(long id, String status, int secs) {
        GuessItGame g = new GuessItGame();
        g.setId(id);
        g.setClientId(CLIENT);
        g.setGroupId(7L);
        g.setStatus(status);
        g.setTimerSecs(secs);
        g.setClues("[\"a\"]");
        g.setOptions("[\"x\",\"y\"]");
        g.setCorrectAnswer("x");
        return g;
    }

    // ── the ceiling ────────────────────────────────────────────────────────

    @Test
    @DisplayName("three minutes is accepted as-is")
    void threeMinutesAccepted() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":180}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(180))
           .andExpect(jsonPath("$.clamped").value(false));
        assertEquals(180, group.getTimerSecs());
    }

    @Test
    @DisplayName("anything above three minutes is clamped, not rejected — a stale page still works")
    void aboveCeilingIsClamped() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":600}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(GuessItGroupController.MAX_TIMER_SECS))
           .andExpect(jsonPath("$.clamped").value(true));
        assertEquals(180, group.getTimerSecs(), "the stored value must never exceed the ceiling");
    }

    @Test
    @DisplayName("zero is a real choice — it means no timer, not 'unset'")
    void zeroDisablesTheTimer() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":0}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(0));
        assertEquals(0, group.getTimerSecs());
    }

    @Test
    @DisplayName("a negative or missing duration is refused rather than coerced")
    void badInputRefused() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":-5}"))
           .andExpect(status().isBadRequest());
        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{}"))
           .andExpect(status().isBadRequest());
        assertEquals(60, group.getTimerSecs(), "a refused request must not change anything");
    }

    // ── what it applies to ────────────────────────────────────────────────

    @Test
    @DisplayName("applies to pending and active rounds, and leaves completed ones alone")
    void appliesToUnfinishedRoundsOnly() throws Exception {
        GuessItGame pending   = game(1L, "pending",   60);
        GuessItGame live      = game(2L, "active",    60);
        GuessItGame finished  = game(3L, "completed", 30);
        when(gameRepo.findAllByGroupIdOrderBySequenceOrder(7L))
                .thenReturn(new ArrayList<>(List.of(pending, live, finished)));

        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":150}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.gamesUpdated").value(2));

        assertEquals(150, pending.getTimerSecs(), "a round not yet started takes the new duration");
        assertEquals(150, live.getTimerSecs(),    "the live round takes it too (from its next clue)");
        assertEquals(30,  finished.getTimerSecs(),
                "a completed round keeps the duration it was actually played with");
    }

    @Test
    @DisplayName("rounds already on the new duration are not re-saved")
    void noPointlessWrites() throws Exception {
        GuessItGame already = game(1L, "pending", 90);
        when(gameRepo.findAllByGroupIdOrderBySequenceOrder(7L))
                .thenReturn(new ArrayList<>(List.of(already)));

        mvc.perform(put("/api/guess-it/admin/groups/7/timer").with(admin())
                        .contentType("application/json").content("{\"timerSecs\":90}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.gamesUpdated").value(0));

        verify(gameRepo, never()).save(any(GuessItGame.class));
    }

    // ── inheritance ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a round created after the change inherits the group's duration")
    void newRoundInheritsGroupDuration() throws Exception {
        group.setTimerSecs(180);
        when(gameRepo.findMaxSequenceOrderByGroupId(7L)).thenReturn(2);

        mvc.perform(post("/api/guess-it/admin/groups/7/games").with(admin())
                        .contentType("application/json")
                        .content("{\"title\":\"Round 3\",\"clues\":[\"one\"],"
                               + "\"options\":[\"Ruth\",\"Esther\"],\"correctAnswer\":\"Ruth\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(180));
    }

    @Test
    @DisplayName("an explicit duration on a new round is still honoured, but still capped")
    void explicitDurationHonouredAndCapped() throws Exception {
        group.setTimerSecs(60);
        when(gameRepo.findMaxSequenceOrderByGroupId(7L)).thenReturn(null);

        mvc.perform(post("/api/guess-it/admin/groups/7/games").with(admin())
                        .contentType("application/json")
                        .content("{\"title\":\"Round 1\",\"clues\":[\"one\"],"
                               + "\"options\":[\"Ruth\",\"Esther\"],\"correctAnswer\":\"Ruth\","
                               + "\"timerSecs\":900}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(180));
    }

    // ── tenancy and auth ──────────────────────────────────────────────────

    @Test
    @DisplayName("another org's group is not found, let alone changed")
    void otherOrgCannotChangeIt() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer")
                        .with(request -> {
                            request.getSession().setAttribute("username", "someone");
                            request.getSession().setAttribute("appClientId", "CLIENT-2");
                            return request;
                        })
                        .contentType("application/json").content("{\"timerSecs\":30}"))
           .andExpect(status().isNotFound());
        assertEquals(60, group.getTimerSecs());
    }

    @Test
    @DisplayName("no session at all is rejected")
    void anonymousRejected() throws Exception {
        mvc.perform(put("/api/guess-it/admin/groups/7/timer")
                        .contentType("application/json").content("{\"timerSecs\":30}"))
           .andExpect(status().isUnauthorized());
    }

    // ── the summary the page reads ────────────────────────────────────────

    @Test
    @DisplayName("the group's duration is reported back so the dropdown can show what is in force")
    void durationIsVisibleToTheAdminPage() throws Exception {
        group.setTimerSecs(150);
        when(gameRepo.findAllByGroupIdOrderBySequenceOrder(7L)).thenReturn(List.of());
        when(groupParticipantRepo.findByGroupIdOrderByScoreDescDisplayNameAsc(7L)).thenReturn(List.of());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/guess-it/admin/groups/7").with(admin()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.timerSecs").value(150));
    }
}

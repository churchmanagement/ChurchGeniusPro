package com.churchgeniuspro.guessit;

import com.churchgeniuspro.controller.GuessItGroupController;
import com.churchgeniuspro.hibernate.GuessItGame;
import com.churchgeniuspro.hibernate.GuessItGroup;
import com.churchgeniuspro.hibernate.GuessItGroupParticipant;
import com.churchgeniuspro.hibernate.GuessItParticipant;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.GuessItGameRepository;
import com.churchgeniuspro.repository.GuessItGroupParticipantRepository;
import com.churchgeniuspro.repository.GuessItGroupRepository;
import com.churchgeniuspro.repository.GuessItParticipantRepository;
import com.churchgeniuspro.service.GuessItPlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * One group code, many devices.
 *
 * <p>The thing that has to hold here is independence: a phone, a tablet and two
 * laptops all entering the same six characters must each end up with their own
 * participant, their own name and their own score, and nothing one of them does
 * — joining, refreshing, losing signal, answering twice — may disturb another.
 *
 * <p>The mechanism is that a public participant is identified by an opaque token
 * held in that browser, never by the HTTP session. These tests pin that: the
 * requests below deliberately carry no session at all, which is exactly the
 * situation of a visitor who has not logged in.
 */
class GuessItMultiDeviceTest {

    private static final String CLIENT = "CLIENT-1";
    private static final String CODE   = "A1768G";

    private GuessItGroupRepository            groupRepo;
    private GuessItGroupParticipantRepository groupParticipantRepo;
    private GuessItGameRepository             gameRepo;
    private GuessItParticipantRepository      participantRepo;
    private FamilyMemberRepository            familyMemberRepo;
    private MockMvc                           mvc;

    private GuessItGroup group;

    /** A stand-in for the guess_it_group_participant table. */
    private final Map<String, GuessItGroupParticipant> byToken   = new HashMap<>();
    private final Map<String, GuessItGroupParticipant> byNameKey = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

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
        group.setCode(CODE);
        group.setStatus("active");
        group.setTimerSecs(60);

        when(groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse(CODE, "active"))
                .thenReturn(Optional.of(group));
        when(groupRepo.findFirstByCodeAndDeleteFlagFalseOrderByCreatedAtDesc(CODE))
                .thenReturn(Optional.of(group));
        when(groupRepo.findById(7L)).thenReturn(Optional.of(group));

        when(groupParticipantRepo.findByToken(anyString()))
                .thenAnswer(i -> Optional.ofNullable(byToken.get(i.<String>getArgument(0))));
        when(groupParticipantRepo.findByGroupIdAndNameKey(anyLong(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(byNameKey.get(i.<String>getArgument(1))));
        when(groupParticipantRepo.save(any(GuessItGroupParticipant.class))).thenAnswer(i -> {
            GuessItGroupParticipant p = i.getArgument(0);
            if (byNameKey.containsKey(p.getNameKey())
                    && !byNameKey.get(p.getNameKey()).getToken().equals(p.getToken()))
                throw new DataIntegrityViolationException("uq_guess_it_group_participant_name");
            if (p.getId() == null) p.setId(ids.incrementAndGet());
            byToken.put(p.getToken(), p);
            byNameKey.put(p.getNameKey(), p);
            return p;
        });
        when(groupParticipantRepo.findByGroupId(7L))
                .thenAnswer(i -> new ArrayList<>(byToken.values()));
        when(groupParticipantRepo.findByGroupIdOrderByScoreDescDisplayNameAsc(7L))
                .thenAnswer(i -> new ArrayList<>(byToken.values()));

        when(gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(7L, "active"))
                .thenReturn(Optional.empty());
        when(gameRepo.findPublishedByGroupIdOrderBySequenceOrder(7L)).thenReturn(List.of());

        GuessItGroupController controller = new GuessItGroupController(
                groupRepo, groupParticipantRepo, gameRepo, participantRepo, familyMemberRepo,
                new GuessItPlayService(participantRepo, groupParticipantRepo),
                new com.churchgeniuspro.util.PublicSendLimiter());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Join as a public participant with no session — the ordinary visitor path. */
    private String join(String name) throws Exception {
        String json = mvc.perform(post("/api/guess-it/group/join")
                        .contentType("application/json")
                        .content("{\"code\":\"" + CODE + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int i = json.indexOf("\"token\":\"");
        assertTrue(i >= 0, "join must hand back a token: " + json);
        return json.substring(i + 9, json.indexOf('"', i + 9));
    }

    // ── independence ──────────────────────────────────────────────────────

    @Test
    @DisplayName("four devices on one code get four independent participants")
    void manyDevicesOneCode() throws Exception {
        String phone   = join("Ada");
        String tablet  = join("Ben");
        String laptop  = join("Cara");
        String desktop = join("Dev");

        assertEquals(4, byToken.size(), "each device must have its own participant row");
        assertEquals(4, java.util.Set.of(phone, tablet, laptop, desktop).size(),
                "tokens must be distinct — a shared token is a shared identity");

        // Each token resolves to its own name, with no session anywhere in play.
        assertNameForToken(phone,   "Ada");
        assertNameForToken(tablet,  "Ben");
        assertNameForToken(laptop,  "Cara");
        assertNameForToken(desktop, "Dev");
    }

    private void assertNameForToken(String token, String expected) throws Exception {
        mvc.perform(get("/api/guess-it/group/state").param("token", token))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.me.name").value(expected));
    }

    @Test
    @DisplayName("a later device joining does not disturb an earlier one's session")
    void laterJoinDoesNotDisturbEarlier() throws Exception {
        String first = join("Ada");
        assertNameForToken(first, "Ada");

        join("Ben");
        join("Cara");

        // The first device is still exactly who it was.
        assertNameForToken(first, "Ada");
    }

    // ── refresh, retry, reopen ────────────────────────────────────────────

    @Test
    @DisplayName("re-joining with the token this device already holds resumes it, name and all")
    void rejoinWithHeldTokenResumes() throws Exception {
        String token = join("Ada");

        // A refresh, a reopened tab, or a retry after a dropped response: the
        // same device sends the same name AND the token it was given. Without
        // the token this would collide with the name it registered itself.
        String json = mvc.perform(post("/api/guess-it/group/join")
                        .contentType("application/json")
                        .content("{\"code\":\"" + CODE + "\",\"name\":\"Ada\",\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(json.contains(token), "the same token must come back, not a second identity");
        assertEquals(1, byToken.size(), "resuming must not create a second participant");
    }

    @Test
    @DisplayName("verify with a held token reports the device as already joined")
    void verifyRecognisesAHeldToken() throws Exception {
        String token = join("Ada");

        mvc.perform(post("/api/guess-it/group/verify")
                        .contentType("application/json")
                        .content("{\"code\":\"" + CODE + "\",\"token\":\"" + token + "\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.alreadyJoined").value(true))
           .andExpect(jsonPath("$.needsName").value(false))
           .andExpect(jsonPath("$.name").value("Ada"));
    }

    @Test
    @DisplayName("a second person cannot take a name already in the game")
    void namesStayUnique() throws Exception {
        join("Ada");

        mvc.perform(post("/api/guess-it/group/join")
                        .contentType("application/json")
                        .content("{\"code\":\"" + CODE + "\",\"name\":\" ada \"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.errorKey").value("nameTaken"));

        assertEquals(1, byToken.size(), "the refused join must leave no trace");
    }

    // ── what may and may not end a session ────────────────────────────────

    @Test
    @DisplayName("an unknown token is reported as expired — the one case a device may forget itself")
    void unknownTokenIsFlaggedExpired() throws Exception {
        mvc.perform(get("/api/guess-it/group/state").param("token", "not-a-real-token"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.expired").value(true))
           .andExpect(jsonPath("$.errorKey").value("expired"));
    }

    @Test
    @DisplayName("a withdrawn group is NOT reported as expired, so devices keep their identity")
    void missingGroupIsNotExpired() throws Exception {
        String token = join("Ada");
        when(groupRepo.findById(7L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/guess-it/group/state").param("token", token))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.expired").doesNotExist())
           .andExpect(jsonPath("$.errorKey").value("groupGone"));
    }

    @Test
    @DisplayName("a request with no token and no session is not treated as an expired one either")
    void missingTokenIsNotExpired() throws Exception {
        mvc.perform(get("/api/guess-it/group/state").param("code", CODE))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.expired").doesNotExist())
           .andExpect(jsonPath("$.errorKey").value("notJoined"));
    }

    // ── concurrent play rows ──────────────────────────────────────────────

    @Test
    @DisplayName("two simultaneous submissions from one device do not surface as an error")
    void racingPlayRowsRecover() throws Exception {
        String token = join("Ada");
        GuessItGroupParticipant gp = byToken.get(token);

        GuessItGame live = new GuessItGame();
        live.setId(11L);
        live.setClientId(CLIENT);
        live.setGroupId(7L);
        live.setTitle("Round 1");
        live.setStatus("active");
        live.setPublished(true);
        live.setTimerSecs(0);
        live.setCurrentClueIndex(0);
        live.setClues("[\"Moabite\"]");
        live.setOptions("[\"Ruth\",\"Esther\"]");
        live.setCorrectAnswer("Ruth");
        when(gameRepo.findById(11L)).thenReturn(Optional.of(live));
        when(gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(7L, "active"))
                .thenReturn(Optional.of(live));
        when(gameRepo.findPublishedByGroupIdOrderBySequenceOrder(7L)).thenReturn(List.of(live));
        when(gameRepo.countUnfinishedPublishedInGroup(7L)).thenReturn(1L);

        GuessItParticipant winnerRow = new GuessItParticipant();
        winnerRow.setId(500L);
        winnerRow.setGameId(11L);
        winnerRow.setGroupParticipantId(gp.getId());
        winnerRow.setMemberName("Ada");

        // The row does not exist when we look, and the insert loses the race to
        // the identical request that arrived a millisecond earlier.
        when(participantRepo.findByGameIdAndGroupParticipantId(11L, gp.getId()))
                .thenReturn(Optional.empty())          // the pre-insert check
                .thenReturn(Optional.of(winnerRow));   // the re-read after losing
        when(participantRepo.save(any(GuessItParticipant.class)))
                .thenThrow(new DataIntegrityViolationException("uq_..._game_group_participant"))
                .thenAnswer(i -> i.getArgument(0));
        when(participantRepo.findByGameId(11L)).thenReturn(new ArrayList<>());
        when(participantRepo.findByGameIdAndWinnerTrue(11L)).thenReturn(List.of());
        when(participantRepo.findByGameIdAndEliminatedTrueAndWinnerFalse(11L)).thenReturn(List.of());
        when(participantRepo.findByGameIdAndEliminatedFalseAndWinnerFalse(11L)).thenReturn(List.of());

        // The participant sees a normal result, not a 500.
        mvc.perform(post("/api/guess-it/group/action")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"gameId\":11,\"option\":\"Ruth\"}"))
           .andExpect(status().isOk());
    }
}

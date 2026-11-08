package com.churchgeniuspro.guessit;

import com.churchgeniuspro.controller.GuessItController;
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
import com.churchgeniuspro.service.PublicLinkResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Round flow (2026-11-08): a correct answer ends the round (clock stops, host
 * starts the next one), starting a group round publishes it, the group stays
 * open while unplayed rounds remain, and waiting players see the last result.
 */
class GuessItRoundEndTest {

    private static final String CLIENT = "CLIENT-1";
    private static final String CODE   = "5N65KX";

    private GuessItGroupRepository            groupRepo;
    private GuessItGroupParticipantRepository groupParticipantRepo;
    private GuessItGameRepository             gameRepo;
    private GuessItParticipantRepository      participantRepo;
    private MockMvc                           mvc;
    private GuessItGroup                      group;
    private GuessItGame                       round1, round2;

    private final Map<String, GuessItGroupParticipant> byToken   = new HashMap<>();
    private final Map<String, GuessItGroupParticipant> byNameKey = new HashMap<>();
    private final Map<Long, GuessItParticipant>        playRows  = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        groupRepo            = mock(GuessItGroupRepository.class);
        groupParticipantRepo = mock(GuessItGroupParticipantRepository.class);
        gameRepo             = mock(GuessItGameRepository.class);
        participantRepo      = mock(GuessItParticipantRepository.class);

        group = new GuessItGroup();
        group.setId(7L); group.setClientId(CLIENT); group.setCode(CODE);
        group.setStatus("active"); group.setTimerSecs(60);
        when(groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse(CODE, "active")).thenReturn(Optional.of(group));
        when(groupRepo.findFirstByCodeAndDeleteFlagFalseOrderByCreatedAtDesc(CODE)).thenReturn(Optional.of(group));
        when(groupRepo.findById(7L)).thenReturn(Optional.of(group));

        when(groupParticipantRepo.findByToken(anyString()))
                .thenAnswer(i -> Optional.ofNullable(byToken.get(i.<String>getArgument(0))));
        when(groupParticipantRepo.findByGroupIdAndNameKey(anyLong(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(byNameKey.get(i.<String>getArgument(1))));
        when(groupParticipantRepo.save(any(GuessItGroupParticipant.class))).thenAnswer(i -> {
            GuessItGroupParticipant p = i.getArgument(0);
            if (p.getId() == null) p.setId(ids.incrementAndGet());
            byToken.put(p.getToken(), p); byNameKey.put(p.getNameKey(), p);
            return p;
        });
        when(groupParticipantRepo.findById(anyLong())).thenAnswer(i -> byToken.values().stream()
                .filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(groupParticipantRepo.findByGroupId(7L)).thenAnswer(i -> new ArrayList<>(byToken.values()));
        when(groupParticipantRepo.findByGroupIdOrderByScoreDescDisplayNameAsc(7L))
                .thenAnswer(i -> new ArrayList<>(byToken.values()));

        round1 = game(11L, 1, "Who am I?", "active", false);
        round2 = game(12L, 2, "What place am I?", "pending", false);
        when(gameRepo.findById(11L)).thenReturn(Optional.of(round1));
        when(gameRepo.findById(12L)).thenReturn(Optional.of(round2));
        when(gameRepo.save(any(GuessItGame.class))).thenAnswer(i -> i.getArgument(0));
        when(gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(7L, "active")).thenAnswer(i ->
                List.of(round1, round2).stream().filter(g -> "active".equals(g.getStatus())).findFirst());
        when(gameRepo.findAllByGroupIdOrderBySequenceOrder(7L)).thenAnswer(i -> List.of(round1, round2));
        when(gameRepo.findPublishedByGroupIdOrderBySequenceOrder(7L)).thenAnswer(i ->
                List.of(round1, round2).stream().filter(GuessItGame::isPublished).toList());
        when(gameRepo.countUnfinishedInGroup(7L)).thenAnswer(i ->
                List.of(round1, round2).stream().filter(g -> !"completed".equals(g.getStatus())).count());

        // Play rows, keyed by group participant.
        when(participantRepo.findByGameIdAndGroupParticipantId(anyLong(), anyLong())).thenAnswer(i ->
                Optional.ofNullable(playRows.get(i.<Long>getArgument(1)))
                        .filter(r -> r.getGameId().equals(i.getArgument(0))));
        when(participantRepo.save(any(GuessItParticipant.class))).thenAnswer(i -> {
            GuessItParticipant p = i.getArgument(0);
            if (p.getId() == null) p.setId(ids.incrementAndGet());
            playRows.put(p.getGroupParticipantId(), p);
            return p;
        });
        when(participantRepo.findByGameId(anyLong())).thenAnswer(i -> playRows.values().stream()
                .filter(r -> r.getGameId().equals(i.getArgument(0))).toList());
        when(participantRepo.findByGameIdAndWinnerTrue(anyLong())).thenAnswer(i -> playRows.values().stream()
                .filter(r -> r.getGameId().equals(i.getArgument(0)) && r.isWinner()).toList());

        GuessItGroupController controller = new GuessItGroupController(
                groupRepo, groupParticipantRepo, gameRepo, participantRepo, mock(FamilyMemberRepository.class),
                new GuessItPlayService(participantRepo, groupParticipantRepo),
                new com.churchgeniuspro.util.PublicSendLimiter());
        GuessItController games = new GuessItController(gameRepo, participantRepo,
                mock(FamilyMemberRepository.class), mock(PublicLinkResolver.class));
        mvc = MockMvcBuilders.standaloneSetup(controller, games).build();
    }

    private GuessItGame game(long id, int seq, String title, String status, boolean published) {
        GuessItGame g = new GuessItGame();
        g.setId(id); g.setClientId(CLIENT); g.setGroupId(7L); g.setSequenceOrder(seq);
        g.setTitle(title); g.setStatus(status); g.setPublished(published); g.setTimerSecs(60);
        g.setCurrentClueIndex("active".equals(status) ? 0 : -1);
        if ("active".equals(status)) g.setClueStartedAt(Instant.now());
        g.setClues("[\"Moabite\",\"Widow\"]");
        g.setOptions("[\"Ruth\",\"Esther\"]");
        g.setCorrectAnswer("Ruth");
        return g;
    }

    private String join(String name) throws Exception {
        String json = mvc.perform(post("/api/guess-it/group/join").contentType("application/json")
                        .content("{\"code\":\"" + CODE + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int i = json.indexOf("\"token\":\"");
        return json.substring(i + 9, json.indexOf('"', i + 9));
    }

    private MockHttpSession host() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "host@church.test");
        s.setAttribute("role", "Admin");
        s.setAttribute("appClientId", CLIENT);
        return s;
    }

    private void answer(String token, String option) throws Exception {
        mvc.perform(post("/api/guess-it/group/action").contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"gameId\":11,\"option\":\"" + option + "\"}"))
           .andExpect(status().isOk());
    }

    @Test
    @DisplayName("starting a group round publishes it, so players see it at once")
    void startPublishes() throws Exception {
        round1.setStatus("pending");
        mvc.perform(post("/api/guess-it/admin/games/11/start").session(host())).andExpect(status().isOk());
        assertEquals("active", round1.getStatus());
        assertTrue(round1.isPublished());

        String token = join("Ada");
        mvc.perform(get("/api/guess-it/group/state").param("token", token))
           .andExpect(jsonPath("$.hasGame").value(true))
           .andExpect(jsonPath("$.title").value("Who am I?"));
    }

    @Test
    @DisplayName("a correct answer ends the round, the group stays open, and waiting players see the result")
    void winnerEndsRound() throws Exception {
        round1.setPublished(true);
        String ada = join("Ada");
        String ben = join("Ben");

        answer(ada, "Ruth");

        assertEquals("completed", round1.getStatus(), "the round ends on a correct answer");
        assertNotNull(round1.getCompletedAt());
        assertEquals("active", group.getStatus(), "round 2 has not been played, so the group stays open");
        assertEquals(1, byToken.get(ada).getScore());

        mvc.perform(get("/api/guess-it/group/state").param("token", ada))
           .andExpect(jsonPath("$.hasGame").value(false))
           .andExpect(jsonPath("$.lastRound.title").value("Who am I?"))
           .andExpect(jsonPath("$.lastRound.youWon").value(true))
           .andExpect(jsonPath("$.lastRound.correctAnswer").value("Ruth"));
        mvc.perform(get("/api/guess-it/group/state").param("token", ben))
           .andExpect(jsonPath("$.lastRound.youWon").value(false))
           .andExpect(jsonPath("$.lastRound.winners[0].name").value("Ada"));

        // A late answer is refused rather than scored.
        mvc.perform(post("/api/guess-it/group/action").contentType("application/json")
                        .content("{\"token\":\"" + ben + "\",\"gameId\":11,\"option\":\"Ruth\"}"))
           .andExpect(jsonPath("$.success").value(false));
        assertEquals(0, byToken.get(ben).getScore());
    }

    @Test
    @DisplayName("a wrong answer does not end the round")
    void wrongAnswerKeepsRoundLive() throws Exception {
        round1.setPublished(true);
        answer(join("Ada"), "Esther");
        assertEquals("active", round1.getStatus());
        verify(gameRepo, never()).save(round1);
    }

    @Test
    @DisplayName("the group closes by itself once its last round has a winner")
    void lastRoundClosesGroup() throws Exception {
        round1.setPublished(true);
        round2.setStatus("completed");
        round2.setCompletedAt(Instant.now().minusSeconds(60));
        answer(join("Ada"), "Ruth");
        assertEquals("ended", group.getStatus());
    }

    @Test
    @DisplayName("restarting a round takes back the point its winner scored")
    void restartTakesBackPoint() throws Exception {
        round1.setPublished(true);
        String ada = join("Ada");
        answer(ada, "Ruth");
        assertEquals(1, byToken.get(ada).getScore());

        // Restart reads the play rows, so wire the score repo into the standalone controller.
        GuessItController games = new GuessItController(gameRepo, participantRepo,
                mock(FamilyMemberRepository.class), mock(PublicLinkResolver.class));
        org.springframework.test.util.ReflectionTestUtils.setField(games, "groupParticipantRepo", groupParticipantRepo);
        MockMvcBuilders.standaloneSetup(games).build()
                .perform(post("/api/guess-it/admin/games/11/restart").session(host()))
                .andExpect(status().isOk());

        assertEquals(0, byToken.get(ada).getScore());
        assertEquals("active", round1.getStatus());
    }

    @Test
    @DisplayName("a bilingual round created with 'Moses – മോശെ' stores the option text and can be won")
    void bilingualRoundCreatedAndWon() throws Exception {
        when(groupRepo.findByIdAndClientIdAndDeleteFlagFalse(7L, CLIENT)).thenReturn(Optional.of(group));
        GuessItGame[] saved = new GuessItGame[1];
        when(gameRepo.save(any(GuessItGame.class))).thenAnswer(i -> {
            GuessItGame g = i.getArgument(0);
            if (g.getId() == null) { g.setId(13L); saved[0] = g; }
            return g;
        });
        String body = "{\"title\":\"Who am I?\","
                + "\"clues\":[\"I grew up in the palace of a king.\\nഞാൻ ഒരു രാജാവിന്റെ കൊട്ടാരത്തിലാണ് വളർന്നത്.\"],"
                + "\"options\":[\"Joshua | യോശുവ\",\"Moses | മോശെ\",\"Aaron | അഹരോൻ\"],"
                + "\"correctAnswer\":\"Moses – മോശെ\"}";
        mvc.perform(post("/api/guess-it/admin/groups/7/games").session(host())
                        .contentType("application/json;charset=UTF-8").content(body))
           .andExpect(status().isOk());
        assertEquals("Moses | മോശെ", saved[0].getCorrectAnswer());

        // Play it: the player picks the option exactly as shown and wins.
        round1 = saved[0];
        round1.setStatus("active"); round1.setPublished(true); round1.setCurrentClueIndex(0);
        round1.setClueStartedAt(Instant.now());
        when(gameRepo.findById(13L)).thenReturn(Optional.of(round1));
        String ada = join("Ada");
        mvc.perform(post("/api/guess-it/group/action").contentType("application/json;charset=UTF-8")
                        .content("{\"token\":\"" + ada + "\",\"gameId\":13,\"option\":\"Moses | മോശെ\"}"))
           .andExpect(status().isOk());
        assertEquals(1, byToken.get(ada).getScore());
        assertEquals("completed", round1.getStatus());

        // An answer that names no option is refused.
        mvc.perform(post("/api/guess-it/admin/groups/7/games").session(host())
                        .contentType("application/json;charset=UTF-8")
                        .content(body.replace("Moses – മോശെ", "Abraham")))
           .andExpect(status().isBadRequest());
    }
}

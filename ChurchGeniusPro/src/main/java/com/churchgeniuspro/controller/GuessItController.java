package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.GuessItGame;
import com.churchgeniuspro.hibernate.GuessItParticipant;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.GuessItGameRepository;
import com.churchgeniuspro.repository.GuessItParticipantRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for the Guess It Bible game.
 *
 * <p>Three access tiers:
 * <ol>
 *   <li><strong>Admin</strong>  – create/manage games (requires Admin/SuperAdmin session)</li>
 *   <li><strong>Member</strong> – submit answers / mark wrong (requires Member or Child session)</li>
 *   <li><strong>Public</strong> – polling endpoint for the big-screen display (no auth,
 *       org identified by an AES-encrypted {@code cid} query param)</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/guess-it")
public class GuessItController {

    private final GuessItGameRepository        gameRepo;
    private final GuessItParticipantRepository participantRepo;
    private final FamilyMemberRepository       familyMemberRepo;
    private final ObjectMapper                 mapper = new ObjectMapper();

    private final com.churchgeniuspro.service.PublicLinkResolver links;

    public GuessItController(GuessItGameRepository gameRepo,
                             GuessItParticipantRepository participantRepo,
                             FamilyMemberRepository familyMemberRepo,
            com.churchgeniuspro.service.PublicLinkResolver links) {
        this.links = links;
        this.gameRepo        = gameRepo;
        this.participantRepo = participantRepo;
        this.familyMemberRepo = familyMemberRepo;
    }

    // ======================================================================
    // ADMIN — game management
    // ======================================================================

    /** GET /api/guess-it/admin/games — list all games for this org. */
    @GetMapping("/admin/games")
    public ResponseEntity<?> listGames(HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<GuessItGame> games = gameRepo.findAllByClientIdOrderBySequenceOrder(clientId);
        return ResponseEntity.ok(games.stream().map(this::gameAdminMap).collect(Collectors.toList()));
    }

    /** POST /api/guess-it/admin/games — create a new game. */
    @PostMapping("/admin/games")
    public ResponseEntity<?> createGame(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String title  = str(body.get("title"));
        String clues  = toJson(body.get("clues"));
        String opts   = toJson(body.get("options"));
        String answer = str(body.get("correctAnswer"));

        if (blank(title))  return err("title is required.");
        if (blank(clues))  return err("At least one clue is required.");
        if (blank(opts))   return err("At least one option is required.");
        if (blank(answer)) return err("correctAnswer is required.");

        // Assign the next sequence order so games always play in creation order
        Integer maxSeq = gameRepo.findMaxSequenceOrderByClientId(clientId);
        int nextSeq = (maxSeq != null ? maxSeq : 0) + 1;

        GuessItGame g = new GuessItGame();
        g.setClientId(clientId);
        g.setTitle(title);
        g.setClues(clues);
        g.setOptions(opts);
        g.setCorrectAnswer(answer);
        g.setCurrentClueIndex(-1);
        g.setStatus("pending");
        g.setSequenceOrder(nextSeq);
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /** PUT /api/guess-it/admin/games/{id} — update a pending game. */
    @PutMapping("/admin/games/{id}")
    public ResponseEntity<?> updateGame(@PathVariable Long id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if (!"pending".equals(g.getStatus())) return err("Only pending games can be edited.");

        if (body.containsKey("title"))        g.setTitle(str(body.get("title")));
        if (body.containsKey("clues"))        g.setClues(toJson(body.get("clues")));
        if (body.containsKey("options"))      g.setOptions(toJson(body.get("options")));
        if (body.containsKey("correctAnswer")) g.setCorrectAnswer(str(body.get("correctAnswer")));
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /** DELETE /api/guess-it/admin/games/{id} — soft-delete a game. */
    @DeleteMapping("/admin/games/{id}")
    public ResponseEntity<?> deleteGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        g.setDeleteFlag(true);
        gameRepo.save(g);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * DELETE /api/guess-it/admin/games/{id}/hard — permanently delete a game and all its participants.
     * This is a hard delete — the row is physically removed from the database.
     */
    @DeleteMapping("/admin/games/{id}/hard")
    public ResponseEntity<?> hardDeleteGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        // Delete participants first (no FK cascade defined), then the game
        List<GuessItParticipant> participants = participantRepo.findByGameId(id);
        if (!participants.isEmpty()) participantRepo.deleteAll(participants);
        gameRepo.delete(g);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * PUT /api/guess-it/admin/games/{id}/timer — set the per-clue timer duration.
     * Body: { "timerSecs": 30 }  (0 = disabled)
     * Can be changed at any time (even mid-game); takes effect on the next clue.
     *
     * <p>Capped at {@link GuessItGroupController#MAX_TIMER_SECS} (three minutes),
     * the same ceiling the group-level control uses — the two share one limit so
     * a game cannot end up outside the range the dropdowns can express.
     */
    @PutMapping("/admin/games/{id}/timer")
    public ResponseEntity<?> setTimer(@PathVariable Long id,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        Integer secs = intVal(body.get("timerSecs"));
        if (secs == null || secs < 0) return err("timerSecs must be a non-negative integer.");
        // Clamp rather than reject: a page left open before the ceiling existed
        // should still be able to set a timer, just not a longer one.
        g.setTimerSecs(Math.min(secs, GuessItGroupController.MAX_TIMER_SECS));
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /** POST /api/guess-it/admin/games/{id}/start — transition pending → active. */
    @PostMapping("/admin/games/{id}/start")
    public ResponseEntity<?> startGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if (!"pending".equals(g.getStatus())) return err("Game is not in pending state.");

        // Mark any previously active game in the SAME lane completed.
        // A game's lane is its group; standalone games share the ungrouped lane.
        // Scoping this way lets two groups run side by side without one ending
        // the other's live game. For games created before groups existed the
        // group is null, so this resolves to exactly the same row as before.
        Optional<GuessItGame> previous = (g.getGroupId() == null)
                ? gameRepo.findFirstByClientIdAndGroupIdIsNullAndStatusAndDeleteFlagFalse(clientId, "active")
                : gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(g.getGroupId(), "active");
        previous.ifPresent(prev -> {
            prev.setStatus("completed");
            prev.setCompletedAt(Instant.now());
            gameRepo.save(prev);
        });

        Instant now = Instant.now();
        g.setStatus("active");
        g.setCurrentClueIndex(0);   // reveal first clue immediately
        g.setStartedAt(now);
        g.setClueStartedAt(now);    // timer anchor for clue 0
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /**
     * POST /api/guess-it/admin/games/{id}/next-clue — reveal the next clue.
     * No auto-elimination: participants choose when they are ready to answer.
     */
    @PostMapping("/admin/games/{id}/next-clue")
    public ResponseEntity<?> nextClue(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if (!"active".equals(g.getStatus())) return err("Game is not active.");

        List<String> clueList = parseJsonArray(g.getClues());
        int currentIdx = g.getCurrentClueIndex();
        if (currentIdx >= clueList.size() - 1) return err("No more clues available.");

        g.setCurrentClueIndex(currentIdx + 1);
        g.setClueStartedAt(Instant.now());   // reset timer anchor for the new clue
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /**
     * POST /api/guess-it/admin/games/{id}/restart — restart an active or completed game.
     * Resets clue index to 0, deletes all participant records, sets status back to active.
     * Works from any status (active or completed) so the admin game list Restart button
     * can reuse the same game record rather than creating a duplicate.
     */
    @PostMapping("/admin/games/{id}/restart")
    public ResponseEntity<?> restartGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if ("pending".equals(g.getStatus())) return err("Use the Start button to begin a pending game.");

        // Mark any other currently active game as completed before taking over
        gameRepo.findFirstByClientIdAndStatusAndDeleteFlagFalse(clientId, "active")
                .ifPresent(prev -> {
                    if (!prev.getId().equals(id)) {
                        prev.setStatus("completed");
                        prev.setCompletedAt(Instant.now());
                        gameRepo.save(prev);
                    }
                });

        // Delete all participants so everyone starts fresh
        List<GuessItParticipant> participants = participantRepo.findByGameId(id);
        if (!participants.isEmpty()) participantRepo.deleteAll(participants);

        // Reset game state and make it active again
        Instant restartNow = Instant.now();
        g.setStatus("active");
        g.setCurrentClueIndex(0);
        g.setStartedAt(restartNow);
        g.setClueStartedAt(restartNow);  // reset timer anchor for clue 0
        g.setCompletedAt(null);
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /**
     * POST /api/guess-it/admin/games/{id}/next-game — end the current active game and
     * start the earliest pending game for this org.
     */
    @PostMapping("/admin/games/{id}/next-game")
    public ResponseEntity<?> nextGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame current = gameRepo.findById(id).orElse(null);
        if (current == null || !current.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if (!"active".equals(current.getStatus())) return err("Current game is not active.");

        // Find the next pending game strictly after the current game's sequence
        // position, staying inside the current game's lane. Inside a group only
        // published games are eligible, so an unreleased round is never started
        // by accident; standalone games only ever advance to other standalone
        // games, which is how this behaved before groups existed.
        List<GuessItGame> all = (current.getGroupId() == null)
                ? gameRepo.findAllByClientIdOrderBySequenceOrder(clientId).stream()
                          .filter(x -> x.getGroupId() == null)
                          .collect(Collectors.toList())
                : gameRepo.findAllByGroupIdOrderBySequenceOrder(current.getGroupId()).stream()
                          .filter(GuessItGame::isPublished)
                          .collect(Collectors.toList());
        GuessItGame next = all.stream()
                .filter(g -> "pending".equals(g.getStatus()) && !g.isDeleteFlag()
                        && g.getSequenceOrder() > current.getSequenceOrder())
                .findFirst()
                .orElse(null);
        // Fallback: if no pending game exists after this one, take any pending game in sequence
        if (next == null) {
            next = all.stream()
                    .filter(g -> "pending".equals(g.getStatus()) && !g.isDeleteFlag())
                    .findFirst()
                    .orElse(null);
        }
        if (next == null) return err("No pending game available to start.");

        // End current game
        current.setStatus("completed");
        current.setCompletedAt(Instant.now());
        gameRepo.save(current);

        // Start next game — inherit timerSecs from current game if next has none set
        Instant nextNow = Instant.now();
        if (next.getTimerSecs() == 0 && current.getTimerSecs() > 0) {
            next.setTimerSecs(current.getTimerSecs());
        }
        next.setStatus("active");
        next.setCurrentClueIndex(0);
        next.setStartedAt(nextNow);
        next.setClueStartedAt(nextNow);
        gameRepo.save(next);
        return ResponseEntity.ok(gameAdminMap(next));
    }

    /** POST /api/guess-it/admin/games/{id}/end — end the active game. */
    @PostMapping("/admin/games/{id}/end")
    public ResponseEntity<?> endGame(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        GuessItGame g = gameRepo.findById(id).orElse(null);
        if (g == null || !g.getClientId().equals(clientId)) return ResponseEntity.notFound().build();
        if (!"active".equals(g.getStatus())) return err("Game is not active.");
        g.setStatus("completed");
        g.setCompletedAt(Instant.now());
        gameRepo.save(g);
        return ResponseEntity.ok(gameAdminMap(g));
    }

    /**
     * GET /api/guess-it/admin/public-link — returns the encrypted cid for the public page URL.
     */
    @GetMapping("/admin/public-link")
    public ResponseEntity<?> publicLink(HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        // The big-screen URL carries the church's live Guess It link (created on first
        // use, subject to the plan/trial rule). Revoke it on Public Screens to end it.
        return links.ensureLink(clientId, com.churchgeniuspro.service.PublicPagePolicy.GUESS_IT_URL, "Guess It")
                .<ResponseEntity<?>>map(l -> ResponseEntity.ok(Map.of(
                        "url", "/guessIt?cid=" + java.net.URLEncoder.encode(l.getToken(), java.nio.charset.StandardCharsets.UTF_8),
                        "cid", l.getToken())))
                .orElseGet(() -> ResponseEntity.status(403).body(Map.of("error", "Guess It is not available for this account.")));
    }

    // ======================================================================
    // PUBLIC — polling endpoint (no auth, cid = encrypted clientId)
    // ======================================================================

    /**
     * GET /api/guess-it/public?cid= — public polling endpoint.
     * Returns a snapshot of the current active game suitable for the big screen.
     */
    @GetMapping("/public")
    public ResponseEntity<?> publicState(@RequestParam(name = "cid") String encryptedCid) {
        String clientId = decryptCid(encryptedCid);
        if (clientId == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid cid"));

        Optional<GuessItGame> gameOpt =
                gameRepo.findFirstByClientIdAndStatusAndDeleteFlagFalse(clientId, "active");
        if (gameOpt.isEmpty()) return ResponseEntity.ok(Map.of("hasGame", false, "v", 0));
        return ResponseEntity.ok(buildPublicState(gameOpt.get()));
    }

    // ======================================================================
    // MEMBER — participant gameplay endpoints
    // ======================================================================

    /**
     * GET /api/guess-it/member/active — returns the active game for Member/Child portal.
     * Includes the participant's own action state so the UI can restore correctly.
     */
    @GetMapping("/member/active")
    public ResponseEntity<?> memberActiveGame(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        // Staff with a linked member account (memberId set by getMemberFamily) can play as members.
        // Staff without a linked member get an empty state rather than 401.
        boolean staff = isStaffSession(session);
        Integer memberId;
        String clientId;
        if (staff) {
            // Use the memberId cached in session by getMemberFamily (if linked)
            Object mid = session.getAttribute("memberId");
            memberId = mid instanceof Number n ? n.intValue() : null;
            if (memberId == null) return ResponseEntity.ok(Map.of("hasGame", false));
            Object cid = session.getAttribute("appClientId");
            clientId = cid instanceof String s && !s.isBlank() ? s : null;
        } else {
            memberId = memberIdFromSession(session);
            clientId = clientIdFromMemberSession(session);
        }
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (clientId == null) clientId = clientIdFromMemberSession(session);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        // Standalone games only. Grouped games are played through the group
        // endpoints, which register the participant and keep their running
        // score; surfacing one here would let a member answer without having
        // joined the group and score nothing for it.
        Optional<GuessItGame> activeOpt =
                gameRepo.findFirstByClientIdAndGroupIdIsNullAndStatusAndDeleteFlagFalse(clientId, "active");
        if (activeOpt.isEmpty()) return ResponseEntity.ok(Map.of("hasGame", false, "v", 0));

        GuessItGame g = activeOpt.get();
        GuessItParticipant p = participantRepo.findByGameIdAndMemberId(g.getId(), memberId)
                .orElse(null);
        return ResponseEntity.ok(buildMemberState(g, p));
    }

    /**
     * POST /api/guess-it/member/action — submit a guess for the current game.
     *
     * <p>Body: {@code { "gameId": 1, "option": "Ruth" }}
     *
     * <p>New rules:
     * <ul>
     *   <li>Only one submission per participant per game.</li>
     *   <li>Correct answer → participant becomes winner; game ends immediately.</li>
     *   <li>Wrong answer → participant is eliminated; cannot submit again for this game.</li>
     *   <li>Race-condition safe: if a winner already exists when this submission arrives, it is rejected.</li>
     * </ul>
     */
    @PostMapping("/member/action")
    public ResponseEntity<?> submitAction(@RequestBody Map<String, Object> body,
                                          HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        boolean staffAction = isStaffSession(session);
        Integer memberId;
        String clientId;
        if (staffAction) {
            Object mid = session.getAttribute("memberId");
            memberId = (mid instanceof Number n) ? n.intValue() : null;
            if (memberId == null) return ResponseEntity.ok(Map.of("success", false, "error", "Not a member account"));
            Object cid = session.getAttribute("appClientId");
            clientId = (cid instanceof String s && !s.isBlank()) ? s : null;
        } else {
            memberId = memberIdFromSession(session);
            if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
            clientId = clientIdFromMemberSession(session);
        }
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "No org"));

        Long gameId = longVal(body.get("gameId"));
        String option = str(body.get("option"));

        if (gameId == null || blank(option))
            return err("gameId and option are required.");

        GuessItGame g = gameRepo.findById(gameId).orElse(null);
        if (g == null || !g.getClientId().equals(clientId))
            return ResponseEntity.notFound().build();
        // Grouped games belong to the group endpoints, which know who the
        // participant is and credit the win to their cumulative score.
        if (g.getGroupId() != null)
            return ResponseEntity.ok(Map.of("success", false,
                    "error", "This game is part of a group. Join the group to play it."));
        if (!"active".equals(g.getStatus()))
            return ResponseEntity.ok(Map.of("success", false, "error", "Game is not active.", "gameEnded", true));

        // Timer-expiry guard: reject submissions after timer runs out (server-side enforcement)
        if (isTimerExpired(g)) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Time is up! Wait for the next clue.", "timerExpired", true));
        }

        // Race-condition guard: if a winner already exists, reject late correct submissions
        boolean winnerExists = participantRepo.findByGameId(gameId)
                .stream().anyMatch(GuessItParticipant::isWinner);
        if (winnerExists)
            return ResponseEntity.ok(Map.of("success", false, "error", "Another participant already answered correctly.", "alreadyWon", true));

        // Validate option exists in the game's option list
        List<String> validOptions = parseJsonArray(g.getOptions());
        if (!validOptions.contains(option))
            return err("Invalid option selected.");

        // Get or create participant record
        final String finalClientId = clientId;
        GuessItParticipant p = participantRepo.findByGameIdAndMemberId(gameId, memberId)
                .orElseGet(() -> {
                    FamilyMember fm = familyMemberRepo.findById(memberId).orElse(null);
                    String name = fm != null
                            ? ((fm.getFirstName() != null ? fm.getFirstName().trim() : "") + " " +
                               (fm.getLastName()  != null ? fm.getLastName().trim()  : "")).trim()
                            : "Member";
                    String role = session != null
                            ? String.valueOf(session.getAttribute("memberRole") != null
                                    ? session.getAttribute("memberRole")
                                    : session.getAttribute("role"))
                            : "Member";
                    GuessItParticipant np = new GuessItParticipant();
                    np.setClientId(finalClientId);
                    np.setGameId(gameId);
                    np.setMemberId(memberId);
                    np.setMemberName(name);
                    np.setMemberRole(role);
                    return participantRepo.save(np);
                });

        // Guard: eliminated or winner participants cannot submit again
        if (p.isEliminated())
            return ResponseEntity.ok(Map.of("success", false, "error", "You have been eliminated from this game.", "eliminated", true));
        if (p.isWinner())
            return ResponseEntity.ok(buildMemberState(g, p));

        // Guard: already submitted an answer for this game
        if (p.getActionsJson() != null && !p.getActionsJson().isBlank() && !p.getActionsJson().equals("{}"))
            return ResponseEntity.ok(Map.of("success", false, "error", "You have already submitted an answer.", "eliminated", p.isEliminated()));

        // Evaluate the answer
        boolean isCorrect = option.equals(g.getCorrectAnswer());
        Map<String, String> actions = parseActions(p.getActionsJson());
        actions.put("answer", (isCorrect ? "correct:" : "wrong:") + option);
        p.setActionsJson(toJsonString(actions));

        if (isCorrect) {
            // Re-check winner race condition just before saving (double-guard)
            boolean stillNoWinner = participantRepo.findByGameId(gameId)
                    .stream().noneMatch(GuessItParticipant::isWinner);
            if (!stillNoWinner) {
                return ResponseEntity.ok(Map.of("success", false, "error", "Another participant already answered correctly.", "alreadyWon", true));
            }
            p.setWinner(true);
            participantRepo.save(p);
            // Game stays active — admin decides when to end/move on
        } else {
            p.setEliminated(true);
            p.setEliminatedAtClue(g.getCurrentClueIndex());
            participantRepo.save(p);
        }

        return ResponseEntity.ok(buildMemberState(g, p));
    }

    // ======================================================================
    // HELPERS
    // ======================================================================

    /** Build the public big-screen state map. */
    private Map<String, Object> buildPublicState(GuessItGame g) {
        List<GuessItParticipant> all      = participantRepo.findByGameId(g.getId());
        List<GuessItParticipant> winners  = all.stream().filter(GuessItParticipant::isWinner).collect(Collectors.toList());
        List<GuessItParticipant> elim     = all.stream().filter(p -> p.isEliminated() && !p.isWinner()).collect(Collectors.toList());
        long activeCount = all.stream().filter(p -> !p.isEliminated() && !p.isWinner()).count();

        List<String> clueList = parseJsonArray(g.getClues());
        int idx = g.getCurrentClueIndex();
        List<String> revealedClues = (idx >= 0) ? clueList.subList(0, Math.min(idx + 1, clueList.size())) : List.of();
        String currentClue = (idx >= 0 && idx < clueList.size()) ? clueList.get(idx) : "";

        // Lightweight fingerprint — clients skip re-render when v is unchanged
        int v = stateVersion(g.getId(), g.getStatus(), idx, all.size(), winners.size(), elim.size(), 0, false, false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasGame",          true);
        m.put("v",                v);
        m.put("gameId",           g.getId());
        m.put("title",            g.getTitle());
        m.put("status",           g.getStatus());
        m.put("currentClueIndex", idx);
        m.put("currentClue",      currentClue);
        m.put("revealedClues",    revealedClues);
        m.put("totalClues",       clueList.size());
        m.put("activeCount",      activeCount);
        m.put("totalJoined",      all.size());
        m.put("winners",   winners.stream().map(p -> Map.of("name", p.getMemberName())).collect(Collectors.toList()));
        m.put("eliminated",elim.stream().map(p -> Map.of("name", p.getMemberName())).collect(Collectors.toList()));
        m.put("completedAt", g.getCompletedAt() != null ? g.getCompletedAt().toString() : null);
        // Timer fields — clients anchor countdown from clueStartedAt (server epoch ms)
        long clueStartedAtMs = g.getClueStartedAt() != null ? g.getClueStartedAt().toEpochMilli() : 0L;
        m.put("timerSecs",     g.getTimerSecs());
        m.put("clueStartedAt", clueStartedAtMs);
        m.put("timerExpired",  isTimerExpired(g));
        return m;
    }

    /** Build the full admin state map (includes clues + options + correctAnswer). */
    private Map<String, Object> gameAdminMap(GuessItGame g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            g.getId());
        m.put("title",         g.getTitle());
        m.put("status",        g.getStatus());
        m.put("sequenceOrder", g.getSequenceOrder());
        m.put("currentClueIndex", g.getCurrentClueIndex());
        m.put("clues",         parseJsonArray(g.getClues()));
        m.put("options",       parseJsonArray(g.getOptions()));
        m.put("correctAnswer", g.getCorrectAnswer());
        m.put("createdAt",     g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);
        m.put("startedAt",     g.getStartedAt()   != null ? g.getStartedAt().toString()   : null);
        m.put("completedAt",   g.getCompletedAt() != null ? g.getCompletedAt().toString() : null);
        m.put("timerSecs",     g.getTimerSecs());
        m.put("clueStartedAt", g.getClueStartedAt() != null ? g.getClueStartedAt().toEpochMilli() : 0L);
        return m;
    }

    /** Build the participant-facing state map. */
    private Map<String, Object> buildMemberState(GuessItGame g, GuessItParticipant p) {
        List<String> clueList = parseJsonArray(g.getClues());
        int idx = g.getCurrentClueIndex();
        List<String> revealedClues = (idx >= 0) ? clueList.subList(0, Math.min(idx + 1, clueList.size())) : List.of();
        String currentClue = (idx >= 0 && idx < clueList.size()) ? clueList.get(idx) : "";

        // Public scoreboard data
        List<GuessItParticipant> all = participantRepo.findByGameId(g.getId());
        List<GuessItParticipant> winners = all.stream().filter(GuessItParticipant::isWinner).collect(Collectors.toList());
        List<GuessItParticipant> elim    = all.stream().filter(pp -> pp.isEliminated() && !pp.isWinner()).collect(Collectors.toList());
        long activeCount = all.stream().filter(pp -> !pp.isEliminated() && !pp.isWinner()).count();

        // Participant personal state
        boolean hasSubmitted = p != null && p.getActionsJson() != null
                && !p.getActionsJson().isBlank() && !p.getActionsJson().equals("{}");
        boolean isWinner    = p != null && p.isWinner();
        boolean isEliminated= p != null && p.isEliminated();

        // Lightweight fingerprint — member clients skip re-render when v is unchanged
        int v = stateVersion(g.getId(), g.getStatus(), idx, all.size(), winners.size(), elim.size(),
                hasSubmitted ? 1 : 0, isWinner, isEliminated);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasGame",          true);
        m.put("v",                v);
        m.put("gameId",           g.getId());
        m.put("title",            g.getTitle());
        m.put("status",           g.getStatus());
        m.put("currentClueIndex", idx);
        m.put("currentClue",      currentClue);
        m.put("revealedClues",    revealedClues);
        m.put("totalClues",       clueList.size());
        m.put("options",          parseJsonArray(g.getOptions()));
        m.put("activeCount",      activeCount);
        m.put("winners",   winners.stream().map(pp -> Map.of("name", pp.getMemberName())).collect(Collectors.toList()));
        m.put("eliminated",elim.stream().map(pp -> Map.of("name", pp.getMemberName())).collect(Collectors.toList()));
        m.put("completedAt", g.getCompletedAt() != null ? g.getCompletedAt().toString() : null);

        if (p != null) {
            m.put("joined",       true);
            m.put("eliminated",   isEliminated);
            m.put("winner",       isWinner);
            m.put("hasSubmitted", hasSubmitted);
        } else {
            m.put("joined",       false);
            m.put("eliminated",   false);
            m.put("winner",       false);
            m.put("hasSubmitted", false);
        }
        // Timer fields — clients anchor countdown from clueStartedAt (server epoch ms)
        long clueStartedAtMs = g.getClueStartedAt() != null ? g.getClueStartedAt().toEpochMilli() : 0L;
        m.put("timerSecs",     g.getTimerSecs());
        m.put("clueStartedAt", clueStartedAtMs);
        m.put("timerExpired",  isTimerExpired(g));
        return m;
    }

    /**
     * Returns true when the timer has expired for the current clue.
     * Always returns false when timer is disabled (timerSecs == 0) or clueStartedAt is null.
     */
    private boolean isTimerExpired(GuessItGame g) {
        if (g.getTimerSecs() <= 0 || g.getClueStartedAt() == null) return false;
        return Instant.now().isAfter(g.getClueStartedAt().plusSeconds(g.getTimerSecs()));
    }

    /**
     * Cheap integer fingerprint over the fields that drive UI changes.
     * Clients compare their last seen v — if equal, skip re-render.
     */
    private static int stateVersion(Long gameId, String status, int clueIdx,
                                     int totalJoined, int winnerCount, int elimCount,
                                     int submitted, boolean winner, boolean elim) {
        int h = 17;
        h = 31 * h + (gameId != null ? gameId.hashCode() : 0);
        h = 31 * h + (status  != null ? status.hashCode()  : 0);
        h = 31 * h + clueIdx;
        h = 31 * h + totalJoined;
        h = 31 * h + winnerCount;
        h = 31 * h + elimCount;
        h = 31 * h + submitted;
        h = 31 * h + (winner ? 1 : 0);
        h = 31 * h + (elim   ? 1 : 0);
        return h;
    }

    // ── Auth helpers ─────────────────────────────────────────────────────────

    /**
     * Returns clientId if the request has any authenticated session belonging to this org.
     * Any logged-in user (any role) from the same clientId may manage Guess It games.
     */
    private String requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return null;
        String clientId = RoleGuard.clientId(request);
        return (clientId != null && !clientId.isBlank()) ? clientId : null;
    }

    /** Returns memberId for Member or Child sessions, else null. */
    /** Returns true when the session belongs to a Staff Portal user (not a Member/Child). */
    private boolean isStaffSession(HttpSession session) {
        if (session == null) return false;
        Object username = session.getAttribute("username");
        if (username == null) return false;
        Object church = session.getAttribute("church");
        if (Boolean.TRUE.equals(church) || "true".equalsIgnoreCase(String.valueOf(church))) return false;
        return true;
    }

    private Integer memberIdFromSession(HttpSession session) {
        if (session == null) return null;
        String role = strAttr(session, "role");
        // Allow Member and Child (stored as memberRole) users
        boolean isMember = "Member".equals(role) && session.getAttribute("memberId") != null;
        if (!isMember) return null;
        Object v = session.getAttribute("memberId");
        return v instanceof Number n ? n.intValue() : null;
    }

    /** Returns clientId for member sessions. */
    private String clientIdFromMemberSession(HttpSession session) {
        if (session == null) return null;
        Object v = session.getAttribute("appClientId");
        if (v instanceof String s && !s.isBlank()) return s;
        Object v2 = session.getAttribute("clientId");
        return v2 instanceof String s2 ? s2 : null;
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return mapper.readValue(json, new TypeReference<List<String>>(){}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseActions(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>(){}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String toJson(Object obj) {
        if (obj == null) return "[]";
        if (obj instanceof String s) {
            // If caller already sends a JSON string, keep it; otherwise wrap it
            String trimmed = s.trim();
            if (trimmed.startsWith("[")) return trimmed;
            // Single value wrapped in array
            try { return mapper.writeValueAsString(List.of(trimmed)); }
            catch (Exception e) { return "[]"; }
        }
        if (obj instanceof List<?> list) {
            try { return mapper.writeValueAsString(list); }
            catch (Exception e) { return "[]"; }
        }
        return "[]";
    }

    private String toJsonString(Map<String, String> map) {
        try { return mapper.writeValueAsString(map); }
        catch (Exception e) { return "{}"; }
    }

    private String decryptCid(String cid) {
        return links.resolveClientId(cid, com.churchgeniuspro.service.PublicPagePolicy.GUESS_IT_URL);   // live link token only
    }
    // Misc helpers

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Integer intVal(Object o) {
        if (o == null) return null;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    private static Long longVal(Object o) {
        if (o == null) return null;
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private static String strAttr(HttpSession session, String key) {
        Object v = session.getAttribute(key);
        return v == null ? null : v.toString().trim();
    }

    private static ResponseEntity<Map<String, Object>> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
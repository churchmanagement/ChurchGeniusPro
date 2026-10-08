package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
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
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Group play for Guess It: several games sharing one set of participants and a
 * cumulative leaderboard.
 *
 * <p>Three ways in, all landing on the same rows:
 * <ul>
 *   <li><b>Admin</b> ({@code /admin/groups/**}) — create a group, add games to
 *       it, publish them, watch the board, close the group. Session required.</li>
 *   <li><b>Public participant</b> ({@code /group/**}) — no login: enter the
 *       six-character code, choose a name, then play. Identified afterwards by
 *       an opaque token held in their browser.</li>
 *   <li><b>Logged-in member</b> — the same {@code /group/**} endpoints, but the
 *       session supplies the identity, so they are never asked for a name.</li>
 * </ul>
 *
 * <p>Answer submission is delegated to {@link GuessItPlayService} so grouped and
 * ungrouped play obey exactly the same rules.
 *
 * <p>The pre-existing endpoints in {@link GuessItController} are untouched, and
 * games with no group behave exactly as they always have.
 */
@RestController
@RequestMapping("/api/guess-it")
public class GuessItGroupController {

    private static final Logger log = LoggerFactory.getLogger(GuessItGroupController.class);

    /** Code alphabet with I, O, 0 and 1 removed so codes are safe to read aloud. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int    CODE_LENGTH   = 6;
    private static final int    MAX_NAME_LEN  = 40;

    /**
     * Longest per-clue countdown a host may choose, in seconds.
     *
     * <p>Three minutes. Enforced here rather than only in the dropdown, because
     * the dropdown is advisory — anything can POST to this endpoint.
     */
    public  static final int    MAX_TIMER_SECS = 180;

    private final GuessItGroupRepository            groupRepo;
    private final GuessItGroupParticipantRepository groupParticipantRepo;
    private final GuessItGameRepository             gameRepo;
    private final GuessItParticipantRepository      participantRepo;
    private final FamilyMemberRepository            familyMemberRepo;
    private final GuessItPlayService                play;
    private final SecureRandom                      random = new SecureRandom();
    private final ObjectMapper                      mapper = new ObjectMapper();

    public GuessItGroupController(GuessItGroupRepository groupRepo,
                                  GuessItGroupParticipantRepository groupParticipantRepo,
                                  GuessItGameRepository gameRepo,
                                  GuessItParticipantRepository participantRepo,
                                  FamilyMemberRepository familyMemberRepo,
                                  GuessItPlayService play,
                                  PublicSendLimiter sendLimiter) {
        this.groupRepo            = groupRepo;
        this.groupParticipantRepo = groupParticipantRepo;
        this.gameRepo             = gameRepo;
        this.participantRepo      = participantRepo;
        this.familyMemberRepo     = familyMemberRepo;
        this.play                 = play;
        this.sendLimiter          = sendLimiter;
    }

    private final PublicSendLimiter sendLimiter;

    /**
     * The subscription plan, for the Activity Corner check below. Field-injected and
     * null-checked so this controller's existing construction sites (and their unit
     * tests) are unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.SubscriptionService subscriptionService;

    /** Test seam — supply the plan without a Spring context. */
    public void setSubscriptionService(com.churchgeniuspro.service.SubscriptionService s) {
        this.subscriptionService = s;
    }

    /**
     * Null when this group's church may still run Activity Corner; otherwise the
     * message to answer with.
     *
     * <p>{@code /api/guess-it/public} and {@code /api/guess-it/group} are excluded
     * from {@code SubscriptionFeatureFilter} because they are reachable without a
     * session, and the catalog justified that by arguing a group can only exist if
     * an admin created it through the gated admin API. That is true when the group
     * is created and not afterwards: a church moved onto the Trial plan, or a demo
     * tenant whose groups were seeded before the evaluation overlay existed, kept
     * live, anonymously joinable boards indefinitely — the exact thing
     * {@code PublicPagePolicy} refuses these tenants a public link for.
     *
     * <p>Fails OPEN on a lookup error, matching the filter it stands in for: a
     * database blip must not end a game a paying church is in the middle of.
     */
    private String activityCornerBlock(GuessItGroup group) {
        if (group == null || subscriptionService == null) return null;
        try {
            if (subscriptionService.isFeatureEnabled(group.getClientId(), "activityCorner")) return null;
        } catch (Exception e) {
            log.warn("Guess It: plan lookup failed for {} — allowing. {}", group.getClientId(), e.getMessage());
            return null;
        }
        log.info("Guess It: refusing group {} — Activity Corner is not available to {}",
                 group.getCode(), group.getClientId());
        return "This group is no longer available.";
    }

    // ======================================================================
    // ADMIN
    // ======================================================================

    /** GET /api/guess-it/admin/groups — every group for this org, newest first. */
    @GetMapping("/admin/groups")
    public ResponseEntity<?> listGroups(HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        List<Map<String, Object>> out = new ArrayList<>();
        for (GuessItGroup g : groupRepo.findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(clientId)) {
            Map<String, Object> m = groupSummary(g);
            m.put("games",        gameRepo.findAllByGroupIdOrderBySequenceOrder(g.getId()).size());
            m.put("participants", groupParticipantRepo.findByGroupId(g.getId()).size());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * POST /api/guess-it/admin/groups — create a group and mint its join code.
     * Body (optional): {@code {"name":"Sunday Youth Night"}}
     */
    @PostMapping("/admin/groups")
    public ResponseEntity<?> createGroup(@RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        String code = generateUniqueCode();
        if (code == null)
            return err("Could not allocate a free group code. Please try again.");

        GuessItGroup g = new GuessItGroup();
        g.setClientId(clientId);
        g.setCode(code);
        g.setName(trimTo(str(body == null ? null : body.get("name")), 200));
        g.setStatus("active");
        Integer secs = intVal(body == null ? null : body.get("timerSecs"));
        if (secs != null) g.setTimerSecs(clampTimer(secs));   // else entity default: 60s
        groupRepo.save(g);

        log.info("GuessIt: created group {} (code {}) for client {}", g.getId(), code, clientId);
        return ResponseEntity.ok(groupSummary(g));
    }

    /** GET /api/guess-it/admin/groups/{id} — games plus live leaderboard. */
    @GetMapping("/admin/groups/{id}")
    public ResponseEntity<?> groupDetail(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        GuessItGroup g = groupRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (g == null) return ResponseEntity.notFound().build();

        Map<String, Object> out = groupSummary(g);
        List<Map<String, Object>> games = new ArrayList<>();
        for (GuessItGame game : gameRepo.findAllByGroupIdOrderBySequenceOrder(id)) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id",        game.getId());
            gm.put("title",     game.getTitle());
            gm.put("status",    game.getStatus());
            gm.put("published", game.isPublished());
            gm.put("timerSecs", game.getTimerSecs());
            gm.put("clues",     play.parseJsonArray(game.getClues()));
            gm.put("options",   play.parseJsonArray(game.getOptions()));
            gm.put("correctAnswer", game.getCorrectAnswer());
            gm.put("winners",   namesOfWinners(game.getId()));
            games.add(gm);
        }
        out.put("gamesList",   games);
        out.put("leaderboard", leaderboard(g));
        return ResponseEntity.ok(out);
    }

    /**
     * POST /api/guess-it/admin/groups/{id}/games — add a game to the group.
     * Body: {@code {title, clues:[], options:[], correctAnswer, timerSecs?}}
     *
     * <p>Mirrors the fields of the existing standalone create-game endpoint; the
     * only additions are the group link and the unpublished starting state.
     */
    @PostMapping("/admin/groups/{id}/games")
    public ResponseEntity<?> createGroupGame(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        GuessItGroup group = groupRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (group == null) return ResponseEntity.notFound().build();
        if (!"active".equals(group.getStatus()))
            return err("This group has ended. Create a new group to add more games.");

        String title         = str(body.get("title"));
        String correctAnswer = str(body.get("correctAnswer"));
        List<String> clues   = stringList(body.get("clues"));
        List<String> options = stringList(body.get("options"));

        if (blank(title))                 return err("Game title is required.");
        if (clues.isEmpty())              return err("At least one clue is required.");
        if (options.size() < 2)           return err("At least two answer options are required.");
        if (blank(correctAnswer))         return err("A correct answer is required.");
        if (!options.contains(correctAnswer))
            return err("The correct answer must be one of the answer options.");

        Integer maxSeq = gameRepo.findMaxSequenceOrderByGroupId(id);

        GuessItGame game = new GuessItGame();
        game.setClientId(clientId);
        game.setGroupId(id);
        game.setTitle(title);
        game.setClues(writeJson(clues));
        game.setOptions(writeJson(options));
        game.setCorrectAnswer(correctAnswer);
        game.setSequenceOrder(maxSeq == null ? 1 : maxSeq + 1);
        game.setStatus("pending");
        game.setPublished(false);
        // A game in a group runs at the group's duration unless the caller names
        // one explicitly, so changing the group setting and then adding a round
        // does not quietly hand that round a different clock.
        Integer timer = intVal(body.get("timerSecs"));
        game.setTimerSecs(clampTimer(timer != null && timer >= 0 ? timer : group.getTimerSecs()));
        gameRepo.save(game);

        return ResponseEntity.ok(Map.of(
                "id", game.getId(), "title", game.getTitle(),
                "published", game.isPublished(), "timerSecs", game.getTimerSecs()));
    }

    /**
     * POST /api/guess-it/admin/groups/{id}/publish — release games to participants.
     * Body: {@code {"gameId": 12}} for one game, or {@code {"all": true}} for the set.
     */
    @PostMapping("/admin/groups/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        GuessItGroup group = groupRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (group == null) return ResponseEntity.notFound().build();
        if (!"active".equals(group.getStatus()))
            return err("This group has ended.");

        boolean all    = body != null && Boolean.TRUE.equals(body.get("all"));
        Long    gameId = body == null ? null : longVal(body.get("gameId"));
        boolean unpublish = body != null && Boolean.TRUE.equals(body.get("unpublish"));

        List<GuessItGame> games = gameRepo.findAllByGroupIdOrderBySequenceOrder(id);
        int changed = 0;
        for (GuessItGame g : games) {
            if (!all && (gameId == null || !gameId.equals(g.getId()))) continue;
            if (g.isPublished() == !unpublish) continue;
            g.setPublished(!unpublish);
            gameRepo.save(g);
            changed++;
        }
        if (changed == 0 && !all && gameId == null)
            return err("Specify a gameId, or pass all:true.");

        return ResponseEntity.ok(Map.of("success", true, "changed", changed));
    }

    /**
     * PUT /api/guess-it/admin/groups/{id}/timer — change the per-clue duration
     * for a group that already exists.
     *
     * <p>Body: {@code {"timerSecs": 180}} — 0 disables the timer, 180 (three
     * minutes) is the ceiling. Values above the ceiling are clamped rather than
     * rejected, so a stale page cannot leave the host staring at an error.
     *
     * <p>The new duration is saved on the group, so every game added later
     * inherits it, and is applied at once to every game in the group that has
     * not finished — the pending rounds and the one currently on screen.
     * Completed games are deliberately left alone: their stored duration is the
     * record of how they were actually played.
     *
     * <p>Changing the live game's duration takes effect on the next clue, which
     * is exactly how the standalone timer control has always behaved; the
     * current clue keeps the countdown the room can already see rather than
     * jumping under them.
     */
    @PutMapping("/admin/groups/{id}/timer")
    public ResponseEntity<?> setGroupTimer(@PathVariable Long id,
                                           @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        GuessItGroup group = groupRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (group == null) return ResponseEntity.notFound().build();

        Integer requested = intVal(body == null ? null : body.get("timerSecs"));
        if (requested == null || requested < 0)
            return err("Duration must be a whole number of seconds, or 0 for no timer.");

        int secs = clampTimer(requested);
        group.setTimerSecs(secs);
        groupRepo.save(group);

        int applied = 0;
        for (GuessItGame g : gameRepo.findAllByGroupIdOrderBySequenceOrder(id)) {
            if ("completed".equals(g.getStatus())) continue;   // keep the record of how it was played
            if (g.getTimerSecs() == secs) continue;
            g.setTimerSecs(secs);
            gameRepo.save(g);
            applied++;
        }

        log.info("GuessIt: group {} (code {}) duration set to {}s; {} game(s) updated",
                 group.getId(), group.getCode(), secs, applied);

        Map<String, Object> out = groupSummary(group);
        out.put("timerSecs", secs);
        out.put("gamesUpdated", applied);
        // Tell the caller when its request was trimmed, so the page can correct
        // its dropdown instead of showing a value the server did not accept.
        out.put("clamped", requested != secs);
        return ResponseEntity.ok(out);
    }

    /**
     * POST /api/guess-it/admin/groups/{id}/end — close the group.
     * Final standings stay readable afterwards; nothing further can be played.
     */
    @PostMapping("/admin/groups/{id}/end")
    public ResponseEntity<?> endGroup(@PathVariable Long id, HttpServletRequest request) {
        String clientId = requireAdmin(request);
        if (clientId == null) return unauthorised();

        GuessItGroup group = groupRepo.findByIdAndClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (group == null) return ResponseEntity.notFound().build();

        closeGroup(group);
        Map<String, Object> out = groupSummary(group);
        out.put("leaderboard", leaderboard(group));
        return ResponseEntity.ok(out);
    }

    // ======================================================================
    // PARTICIPANT — public and logged-in share these
    // ======================================================================

    /**
     * POST /api/guess-it/group/verify — check a join code before asking for a name.
     * Body: {@code {"code":"A1768G"}}
     */
    @PostMapping("/group/verify")
    public ResponseEntity<?> verifyCode(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        // A 6-character code can be guessed in bulk (audit N10/P7): bounded per origin.
        String limited = sendLimiter.check(PublicSendLimiter.GUESSIT_VERIFY, request, null, null);
        if (limited != null) return ResponseEntity.status(429).body(Map.of("success", false, "error", limited));
        String code = normaliseCode(str(body.get("code")));
        if (code == null) return err("Enter the 6-character group code.");

        GuessItGroup group = groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse(code, "active").orElse(null);
        if (group == null) return err("That group code was not recognised. Please check it and try again.");
        String blocked = activityCornerBlock(group);
        if (blocked != null) return err(blocked);

        // A signed-in member never has to type a name — tell the page that up front.
        MemberIdentity me = memberIdentity(request, group.getClientId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok",        true);
        out.put("code",      group.getCode());
        out.put("groupName", group.getName());

        // A device that already holds a token for this group is re-entering it,
        // so it is neither asked for a name nor at risk of colliding with the
        // name it registered earlier.
        String heldToken = str(body.get("token"));
        if (!blank(heldToken)) {
            GuessItGroupParticipant held = groupParticipantRepo.findByToken(heldToken.trim()).orElse(null);
            if (held != null && held.getGroupId().equals(group.getId())) {
                out.put("needsName",     false);
                out.put("alreadyJoined", true);
                out.put("name",          held.getDisplayName());
                return ResponseEntity.ok(out);
            }
        }

        out.put("needsName", me == null);
        if (me != null) out.put("suggestedName", me.firstName());
        return ResponseEntity.ok(out);
    }

    /**
     * POST /api/guess-it/group/join — register in the group.
     *
     * <p>Public: {@code {"code":"A1768G","name":"Sam"}} — the name must be free
     * within that group. Logged-in members send only the code and are joined
     * under their first name automatically.
     */
    @PostMapping("/group/join")
    public ResponseEntity<?> join(@RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        String limited = sendLimiter.check(PublicSendLimiter.GUESSIT_VERIFY, request, null, null);
        if (limited != null) return ResponseEntity.status(429).body(Map.of("success", false, "error", limited));
        String code = normaliseCode(str(body.get("code")));
        if (code == null) return err("Enter the 6-character group code.");

        GuessItGroup group = groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse(code, "active").orElse(null);
        if (group == null) return err("That group code was not recognised. Please check it and try again.");
        String blocked = activityCornerBlock(group);
        if (blocked != null) return err(blocked);

        MemberIdentity me = memberIdentity(request, group.getClientId());

        // A device that already holds a token for this group is re-joining, not
        // joining: a refresh, a reopened tab, or a retry after a dropped
        // network. Resume that identity rather than treating it as a new person
        // — otherwise the second attempt collides with the name the same device
        // registered a moment ago and the participant is locked out of a game
        // they are already in.
        String heldToken = str(body.get("token"));
        if (!blank(heldToken)) {
            GuessItGroupParticipant held =
                    groupParticipantRepo.findByToken(heldToken.trim()).orElse(null);
            if (held != null && held.getGroupId().equals(group.getId()))
                return ResponseEntity.ok(joinPayload(group, held));
        }

        // Already in this group? Resume rather than creating a second identity.
        if (me != null) {
            GuessItGroupParticipant existing =
                    groupParticipantRepo.findByGroupIdAndMemberId(group.getId(), me.memberId()).orElse(null);
            if (existing != null) return ResponseEntity.ok(joinPayload(group, existing));
        }

        String displayName;
        if (me != null) {
            // Members are never asked for a name; collisions get a numeric suffix
            // so two people called Sam can both play.
            displayName = uniqueNameFor(group.getId(), me.firstName());
        } else {
            displayName = trimTo(str(body.get("name")), MAX_NAME_LEN);
            if (blank(displayName)) return err("Enter your name to join.");
            if (groupParticipantRepo.findByGroupIdAndNameKey(group.getId(), nameKey(displayName)).isPresent())
                return errWith("Somebody in this game is already playing as \"" + displayName
                             + "\". Please pick a different name — everyone on the leaderboard needs their own.",
                               "nameTaken");
        }

        // A new participant row (security audit P7): bounded per origin and per group per day.
        String joinLimited = sendLimiter.check(PublicSendLimiter.GUESSIT_JOIN, request, null, String.valueOf(group.getId()));
        if (joinLimited != null) return ResponseEntity.status(429).body(Map.of("success", false, "error", joinLimited));

        GuessItGroupParticipant p = new GuessItGroupParticipant();
        p.setClientId(group.getClientId());
        p.setGroupId(group.getId());
        p.setDisplayName(displayName);
        p.setNameKey(nameKey(displayName));
        p.setMemberId(me == null ? null : me.memberId());
        p.setToken(UUID.randomUUID().toString().replace("-", ""));
        p.setScore(0);
        try {
            groupParticipantRepo.save(p);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Two devices submitted the same name in the same instant; the unique
            // index decided it. The loser is told so rather than being handed a
            // half-made identity.
            return errWith("Somebody in this game is already playing as \"" + displayName
                         + "\". Please pick a different name — everyone on the leaderboard needs their own.",
                           "nameTaken");
        }
        return ResponseEntity.ok(joinPayload(group, p));
    }

    /**
     * GET /api/guess-it/group/state — current game plus leaderboard for one participant.
     * Identify with {@code ?token=} (public) or {@code ?code=} with a member session.
     */
    @GetMapping("/group/state")
    public ResponseEntity<?> groupState(@RequestParam(required = false) String token,
                                        @RequestParam(required = false) String code,
                                        HttpServletRequest request) {
        Resolved r = resolve(token, code, request);
        if (r.error != null) return errWith(r.error, r.errorKey);
        return ResponseEntity.ok(buildGroupState(r.group, r.participant));
    }

    /**
     * POST /api/guess-it/group/action — submit an answer.
     * Body: {@code {token|code, gameId, option}}
     */
    @PostMapping("/group/action")
    public ResponseEntity<?> groupAction(@RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        Resolved r = resolve(str(body.get("token")), str(body.get("code")), request);
        if (r.error != null) return errWith(r.error, r.errorKey);

        if (!"active".equals(r.group.getStatus()))
            return ResponseEntity.ok(Map.of("success", false, "error", "This group has ended.", "gameEnded", true));

        Long   gameId = longVal(body.get("gameId"));
        String option = str(body.get("option"));
        if (gameId == null || blank(option)) return err("gameId and option are required.");

        GuessItGame game = gameRepo.findById(gameId).orElse(null);
        if (game == null || !Objects.equals(game.getGroupId(), r.group.getId()))
            return ResponseEntity.notFound().build();
        if (!game.isPublished())
            return ResponseEntity.ok(Map.of("success", false, "error", "That game is not available yet."));

        GuessItParticipant p = playRowFor(game, r.participant);

        GuessItPlayService.Outcome outcome = play.submit(game, p, option);
        if (!outcome.accepted()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", false);
            out.put("error",   outcome.error());
            if (outcome.errorKey() != null) out.put(outcome.errorKey(), true);
            return ResponseEntity.ok(out);
        }
        // Finishing the last published game closes the group on its own.
        maybeAutoCloseGroup(r.group);
        return ResponseEntity.ok(buildGroupState(r.group, r.participant));
    }

    // ======================================================================
    // INTERNALS
    // ======================================================================

    /**
     * A participant resolved from either a token or a member session.
     *
     * <p>{@code errorKey} matters more than it looks: the participant page holds
     * its identity in browser storage, and it must only throw that identity away
     * when the server has actually said the token is dead ({@code expired}).
     * Anything else — a group that has been removed, a request that arrived
     * without a token — leaves the stored session alone, so a hiccup on one
     * device never signs that person out mid-game.
     */
    private static final class Resolved {
        GuessItGroup            group;
        GuessItGroupParticipant participant;
        String                  error;
        String                  errorKey;
        static Resolved fail(String e, String key) {
            Resolved r = new Resolved(); r.error = e; r.errorKey = key; return r;
        }
    }

    private Resolved resolve(String token, String code, HttpServletRequest request) {
        if (!blank(token)) {
            // Token-identified participants are entirely independent of the HTTP
            // session: nothing here reads or writes one, so any number of
            // devices and browsers can play the same group side by side, and a
            // shared computer cannot mix two people up.
            GuessItGroupParticipant p = groupParticipantRepo.findByToken(token.trim()).orElse(null);
            if (p == null)
                return Resolved.fail("This device is not registered for that game any more. Please join again.",
                                     "expired");
            GuessItGroup g = groupRepo.findById(p.getGroupId()).orElse(null);
            if (g == null || g.isDeleteFlag())
                return Resolved.fail("That group is no longer available.", "groupGone");
            String blocked = activityCornerBlock(g);
            if (blocked != null) return Resolved.fail(blocked, "groupGone");
            Resolved r = new Resolved(); r.group = g; r.participant = p; return r;
        }
        String c = normaliseCode(code);
        if (c == null) return Resolved.fail("Enter the 6-character group code.", "needCode");
        GuessItGroup g = groupRepo.findFirstByCodeAndDeleteFlagFalseOrderByCreatedAtDesc(c).orElse(null);
        if (g == null) return Resolved.fail("That group code was not recognised.", "badCode");
        String blocked = activityCornerBlock(g);
        if (blocked != null) return Resolved.fail(blocked, "groupGone");
        MemberIdentity me = memberIdentity(request, g.getClientId());
        if (me == null) return Resolved.fail("Please join this group before playing.", "notJoined");
        GuessItGroupParticipant p =
                groupParticipantRepo.findByGroupIdAndMemberId(g.getId(), me.memberId()).orElse(null);
        if (p == null) return Resolved.fail("Please join this group before playing.", "notJoined");
        Resolved r = new Resolved(); r.group = g; r.participant = p; return r;
    }

    /**
     * Find or create this participant's play row for one game.
     *
     * <p>Two requests from the same person can land at once — a double tap on a
     * phone, or a tab that was left open and woke up at the same moment. Both
     * would see no row and both would insert one; the unique index on
     * {@code (game_id, group_participant_id)} stops the second, and this catch
     * turns that into "read the row the winner just wrote" rather than a 500.
     * Without it, one participant's stray retry surfaces as an error mid-game.
     */
    private GuessItParticipant playRowFor(GuessItGame game, GuessItGroupParticipant gp) {
        GuessItParticipant existing =
                participantRepo.findByGameIdAndGroupParticipantId(game.getId(), gp.getId()).orElse(null);
        if (existing != null) return existing;

        GuessItParticipant np = new GuessItParticipant();
        np.setClientId(game.getClientId());
        np.setGameId(game.getId());
        np.setGroupParticipantId(gp.getId());
        np.setMemberId(gp.getMemberId());
        np.setMemberName(gp.getDisplayName());
        np.setMemberRole(gp.getMemberId() == null ? "Guest" : "Member");
        try {
            return participantRepo.save(np);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            return participantRepo.findByGameIdAndGroupParticipantId(game.getId(), gp.getId())
                    .orElseThrow(() -> raced);
        }
    }

    /** The state one participant sees: the live game, their own status, the board. */
    private Map<String, Object> buildGroupState(GuessItGroup group, GuessItGroupParticipant gp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("group", groupSummary(group));
        out.put("me", Map.of("name", gp.getDisplayName(), "score", gp.getScore(), "token", gp.getToken()));

        List<Map<String, Object>> board = leaderboard(group);
        out.put("leaderboard", board);

        // The live game is the published one currently active in this group.
        GuessItGame game = gameRepo.findFirstByGroupIdAndStatusAndDeleteFlagFalse(group.getId(), "active")
                .filter(GuessItGame::isPublished).orElse(null);

        List<GuessItGame> published = gameRepo.findPublishedByGroupIdOrderBySequenceOrder(group.getId());
        List<Map<String, Object>> games = new ArrayList<>();
        for (GuessItGame g : published) {
            GuessItParticipant mine =
                    participantRepo.findByGameIdAndGroupParticipantId(g.getId(), gp.getId()).orElse(null);
            games.add(Map.of(
                    "id", g.getId(), "title", g.getTitle(), "status", g.getStatus(),
                    "won", mine != null && mine.isWinner()));
        }
        out.put("games", games);

        if (game == null || !"active".equals(group.getStatus())) {
            out.put("hasGame", false);
            out.put("v", stateVersion(group, null, gp, board));
            return out;
        }

        List<String> clues   = play.parseJsonArray(game.getClues());
        List<String> options = play.parseJsonArray(game.getOptions());
        int idx = Math.max(-1, Math.min(game.getCurrentClueIndex(), clues.size() - 1));
        List<String> revealed = idx < 0 ? List.of() : new ArrayList<>(clues.subList(0, idx + 1));

        GuessItParticipant mine =
                participantRepo.findByGameIdAndGroupParticipantId(game.getId(), gp.getId()).orElse(null);

        out.put("hasGame",       true);
        out.put("gameId",        game.getId());
        out.put("title",         game.getTitle());
        out.put("status",        game.getStatus());
        out.put("revealedClues", revealed);
        out.put("totalClues",    clues.size());
        out.put("options",       options);
        out.put("timerSecs",     game.getTimerSecs());
        out.put("clueStartedAt", game.getClueStartedAt() == null ? null : game.getClueStartedAt().toEpochMilli());
        out.put("timerExpired",  play.isTimerExpired(game));
        out.put("winner",        mine != null && mine.isWinner());
        out.put("eliminated",    mine != null && mine.isEliminated());
        out.put("hasSubmitted",  mine != null && play.hasSubmitted(mine));
        out.put("winners",       namesOfWinners(game.getId()));
        out.put("eliminatedList", namesOf(participantRepo.findByGameIdAndEliminatedTrueAndWinnerFalse(game.getId())));
        out.put("activeCount",   participantRepo.findByGameIdAndEliminatedFalseAndWinnerFalse(game.getId()).size());
        out.put("totalJoined",   groupParticipantRepo.findByGroupId(group.getId()).size());
        out.put("v",             stateVersion(group, game, gp, board));
        return out;
    }

    /**
     * Fingerprint the parts of the state the pages actually redraw on. Both
     * front-ends skip rendering when this is unchanged, so anything that can
     * change on screen — including scores — has to be folded in here.
     */
    private int stateVersion(GuessItGroup group, GuessItGame game,
                             GuessItGroupParticipant gp, List<Map<String, Object>> board) {
        int h = Objects.hash(group.getId(), group.getStatus(), gp.getId(), gp.getScore());
        if (game != null) {
            h = 31 * h + Objects.hash(game.getId(), game.getStatus(), game.getCurrentClueIndex(),
                    game.getClueStartedAt() == null ? 0L : game.getClueStartedAt().toEpochMilli());
            List<GuessItParticipant> rows = participantRepo.findByGameId(game.getId());
            int won = 0, elim = 0;
            for (GuessItParticipant p : rows) { if (p.isWinner()) won++; else if (p.isEliminated()) elim++; }
            h = 31 * h + Objects.hash(rows.size(), won, elim);
        }
        for (Map<String, Object> row : board) h = 31 * h + Objects.hash(row.get("name"), row.get("score"));
        return h;
    }

    /**
     * Standings, highest first. Everyone on the top score is a winner — ties are
     * shown in full rather than broken arbitrarily.
     */
    private List<Map<String, Object>> leaderboard(GuessItGroup group) {
        List<GuessItGroupParticipant> people =
                groupParticipantRepo.findByGroupIdOrderByScoreDescDisplayNameAsc(group.getId());
        int top = people.isEmpty() ? 0 : people.get(0).getScore();
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 0, shown = 0, lastScore = Integer.MIN_VALUE;
        for (GuessItGroupParticipant p : people) {
            shown++;
            if (p.getScore() != lastScore) { rank = shown; lastScore = p.getScore(); }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name",  p.getDisplayName());
            row.put("score", p.getScore());
            row.put("rank",  rank);
            // Nobody is a "winner" at zero points, even if everyone is on zero.
            row.put("isWinner", top > 0 && p.getScore() == top);
            out.add(row);
        }
        return out;
    }

    /** Close a group and stamp the time; safe to call more than once. */
    private void closeGroup(GuessItGroup group) {
        if ("ended".equals(group.getStatus())) return;
        group.setStatus("ended");
        group.setEndedAt(Instant.now());
        groupRepo.save(group);
        log.info("GuessIt: group {} (code {}) ended", group.getId(), group.getCode());
    }

    /** A group with published games, all of them finished, ends by itself. */
    private void maybeAutoCloseGroup(GuessItGroup group) {
        if (!"active".equals(group.getStatus())) return;
        List<GuessItGame> published = gameRepo.findPublishedByGroupIdOrderBySequenceOrder(group.getId());
        if (published.isEmpty()) return;
        if (gameRepo.countUnfinishedPublishedInGroup(group.getId()) == 0) closeGroup(group);
    }

    private Map<String, Object> groupSummary(GuessItGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",      g.getId());
        m.put("code",    g.getCode());
        m.put("name",    g.getName());
        m.put("status",  g.getStatus());
        m.put("timerSecs", g.getTimerSecs());
        m.put("ended",   "ended".equals(g.getStatus()));
        m.put("endedAt", g.getEndedAt() == null ? null : g.getEndedAt().toEpochMilli());
        return m;
    }

    private Map<String, Object> joinPayload(GuessItGroup group, GuessItGroupParticipant p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok",    true);
        out.put("token", p.getToken());
        out.put("name",  p.getDisplayName());
        out.put("score", p.getScore());
        out.put("group", groupSummary(group));
        return out;
    }

    private List<Map<String, Object>> namesOfWinners(Long gameId) {
        return namesOf(participantRepo.findByGameIdAndWinnerTrue(gameId));
    }

    private List<Map<String, Object>> namesOf(List<GuessItParticipant> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GuessItParticipant p : rows) out.add(Map.of("name", String.valueOf(p.getMemberName())));
        return out;
    }

    /** Give a member a name that is free in this group, e.g. "Sam" then "Sam 2". */
    private String uniqueNameFor(Long groupId, String base) {
        String candidate = blank(base) ? "Member" : base;
        if (groupParticipantRepo.findByGroupIdAndNameKey(groupId, nameKey(candidate)).isEmpty())
            return candidate;
        for (int i = 2; i < 100; i++) {
            String next = candidate + " " + i;
            if (groupParticipantRepo.findByGroupIdAndNameKey(groupId, nameKey(next)).isEmpty()) return next;
        }
        return candidate + " " + UUID.randomUUID().toString().substring(0, 4);
    }

    /** Try a few random codes; collisions with live groups are rejected. */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 40; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++)
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            String code = sb.toString();
            if (!groupRepo.existsByCodeAndStatusAndDeleteFlagFalse(code, "active")) return code;
        }
        return null;
    }

    // ── identity ────────────────────────────────────────────────────────────

    /** A logged-in member, when the request carries one for the right org. */
    private record MemberIdentity(Integer memberId, String firstName) {}

    /**
     * Reads a member identity from the session, if any. Public participants have
     * no session and simply get {@code null} — that is the normal path here, not
     * an error, which is why these endpoints are not behind the auth filter.
     */
    private MemberIdentity memberIdentity(HttpServletRequest request, String groupClientId) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        Object mid = session.getAttribute("memberId");
        Integer memberId = mid instanceof Number n ? n.intValue() : null;
        if (memberId == null) return null;

        // Only honour the session when it belongs to the group's own org.
        String sessionClient = null;
        Object v = session.getAttribute("appClientId");
        if (v instanceof String s && !s.isBlank()) sessionClient = s;
        if (sessionClient == null) {
            Object v2 = session.getAttribute("clientId");
            if (v2 instanceof String s2 && !s2.isBlank()) sessionClient = s2;
        }
        if (sessionClient == null || !sessionClient.equals(groupClientId)) return null;

        String first = null;
        FamilyMember fm = familyMemberRepo.findById(memberId).orElse(null);
        if (fm != null && fm.getFirstName() != null && !fm.getFirstName().isBlank())
            first = fm.getFirstName().trim();
        if (first == null) first = "Member";
        return new MemberIdentity(memberId, first);
    }

    private String requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return null;
        String clientId = RoleGuard.clientId(request);
        return (clientId != null && !clientId.isBlank()) ? clientId : null;
    }

    // ── small helpers ───────────────────────────────────────────────────────

    /** Uppercase, strip anything that is not a letter or digit, require 6 chars. */
    private String normaliseCode(String raw) {
        if (raw == null) return null;
        String c = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return c.length() == CODE_LENGTH ? c : null;
    }

    /** Case-folded, whitespace-collapsed key used for the per-group name rule. */
    private String nameKey(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                String s = str(item);
                if (!s.isBlank()) out.add(s);
            }
        } else if (o instanceof String s) {
            for (String line : s.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private String writeJson(List<String> list) {
        try { return mapper.writeValueAsString(list); }
        catch (Exception e) { return "[]"; }
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** Keep a requested duration inside 0..{@link #MAX_TIMER_SECS}. */
    private static int clampTimer(int secs) {
        if (secs < 0) return 0;
        return Math.min(secs, MAX_TIMER_SECS);
    }

    private static String str(Object o)      { return o == null ? "" : o.toString().trim(); }
    private static boolean blank(String s)   { return s == null || s.isBlank(); }
    private static Integer intVal(Object o)  { try { return o == null ? null : Integer.parseInt(o.toString()); } catch (Exception e) { return null; } }
    private static Long longVal(Object o)    { try { return o == null ? null : Long.parseLong(o.toString()); } catch (Exception e) { return null; } }

    private static ResponseEntity<Map<String, Object>> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /**
     * An error carrying a machine-readable key alongside the message, so the
     * participant page can react precisely — most importantly, so it only
     * discards a stored session on {@code expired} and never on a transient
     * failure.
     */
    private static ResponseEntity<Map<String, Object>> errWith(String msg, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", msg);
        if (key != null) body.put("errorKey", key);
        if ("expired".equals(key)) body.put("expired", true);
        return ResponseEntity.badRequest().body(body);
    }
    private static ResponseEntity<Map<String, Object>> unauthorised() {
        return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
    }
}

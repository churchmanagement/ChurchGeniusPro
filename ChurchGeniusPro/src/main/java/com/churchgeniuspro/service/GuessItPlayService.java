package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.GuessItGame;
import com.churchgeniuspro.hibernate.GuessItGroupParticipant;
import com.churchgeniuspro.hibernate.GuessItParticipant;
import com.churchgeniuspro.repository.GuessItGroupParticipantRepository;
import com.churchgeniuspro.repository.GuessItParticipantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of a single Guess It answer submission, in one place.
 *
 * <p>Both ways of playing go through this: the long-standing member portal
 * ({@code /member/action}, identified by family member) and group play
 * ({@code /group/action}, which also covers public participants with no login).
 * Keeping one implementation is what stops the two drifting apart — the brief
 * called for one integrated game, not a parallel one.
 *
 * <p>The rules enforced here are unchanged from the original implementation:
 * <ul>
 *   <li>one submission per participant per game;</li>
 *   <li>the first correct answer wins, and later correct answers are rejected;</li>
 *   <li>a wrong answer eliminates that participant for the rest of the game;</li>
 *   <li>submissions after the clue timer expires are refused.</li>
 * </ul>
 *
 * <p>The one addition is that winning a game which belongs to a group also
 * increments that participant's cumulative group score.
 */
@Service
public class GuessItPlayService {

    private final GuessItParticipantRepository      participantRepo;
    private final GuessItGroupParticipantRepository groupParticipantRepo;
    private final ObjectMapper                      mapper = new ObjectMapper();

    public GuessItPlayService(GuessItParticipantRepository participantRepo,
                              GuessItGroupParticipantRepository groupParticipantRepo) {
        this.participantRepo      = participantRepo;
        this.groupParticipantRepo = groupParticipantRepo;
    }

    /**
     * Result of a submission attempt.
     *
     * @param accepted   whether the answer was recorded at all
     * @param correct    whether the recorded answer was the winning one
     * @param error      human-readable reason when {@code accepted} is false
     * @param errorKey   machine-readable reason so callers can set the flags the
     *                   existing front-ends already look for
     *                   ({@code timerExpired}, {@code alreadyWon}, {@code eliminated})
     */
    public record Outcome(boolean accepted, boolean correct, String error, String errorKey) {
        static Outcome reject(String error, String key) { return new Outcome(false, false, error, key); }
        static Outcome ok(boolean correct)              { return new Outcome(true, correct, null, null); }
    }

    /**
     * Checks the submission against every rule and, when it passes, records it.
     *
     * @param game        the game being played; must already be loaded and tenant-checked
     * @param participant the caller's play row for this game
     * @param option      the exact option string submitted
     */
    @Transactional
    public Outcome submit(GuessItGame game, GuessItParticipant participant, String option) {

        if (!"active".equals(game.getStatus()))
            return Outcome.reject("Game is not active.", "gameEnded");

        if (isTimerExpired(game))
            return Outcome.reject("Time is up! Wait for the next clue.", "timerExpired");

        // A winner already decided this game — late correct answers do not count.
        if (hasWinner(game.getId()))
            return Outcome.reject("Another participant already answered correctly.", "alreadyWon");

        if (!parseJsonArray(game.getOptions()).contains(option))
            return Outcome.reject("Invalid option selected.", "invalidOption");

        if (participant.isEliminated())
            return Outcome.reject("You have been eliminated from this game.", "eliminated");

        if (participant.isWinner())
            return Outcome.reject("You have already won this game.", "alreadyWon");

        if (hasSubmitted(participant))
            return Outcome.reject("You have already submitted an answer.", "alreadySubmitted");

        boolean correct = option.equals(game.getCorrectAnswer());

        Map<String, String> actions = parseActions(participant.getActionsJson());
        actions.put("answer", (correct ? "correct:" : "wrong:") + option);
        participant.setActionsJson(toJsonString(actions));

        if (correct) {
            // Re-check immediately before committing: two correct answers can
            // arrive within the same instant and only the first may win.
            if (hasWinner(game.getId()))
                return Outcome.reject("Another participant already answered correctly.", "alreadyWon");

            participant.setWinner(true);
            participantRepo.save(participant);
            awardGroupPoint(game, participant);
        } else {
            participant.setEliminated(true);
            participant.setEliminatedAtClue(game.getCurrentClueIndex());
            participantRepo.save(participant);
        }

        return Outcome.ok(correct);
    }

    /**
     * Winning a grouped game is worth one point on that group's leaderboard.
     * Standalone games have no group and score nothing, exactly as before.
     */
    private void awardGroupPoint(GuessItGame game, GuessItParticipant participant) {
        if (game.getGroupId() == null || participant.getGroupParticipantId() == null) return;
        groupParticipantRepo.findById(participant.getGroupParticipantId()).ifPresent(gp -> {
            gp.setScore(gp.getScore() + 1);
            groupParticipantRepo.save(gp);
        });
    }

    /** True once anyone in this game has answered correctly. */
    public boolean hasWinner(Long gameId) {
        return participantRepo.findByGameId(gameId).stream().anyMatch(GuessItParticipant::isWinner);
    }

    /** True once this participant has recorded an answer for the game. */
    public boolean hasSubmitted(GuessItParticipant p) {
        String a = p.getActionsJson();
        return a != null && !a.isBlank() && !a.equals("{}");
    }

    /**
     * Server-side clue timer check, anchored to when the clue was revealed so a
     * participant cannot gain time by changing their device clock.
     */
    public boolean isTimerExpired(GuessItGame g) {
        if (g.getTimerSecs() <= 0) return false;
        Instant started = g.getClueStartedAt();
        if (started == null) return false;
        long elapsed = (System.currentTimeMillis() - started.toEpochMilli()) / 1000L;
        return elapsed >= g.getTimerSecs();
    }

    // ── JSON helpers (same shapes the entities document) ────────────────────

    public List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return mapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    public Map<String, String> parseActions(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String toJsonString(Map<String, String> map) {
        try { return mapper.writeValueAsString(map); }
        catch (Exception e) { return "{}"; }
    }
}

package com.churchgeniuspro.security;

import com.churchgeniuspro.config.LoginProtectionProperties;
import com.churchgeniuspro.hibernate.LoginAttemptLog;
import com.churchgeniuspro.hibernate.LoginBlock;
import com.churchgeniuspro.repository.LoginAttemptLogRepository;
import com.churchgeniuspro.repository.LoginBlockRepository;
import com.churchgeniuspro.service.LoginProtectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Behavioural tests for the failed-login protection policy.
 *
 * <p>The two repositories are backed by in-memory lists rather than plain stubs, so the
 * counting, windowing, reset-on-success and escalation logic is genuinely exercised
 * instead of being asserted against canned numbers. No Spring context and no database —
 * these run in milliseconds under {@code ./mvnw test}.
 */
class LoginProtectionServiceTest {

    private static final String VICTIM       = "john@example.com";
    private static final String ATTACKER_IP  = "203.0.113.7";
    private static final String VICTIM_IP    = "198.51.100.22";

    private LoginProtectionProperties props;
    private LoginProtectionService    service;

    /** Everything the fake repositories have been asked to persist. */
    private List<LoginAttemptLog> attempts;
    private List<LoginBlock>      blocks;

    /**
     * Rows are stamped from a monotonic counter rather than {@code LocalDateTime.now()}.
     * Several attempts in one test execute inside the same microsecond, and the
     * reset-on-success cutoff is an inclusive lower bound — with a real clock, whether a
     * pre-reset failure lands before or exactly on the cutoff would be a coin toss.
     */
    private LocalDateTime clockBase;
    private long sequence;

    @BeforeEach
    void setUp() {
        clockBase = LocalDateTime.now();
        sequence  = 0;
        props = new LoginProtectionProperties();
        // Delays make tests slow and prove nothing here; the maths is asserted separately
        // by driving progressiveDelayMillis through recordFailure's return value.
        props.setProgressiveDelayStep(Duration.ofMillis(1));
        props.setProgressiveDelayMax(Duration.ofMillis(3));

        attempts = new ArrayList<>();
        blocks   = new ArrayList<>();

        LoginAttemptLogRepository attemptRepo = mock(LoginAttemptLogRepository.class);
        LoginBlockRepository      blockRepo   = mock(LoginBlockRepository.class);

        when(attemptRepo.save(any(LoginAttemptLog.class))).thenAnswer(inv -> {
            LoginAttemptLog row = inv.getArgument(0);
            row.setAttemptedAt(clockBase.plusNanos(++sequence * 1_000L));
            attempts.add(row);
            return row;
        });
        when(attemptRepo.countUserIpFailuresSince(anyString(), anyString(), any())).thenAnswer(inv ->
                attempts.stream()
                        .filter(a -> LoginAttemptLog.OUTCOME_FAILURE.equals(a.getOutcome()))
                        .filter(a -> inv.getArgument(0).equals(a.getUsername()))
                        .filter(a -> inv.getArgument(1).equals(a.getIpAddress()))
                        .filter(a -> !a.getAttemptedAt().isBefore(inv.getArgument(2)))
                        .count());
        when(attemptRepo.countIpFailuresSince(anyString(), any())).thenAnswer(inv ->
                attempts.stream()
                        .filter(a -> LoginAttemptLog.OUTCOME_FAILURE.equals(a.getOutcome()))
                        .filter(a -> inv.getArgument(0).equals(a.getIpAddress()))
                        .filter(a -> !a.getAttemptedAt().isBefore(inv.getArgument(1)))
                        .count());
        when(attemptRepo.countUsernameFailuresSince(anyString(), any())).thenAnswer(inv ->
                attempts.stream()
                        .filter(a -> LoginAttemptLog.OUTCOME_FAILURE.equals(a.getOutcome()))
                        .filter(a -> inv.getArgument(0).equals(a.getUsername()))
                        .filter(a -> !a.getAttemptedAt().isBefore(inv.getArgument(1)))
                        .count());
        when(attemptRepo.lastSuccessOrResetAt(anyString())).thenAnswer(inv ->
                attempts.stream()
                        .filter(a -> inv.getArgument(0).equals(a.getUsername()))
                        .filter(a -> LoginAttemptLog.OUTCOME_SUCCESS.equals(a.getOutcome())
                                  || LoginAttemptLog.OUTCOME_RESET.equals(a.getOutcome()))
                        .map(LoginAttemptLog::getAttemptedAt)
                        .max(Comparator.naturalOrder())
                        .orElse(null));

        when(blockRepo.save(any(LoginBlock.class))).thenAnswer(inv -> {
            blocks.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(blockRepo.findActive(anyCollection(), any())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            return blocks.stream()
                    .filter(b -> keys.contains(b.getScopeKey()))
                    .filter(b -> b.getBlockedUntil().isAfter(now))
                    .sorted(Comparator.comparing(LoginBlock::getBlockedUntil).reversed())
                    .toList();
        });
        when(blockRepo.countBlocksSince(anyString(), any())).thenAnswer(inv ->
                blocks.stream()
                        .filter(b -> inv.getArgument(0).equals(b.getScopeKey()))
                        .filter(b -> !b.getBlockedAt().isBefore(inv.getArgument(1)))
                        .count());
        when(blockRepo.releaseActiveBlocksForUsername(anyString(), any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(1);
            List<LoginBlock> doomed = blocks.stream()
                    .filter(b -> inv.getArgument(0).equals(b.getUsername()))
                    .filter(b -> b.getBlockedUntil().isAfter(now))
                    .toList();
            blocks.removeAll(doomed);
            return doomed.size();
        });

        service = new LoginProtectionService(attemptRepo, blockRepo, props);
    }

    // ── Normal login ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a clean request is never blocked")
    void normalLogin_isAllowed() {
        assertFalse(service.check(request(VICTIM_IP), VICTIM).blocked());
    }

    @Test
    @DisplayName("a successful login is recorded and leaves nothing blocked")
    void successfulLogin_recordsAndDoesNotBlock() {
        service.recordSuccess(request(VICTIM_IP), VICTIM, "CHR-1", "/login");

        assertEquals(1, attempts.size());
        assertEquals(LoginAttemptLog.OUTCOME_SUCCESS, attempts.get(0).getOutcome());
        assertFalse(service.check(request(VICTIM_IP), VICTIM).blocked());
    }

    // ── Wrong passwords, below the threshold ──────────────────────────────────

    @Test
    @DisplayName("failures below the threshold do not block, and never say how many remain")
    void fewFailures_doNotBlock() {
        for (int i = 0; i < props.getMaxFailedAttempts() - 1; i++) {
            assertFalse(fail(VICTIM, ATTACKER_IP).blockTriggered(), "failure " + (i + 1) + " must not block");
        }
        assertFalse(service.check(request(ATTACKER_IP), VICTIM).blocked());
        assertTrue(blocks.isEmpty());
    }

    @Test
    @DisplayName("progressive delay starts after the configured attempt and is capped")
    void progressiveDelay_growsThenCaps() {
        props.setProgressiveDelayStep(Duration.ofMillis(400));
        props.setProgressiveDelayMax(Duration.ofMillis(1000));
        props.setMaxFailedAttempts(50);          // keep the hard block out of the way

        assertEquals(0,    fail(VICTIM, ATTACKER_IP).delayMillis());   // 1st
        assertEquals(0,    fail(VICTIM, ATTACKER_IP).delayMillis());   // 2nd
        assertEquals(400,  fail(VICTIM, ATTACKER_IP).delayMillis());   // 3rd
        assertEquals(800,  fail(VICTIM, ATTACKER_IP).delayMillis());   // 4th
        assertEquals(1000, fail(VICTIM, ATTACKER_IP).delayMillis());   // 5th — capped
        assertEquals(1000, fail(VICTIM, ATTACKER_IP).delayMillis());   // 6th — still capped
    }

    // ── Temporary blocking ────────────────────────────────────────────────────

    @Test
    @DisplayName("the 5th failure opens a 15-minute username+IP block")
    void fifthFailure_opensTemporaryBlock() {
        for (int i = 0; i < 5; i++) fail(VICTIM, ATTACKER_IP);

        assertEquals(1, blocks.size());
        LoginBlock block = blocks.get(0);
        assertEquals(LoginBlock.SCOPE_USER_IP, block.getScope());
        assertEquals(Duration.ofMinutes(15).toSeconds(), block.getBlockSeconds());

        var guard = service.check(request(ATTACKER_IP), VICTIM);
        assertTrue(guard.blocked());
        assertTrue(guard.retryAfterSeconds() > 0);
        assertEquals(LoginProtectionService.BLOCKED_MESSAGE, guard.message());
    }

    @Test
    @DisplayName("the block message discloses neither the threshold nor the attempts remaining")
    void blockedMessage_leaksNothing() {
        String msg = LoginProtectionService.BLOCKED_MESSAGE.toLowerCase();
        assertFalse(msg.contains("5"));
        assertFalse(msg.contains("attempts remaining"));
        assertFalse(msg.contains("locked"));
        // The generic credential error must not distinguish the two failure modes either.
        assertEquals("Invalid username or password.", LoginProtectionService.GENERIC_FAILURE_MESSAGE);
    }

    @Test
    @DisplayName("no block is permanent — every one carries a bounded expiry")
    void blocks_areAlwaysTimeBoxed() {
        for (int i = 0; i < 5; i++) fail(VICTIM, ATTACKER_IP);

        LoginBlock block = blocks.get(0);
        assertNotNull(block.getBlockedUntil(), "a block without an expiry would be permanent");
        assertTrue(block.getBlockedUntil().isAfter(block.getBlockedAt()));
        assertTrue(block.getBlockSeconds() <= props.getMaxBlockDuration().toSeconds(),
                "no block may exceed max-block-duration");
        assertTrue(service.check(request(ATTACKER_IP), VICTIM).blocked());

        // Wind the clock past the expiry: the block lifts by itself, with no administrator
        // action and no state left behind to clear.
        block.setBlockedUntil(LocalDateTime.now().minusSeconds(1));
        assertFalse(service.check(request(ATTACKER_IP), VICTIM).blocked(),
                "an expired block must stop applying without any intervention");
    }

    @Test
    @DisplayName("an active block is not extended for free by further attempts")
    void blockedAttempts_doNotRatchetTheBlock() {
        for (int i = 0; i < 5; i++) fail(VICTIM, ATTACKER_IP);
        LocalDateTime until = blocks.get(0).getBlockedUntil();
        long failureRowsBefore = countFailureRows();

        service.recordBlockedAttempt(request(ATTACKER_IP), VICTIM, "/login");
        service.recordBlockedAttempt(request(ATTACKER_IP), VICTIM, "/login");

        assertEquals(1, blocks.size(), "no second block row");
        assertEquals(until, blocks.get(0).getBlockedUntil(), "expiry unchanged");
        assertEquals(failureRowsBefore, countFailureRows(),
                "hammering a live block must not inflate the failure counters — otherwise a "
              + "persistent attacker ratchets their own escalation, and the account-scope "
              + "block, upwards for free");
        assertEquals(2, attempts.stream()
                .filter(a -> LoginAttemptLog.OUTCOME_BLOCKED.equals(a.getOutcome()))
                .count(), "the refused attempts are still audited");
    }

    private long countFailureRows() {
        return attempts.stream()
                .filter(a -> LoginAttemptLog.OUTCOME_FAILURE.equals(a.getOutcome()))
                .count();
    }

    // ── THE headline requirement ──────────────────────────────────────────────

    @Test
    @DisplayName("an attacker who knows a username cannot lock the owner out of their own account")
    void attackerCannotLockOutVictim() {
        // Attacker submits five wrong passwords for a username they guessed.
        for (int i = 0; i < 5; i++) fail(VICTIM, ATTACKER_IP);

        // The attacker's own username+IP pair is now blocked...
        assertTrue(service.check(request(ATTACKER_IP), VICTIM).blocked(),
                "the attacker's own pair must be throttled");

        // ...while the real owner, signing in from their own address, is untouched.
        assertFalse(service.check(request(VICTIM_IP), VICTIM).blocked(),
                "the account owner must still be able to sign in");

        // And nothing anywhere marks the account as locked.
        assertTrue(blocks.stream().noneMatch(b -> LoginBlock.SCOPE_USERNAME.equals(b.getScope())),
                "five failures from one host must not trip an account-wide block");
    }

    @Test
    @DisplayName("account-scope block needs a distributed attack, is short, and never escalates")
    void accountScope_isTheDistributedAttackBackstop() {
        // 20 failures spread over 20 different source addresses — no single IP reaches
        // its own threshold, so only the account-scope backstop can catch this.
        for (int i = 0; i < props.getAccountMaxFailedAttempts(); i++) {
            fail(VICTIM, "192.0.2." + i);
        }

        LoginBlock accountBlock = blocks.stream()
                .filter(b -> LoginBlock.SCOPE_USERNAME.equals(b.getScope()))
                .findFirst().orElseThrow(() -> new AssertionError("expected an account-scope block"));

        assertEquals(Duration.ofMinutes(15).toSeconds(), accountBlock.getBlockSeconds());
        assertEquals(0, accountBlock.getEscalationStep(), "account-scope blocks must never escalate");
    }

    @Test
    @DisplayName("turning off the account scope removes the account-level DoS surface entirely")
    void accountScopeDisabled_leavesOwnerUntouchable() {
        props.setAccountScopeEnabled(false);

        for (int i = 0; i < 100; i++) fail(VICTIM, "192.0.2." + (i % 50));

        assertTrue(blocks.stream().noneMatch(b -> LoginBlock.SCOPE_USERNAME.equals(b.getScope())));
        assertFalse(service.check(request(VICTIM_IP), VICTIM).blocked());
    }

    // ── IP-scope rate limiting ────────────────────────────────────────────────

    @Test
    @DisplayName("one host spraying many usernames trips the IP scope")
    void ipScope_catchesUsernameSpraying() {
        for (int i = 0; i < props.getIpMaxFailedAttempts(); i++) {
            fail("victim" + i + "@example.com", ATTACKER_IP);
        }

        assertTrue(blocks.stream().anyMatch(b -> LoginBlock.SCOPE_IP.equals(b.getScope())));
        // Any further login from that host is refused, whatever username it carries.
        assertTrue(service.check(request(ATTACKER_IP), "someone-else@example.com").blocked());
    }

    // ── Reset semantics ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a successful login clears the account counters")
    void success_resetsAccountCounters() {
        for (int i = 0; i < 4; i++) fail(VICTIM, VICTIM_IP);      // one short of the threshold

        service.recordSuccess(request(VICTIM_IP), VICTIM, "CHR-1", "/login");

        // The counter restarts, so the next four failures still do not block.
        for (int i = 0; i < 4; i++) fail(VICTIM, VICTIM_IP);
        assertTrue(blocks.isEmpty(), "counters should have restarted after the successful login");
    }

    @Test
    @DisplayName("a successful login does NOT clear the IP counter (no rate-limit bypass)")
    void success_doesNotResetIpCounter() {
        // The attacker holds one valid account on this host and keeps signing into it,
        // hoping to wipe the IP budget between sprays.
        for (int i = 0; i < props.getIpMaxFailedAttempts() - 1; i++) {
            fail("victim" + i + "@example.com", ATTACKER_IP);
            if (i % 3 == 0) service.recordSuccess(request(ATTACKER_IP), "attacker-own", "CHR-9", "/login");
        }
        assertTrue(blocks.isEmpty());

        fail("one-more@example.com", ATTACKER_IP);
        assertTrue(blocks.stream().anyMatch(b -> LoginBlock.SCOPE_IP.equals(b.getScope())),
                "the IP budget must survive the attacker's own successful logins");
    }

    @Test
    @DisplayName("completing a password reset releases the account's blocks immediately")
    void passwordReset_releasesBlocks() {
        for (int i = 0; i < 5; i++) fail(VICTIM, VICTIM_IP);
        assertTrue(service.check(request(VICTIM_IP), VICTIM).blocked());

        service.clearAccountCounters(VICTIM, "CHR-1", "PASSWORD_RESET");

        assertFalse(service.check(request(VICTIM_IP), VICTIM).blocked(),
                "a completed password reset must be a self-service way out of a block");
        assertTrue(attempts.stream().anyMatch(a -> LoginAttemptLog.OUTCOME_RESET.equals(a.getOutcome())));
    }

    // ── Escalation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("repeat offenders get progressively longer blocks, capped at max-block-duration")
    void repeatBlocks_escalateAndCap() {
        props.setMaxBlockDuration(Duration.ofMinutes(45));

        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 5; i++) fail(VICTIM, ATTACKER_IP);
            // Expire the block so the next round can open a new one, as real time would.
            blocks.forEach(b -> b.setBlockedUntil(LocalDateTime.now().minusSeconds(1)));
            attempts.clear();     // and let the failure window roll over
        }

        List<Integer> lengths = blocks.stream().map(LoginBlock::getBlockSeconds).toList();
        assertEquals(List.of(900, 1800, 2700, 2700), lengths,
                "15m → 30m → 45m (capped) → 45m");
    }

    // ── Logging hygiene ───────────────────────────────────────────────────────

    @Test
    @DisplayName("nothing password-derived is ever persisted to the attempt log")
    void attemptLog_neverHoldsCredentials() {
        fail(VICTIM, ATTACKER_IP);

        LoginAttemptLog row = attempts.get(0);
        assertEquals(VICTIM, row.getUsername());
        assertEquals(ATTACKER_IP, row.getIpAddress());
        assertNotNull(row.getAttemptedAt());
        assertEquals(LoginProtectionService.REASON_BAD_PASSWORD, row.getFailureReason());
        // The entity has no password field at all — asserted structurally so a future
        // "just add the submitted value for debugging" change fails this test.
        assertTrue(java.util.Arrays.stream(LoginAttemptLog.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().toLowerCase().contains("password")),
                "LoginAttemptLog must never gain a password-bearing field");
    }

    @Test
    @DisplayName("usernames are normalised so case cannot be used to get a fresh budget")
    void usernames_areCaseInsensitiveForCounting() {
        fail("John@Example.com", ATTACKER_IP);
        fail("JOHN@EXAMPLE.COM", ATTACKER_IP);
        fail("john@example.com", ATTACKER_IP);
        fail("jOhN@eXaMpLe.CoM", ATTACKER_IP);
        fail("John@example.COM", ATTACKER_IP);

        assertEquals(1, blocks.size(), "all five casings must share one counter");
    }

    // ── Disabled switch ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the master switch turns the whole feature off cleanly")
    void disabled_recordsNothingAndBlocksNothing() {
        props.setEnabled(false);

        for (int i = 0; i < 50; i++) fail(VICTIM, ATTACKER_IP);

        assertTrue(attempts.isEmpty());
        assertTrue(blocks.isEmpty());
        assertFalse(service.check(request(ATTACKER_IP), VICTIM).blocked());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LoginProtectionService.FailureOutcome fail(String username, String ip) {
        return service.recordFailure(request(ip), username,
                LoginProtectionService.REASON_BAD_PASSWORD, null, "/login");
    }

    private HttpServletRequest request(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRemoteAddr(ip);
        // trust-forwarded-headers defaults to true, so set the header the proxy would.
        req.addHeader("X-Forwarded-For", ip);
        req.addHeader("User-Agent", "JUnit");
        return req;
    }
}

package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.RememberMeTokenRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Per-device "Remember me" (Phase 1, M2). The only code that mints, validates,
 * rotates or revokes remember-me credentials, and the only owner of the
 * {@code rememberToken} cookie.
 *
 * <h3>Model</h3>
 * <ul>
 *   <li>The cookie carries 32 random bytes (base64url). The database stores only the
 *       SHA-256 of that value ({@link RememberMeToken#getTokenHash()}).</li>
 *   <li>One row per browser/device. Issuing a new row never touches the others, so
 *       Remember me now works on several devices at once.</li>
 *   <li>Every successful use rotates the value (new cookie, new hash). The previous
 *       hash stays valid for {@link #ROTATION_GRACE} so two tabs that both hit the login
 *       page do not knock each other out.</li>
 *   <li>Expiry slides: each use extends the row to now + {@link #TOKEN_TTL}; a device
 *       that is not used for 30 days stops auto-logging in.</li>
 *   <li>At most {@link #MAX_ACTIVE_PER_ACCOUNT} live rows per login; the oldest is
 *       revoked when the cap is reached.</li>
 * </ul>
 *
 * <h3>Legacy bridge</h3>
 * Before this service, the single {@code signup.remember} column held the raw cookie
 * value. A cookie that matches no row is looked up there once; on a hit the device is
 * migrated to its own row, the column is cleared and a new cookie is issued, so nobody
 * is logged out by the deploy. The column itself is left in place (nullable, no longer
 * written) and can be dropped in a later release.
 */
@Service
public class RememberMeService {

    private static final Logger LOG = LoggerFactory.getLogger(RememberMeService.class);

    public static final String   COOKIE_NAME            = "rememberToken";
    public static final Duration TOKEN_TTL              = Duration.ofDays(30);
    public static final Duration ROTATION_GRACE         = Duration.ofMinutes(5);
    public static final int      MAX_ACTIVE_PER_ACCOUNT = 10;
    /** Dead rows are kept this long for support questions, then deleted. */
    public static final Duration DEAD_ROW_RETENTION     = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RememberMeTokenRepository tokens;
    private final LoginRepository           logins;

    /** Mirrors {@code server.servlet.session.cookie.secure}: Secure flag in production. */
    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean cookieSecure;

    public RememberMeService(RememberMeTokenRepository tokens, LoginRepository logins) {
        this.tokens = tokens;
        this.logins = logins;
    }

    /** For tests that need the Secure flag on (production sets it via the property). */
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }

    // ── Issue / resolve / rotate / revoke ─────────────────────────────────────

    /** Result of resolving a cookie: the login it belongs to and the row that proved it. */
    public record Resolved(SignUp user, RememberMeToken token) {}

    /**
     * Mints a new per-device credential for {@code user}, stores its hash and sets the
     * cookie. Existing rows of the account are untouched (other than the cap).
     */
    @Transactional
    public RememberMeToken issue(SignUp user, HttpServletRequest request, HttpServletResponse response) {
        String secret = newSecret();
        RememberMeToken row = issueRow(user, request, secret, null);
        addCookie(response, secret);
        return row;
    }

    /**
     * Mobile Phase 6: mints a per-device credential for the app shell and returns the
     * RAW secret instead of setting a cookie. The app keeps it in the OS secure store
     * (Keychain / Keystore) behind the biometric prompt and presents it to
     * {@code /api/auth/device-login}; the server only ever stores its hash. Rows look
     * exactly like cookie rows (same table, cap, expiry, revocation), with
     * {@code platform} android/ios and the device name the app reports.
     */
    @Transactional
    public String issueDeviceSecret(SignUp user, HttpServletRequest request, String deviceName) {
        String secret = newSecret();
        issueRow(user, request, secret, deviceName);
        return secret;
    }

    private RememberMeToken issueRow(SignUp user, HttpServletRequest request, String secret, String deviceName) {
        Instant now = Instant.now();
        enforceCap(user.getId(), now);
        RememberMeToken row = new RememberMeToken();
        row.setSignupId(user.getId());
        row.setTokenHash(sha256Hex(secret));
        String label = deviceName == null ? null : deviceName.trim();
        row.setDeviceLabel(label == null || label.isEmpty() ? deviceLabel(request) : label.substring(0, Math.min(label.length(), 200)));
        row.setPlatform(platformOf(request));
        row.setCreatedAt(now);
        row.setLastUsedAt(now);
        row.setExpiresAt(now.plus(TOKEN_TTL));
        tokens.save(row);
        return row;
    }

    /**
     * Validates the cookie on {@code request}. Returns the login and row when the value
     * is live; empty otherwise. Does NOT rotate — callers that go on to sign the user in
     * call {@link #rotate} afterwards, so a refused sign-in never burns the token.
     */
    @Transactional
    public Optional<Resolved> resolve(HttpServletRequest request) {
        return resolveSecret(readCookie(request), request);
    }

    /**
     * Mobile Phase 6: validates a raw secret presented in a request body (the app's
     * device token) exactly as {@link #resolve} validates the cookie. Does not rotate.
     */
    @Transactional
    public Optional<Resolved> resolveSecret(String raw, HttpServletRequest request) {
        if (raw == null || raw.isBlank() || raw.length() > 128) return Optional.empty();
        Instant now = Instant.now();

        String hash = sha256Hex(raw);
        Optional<RememberMeToken> byHash = tokens.findByTokenHash(hash);
        if (byHash.isEmpty()) {
            // Previous value, inside the grace window after a rotation (second tab).
            byHash = tokens.findByPrevTokenHash(hash)
                    .filter(t -> t.getRotatedAt() != null && t.getRotatedAt().plus(ROTATION_GRACE).isAfter(now));
        }
        if (byHash.isPresent()) {
            RememberMeToken row = byHash.get();
            if (!row.isUsable(now)) return Optional.empty();
            return logins.findById(row.getSignupId())
                    .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                    .map(u -> new Resolved(u, row));
        }

        // Legacy: raw value stored in signup.remember by the pre-Phase-1 code.
        return logins.findByRememberToken(raw).map(user -> {
            RememberMeToken row = new RememberMeToken();
            row.setSignupId(user.getId());
            row.setTokenHash(hash);               // rotate() replaces this immediately
            row.setDeviceLabel(deviceLabel(request));
            row.setPlatform(platformOf(request));
            row.setCreatedAt(now);
            row.setLastUsedAt(now);
            row.setExpiresAt(now.plus(TOKEN_TTL));
            tokens.save(row);
            user.setRemember(null);
            logins.save(user);
            LOG.info("Remember-me: migrated legacy token of signup id={} to a per-device row", user.getId());
            return new Resolved(user, row);
        });
    }

    /**
     * Rotates {@code row} after a successful auto-login: new secret, new cookie, sliding
     * expiry; the old hash is kept for the grace window.
     */
    @Transactional
    public void rotate(RememberMeToken row, HttpServletResponse response) {
        addCookie(response, rotateSecret(row));
    }

    /**
     * Mobile Phase 6: rotates {@code row} and returns the NEW raw secret for the app to
     * store; the previous hash stays valid for the grace window.
     */
    @Transactional
    public String rotateSecret(RememberMeToken row) {
        Instant now = Instant.now();
        String secret = newSecret();
        row.setPrevTokenHash(row.getTokenHash());
        row.setRotatedAt(now);
        row.setTokenHash(sha256Hex(secret));
        row.setLastUsedAt(now);
        row.setExpiresAt(now.plus(TOKEN_TTL));
        tokens.save(row);
        return secret;
    }

    /**
     * Revokes the credential carried by {@code request} (this device only) and clears the
     * cookie. Other devices of the same account keep working. Safe with no cookie.
     */
    @Transactional
    public void revokeCurrent(HttpServletRequest request, HttpServletResponse response) {
        String raw = readCookie(request);
        if (raw != null && !raw.isBlank()) {
            Instant now = Instant.now();
            String hash = sha256Hex(raw);
            tokens.findByTokenHash(hash).or(() -> tokens.findByPrevTokenHash(hash)).ifPresent(row -> {
                if (row.getRevokedAt() == null) {
                    row.setRevokedAt(now);
                    tokens.save(row);
                }
            });
            // A legacy cookie may still be in flight on a browser that never auto-logged in.
            logins.findByRememberToken(raw).ifPresent(user -> {
                user.setRemember(null);
                logins.save(user);
            });
        }
        clearCookie(response);
    }

    /**
     * Mobile Phase 6: revokes the row behind a raw device secret (app logout). Safe with
     * an unknown or blank value.
     */
    @Transactional
    public void revokeSecret(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 128) return;
        Instant now = Instant.now();
        String hash = sha256Hex(raw);
        tokens.findByTokenHash(hash).or(() -> tokens.findByPrevTokenHash(hash)).ifPresent(row -> {
            if (row.getRevokedAt() == null) {
                row.setRevokedAt(now);
                tokens.save(row);
            }
        });
    }

    /**
     * "Your devices": revokes one row of the login. Returns false when the row is not
     * this login's (or does not exist), so a caller can answer 404 without leaking.
     */
    @Transactional
    public boolean revokeOne(Integer signupId, Long rowId) {
        if (signupId == null || rowId == null) return false;
        RememberMeToken row = tokens.findById(rowId).orElse(null);
        if (row == null || !signupId.equals(row.getSignupId())) return false;
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now());
            tokens.save(row);
        }
        return true;
    }

    /**
     * "Sign out of other devices": every live row of the login except the one proving
     * {@code request} (its cookie or the app's device secret). Returns the count.
     */
    @Transactional
    public int revokeOthers(Integer signupId, HttpServletRequest request, String currentDeviceSecret) {
        if (signupId == null) return 0;
        Instant now = Instant.now();
        Long keep = currentRowId(request, currentDeviceSecret);
        int n = 0;
        for (RememberMeToken row : tokens.findActiveBySignupId(signupId, now)) {
            if (keep != null && keep.equals(row.getId())) continue;
            row.setRevokedAt(now);
            tokens.save(row);
            n++;
        }
        return n;
    }

    /** The id of the row proving this request (cookie first, then the app's device secret), or null. */
    public Long currentRowId(HttpServletRequest request, String deviceSecret) {
        for (String raw : new String[] { readCookie(request), deviceSecret }) {
            if (raw == null || raw.isBlank() || raw.length() > 128) continue;
            String hash = sha256Hex(raw);
            Optional<RememberMeToken> row = tokens.findByTokenHash(hash).or(() -> tokens.findByPrevTokenHash(hash));
            if (row.isPresent()) return row.get().getId();
        }
        return null;
    }

    /** "Sign out everywhere": every live row of the login is revoked. Returns the count. */
    @Transactional
    public int revokeAllFor(Integer signupId) {
        if (signupId == null) return 0;
        int n = tokens.revokeAllBySignupId(signupId, Instant.now());
        logins.findById(signupId).filter(u -> u.getRemember() != null).ifPresent(u -> {
            u.setRemember(null);
            logins.save(u);
        });
        return n;
    }

    /** Live devices of a login, oldest first — for a future "your devices" screen. */
    public List<RememberMeToken> activeDevices(Integer signupId) {
        return tokens.findActiveBySignupId(signupId, Instant.now());
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /** Deletes revoked / expired rows older than {@link #DEAD_ROW_RETENTION}. Never throws. */
    @Scheduled(cron = "${scheduler.job.remember-me-purge:0 30 3 * * *}", zone = "America/Chicago")
    public void purgeDeadTokens() {
        try {
            int n = tokens.deleteDeadBefore(Instant.now().minus(DEAD_ROW_RETENTION));
            if (n > 0) LOG.info("Remember-me purge removed {} dead token row(s)", n);
        } catch (Exception e) {
            LOG.warn("Remember-me purge failed: {}", e.getMessage());
        }
    }

    // ── Cookie helpers (the only place the cookie is written) ─────────────────

    public void addCookie(HttpServletResponse response, String value) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setMaxAge((int) TOKEN_TTL.toSeconds());
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public static String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst().orElse(null);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void enforceCap(Integer signupId, Instant now) {
        List<RememberMeToken> live = tokens.findActiveBySignupId(signupId, now);
        int excess = live.size() - (MAX_ACTIVE_PER_ACCOUNT - 1);
        for (int i = 0; i < excess; i++) {
            live.get(i).setRevokedAt(now);
            tokens.save(live.get(i));
        }
    }

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** "Chrome on Windows"-style label from the User-Agent; never used for authorisation. */
    static String deviceLabel(HttpServletRequest request) {
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) return "Unknown device";
        String os = ua.contains("Windows") ? "Windows"
                  : ua.contains("iPhone") ? "iPhone"
                  : ua.contains("iPad") ? "iPad"
                  : ua.contains("Android") ? "Android"
                  : ua.contains("Mac OS X") ? "Mac"
                  : ua.contains("CrOS") ? "ChromeOS"
                  : ua.contains("Linux") ? "Linux" : "Unknown OS";
        String browser = ua.contains("Edg/") ? "Edge"
                       : ua.contains("OPR/") ? "Opera"
                       : ua.contains("Firefox/") ? "Firefox"
                       : ua.contains("Chrome/") ? "Chrome"
                       : ua.contains("Safari/") ? "Safari" : "Browser";
        return browser + " on " + os;
    }

    /** {@code web} for browsers; the app shell is recognised by header or User-Agent marker. */
    static String platformOf(HttpServletRequest request) {
        String p = request == null ? null : request.getHeader("X-CGP-Platform");
        if (p == null) {
            // The shell appends "ChurchGeniusProApp/1.0 (android|ios)" to its User-Agent.
            String ua = request == null ? null : request.getHeader("User-Agent");
            if (ua != null && ua.contains("ChurchGeniusProApp/")) {
                if (ua.contains("(android)")) return "android";
                if (ua.contains("(ios)")) return "ios";
            }
            return RememberMeToken.PLATFORM_WEB;
        }
        p = p.trim().toLowerCase();
        return (p.equals("android") || p.equals("ios")) ? p : RememberMeToken.PLATFORM_WEB;
    }
}

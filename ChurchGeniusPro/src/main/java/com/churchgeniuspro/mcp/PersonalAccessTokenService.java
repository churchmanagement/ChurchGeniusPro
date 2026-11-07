package com.churchgeniuspro.mcp;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.McpTenantSetting;
import com.churchgeniuspro.hibernate.PersonalAccessToken;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.McpTenantSettingRepository;
import com.churchgeniuspro.repository.PersonalAccessTokenRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Personal access tokens for the MCP endpoint (AI Phase 5, 2026-11-05).
 *
 * <p>A token is minted once ({@link #create}) and shown to its owner once; the server
 * keeps only its SHA-256. {@link #authenticate} turns a presented token into a
 * {@link Principal}: the staff login the token belongs to, re-read on every request
 * (enabled, not deleted, same church), with the permission map sign-in would load,
 * narrowed by the token's scopes. Anything that fails returns a {@link Refusal} with a
 * code the filter answers 401/403 with — never a half principal.
 *
 * <p>Scopes (closed set, {@link #SCOPES}): {@code read} is implied by every token;
 * {@code contact} lets e-mail / phone / address through unmasked; {@code giving} lets
 * finance data through (without it the principal has no income / expense / reports /
 * pledges access, whatever the person's own permissions say); {@code actions} opens the
 * confirm-gated write tools of Phase 4.
 */
@Service
public class PersonalAccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenService.class);

    public static final String PLAN_FEATURE = "mcp";
    public static final String PREFIX = "cgp_";
    public static final Set<String> SCOPES = new LinkedHashSet<>(List.of("read", "contact", "giving", "actions"));
    public static final int DEFAULT_EXPIRY_DAYS = 90, MAX_EXPIRY_DAYS = 365, MAX_LIVE_PER_USER = 10;
    static final long LAST_USED_WRITE_GAP_SECONDS = 60;

    /** What the filter builds the request principal from. */
    public record Principal(PersonalAccessToken token, AppUser user, String privileges, Set<String> scopes) {
        public boolean has(String scope) { return scopes.contains(scope); }
    }
    /** Why a token was refused: {@code code} is machine-readable, {@code status} the HTTP status to answer with. */
    public record Refusal(int status, String code, String message) {}
    /** Result of {@link #authenticate}: exactly one of the two is set. */
    public record Outcome(Principal principal, Refusal refusal) {
        static Outcome ok(Principal p) { return new Outcome(p, null); }
        static Outcome no(int status, String code, String message) { return new Outcome(null, new Refusal(status, code, message)); }
    }
    /** What {@link #create} hands back: the secret is in {@code secret} ONCE. */
    public record Created(PersonalAccessToken token, String secret) {}

    private final PersonalAccessTokenRepository tokens;
    private final McpTenantSettingRepository settings;
    private final AppUserRepository users;
    private final UserPermissionsRepository permissions;
    private final SubscriptionService subscriptions;     // null in unit tests → plan feature assumed on
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PersonalAccessTokenService(PersonalAccessTokenRepository tokens, McpTenantSettingRepository settings,
                                      AppUserRepository users, UserPermissionsRepository permissions,
                                      @Autowired(required = false) SubscriptionService subscriptions,
                                      @Value("${mcp.enabled:true}") boolean enabled) {
        this.tokens = tokens; this.settings = settings; this.users = users; this.permissions = permissions;
        this.subscriptions = subscriptions; this.enabled = enabled;
    }

    /** Server-wide kill switch ({@code mcp.enabled}). */
    public boolean serverEnabled() { return enabled; }

    /** The plan allows external AI connections (feature {@code mcp}; PRO / TRIAL). Fails closed without a church. */
    public boolean planAllows(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        if (subscriptions == null) return true;
        try { return subscriptions.isFeatureEnabled(clientId, PLAN_FEATURE); }
        catch (RuntimeException e) { log.warn("MCP plan check failed for a tenant: {}", e.getClass().getSimpleName()); return false; }
    }

    /** The church switched external connections on (API Tokens page). No row = off. */
    public boolean tenantEnabled(String clientId) {
        if (clientId == null || clientId.isBlank()) return false;
        return settings.findByAppClientId(clientId).map(McpTenantSetting::isEnabled).orElse(false);
    }

    @Transactional
    public McpTenantSetting setTenantEnabled(String clientId, boolean on, String by) {
        McpTenantSetting s = settings.findByAppClientId(clientId).orElseGet(() -> { McpTenantSetting n = new McpTenantSetting(); n.setAppClientId(clientId); return n; });
        s.setEnabled(on); s.setUpdatedBy(by); s.setUpdatedAt(LocalDateTime.now());
        return settings.save(s);
    }

    /* ───────────────────────── lifecycle ───────────────────────── */

    /** Validates and normalises a requested scope list: unknown names are refused, {@code read} is always present. */
    public static Set<String> normaliseScopes(Collection<?> requested) {
        Set<String> out = new LinkedHashSet<>();
        out.add("read");
        if (requested == null) return out;
        for (Object o : requested) {
            String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (!SCOPES.contains(s)) throw new IllegalArgumentException("Unknown scope: " + s);
            out.add(s);
        }
        return out;
    }

    public static Set<String> scopesOf(PersonalAccessToken t) {
        Set<String> out = new LinkedHashSet<>();
        out.add("read");
        if (t != null && t.getScopes() != null)
            for (String s : t.getScopes().split(",")) { String x = s.trim(); if (!x.isEmpty() && SCOPES.contains(x)) out.add(x); }
        return out;
    }

    /**
     * Mints a token for {@code user} (a staff login of {@code clientId}). Caller has already checked
     * the session, the plan and the church opt-in. Name 1–80 chars; expiry 1–365 days (default 90);
     * at most {@value #MAX_LIVE_PER_USER} live tokens per person.
     */
    @Transactional
    public Created create(String clientId, AppUser user, String username, String name, Collection<?> scopes, Integer expiryDays) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 80) throw new IllegalArgumentException("Give the token a name (up to 80 characters).");
        int days = expiryDays == null ? DEFAULT_EXPIRY_DAYS : expiryDays;
        if (days < 1 || days > MAX_EXPIRY_DAYS) throw new IllegalArgumentException("Expiry must be between 1 and " + MAX_EXPIRY_DAYS + " days.");
        Set<String> sc = normaliseScopes(scopes);
        LocalDateTime now = LocalDateTime.now();
        if (tokens.countByAppClientIdAndAppUserIdAndRevokedAtIsNullAndExpiresAtAfter(clientId, user.getId(), now) >= MAX_LIVE_PER_USER)
            throw new IllegalStateException("You already have " + MAX_LIVE_PER_USER + " active tokens. Revoke one first.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PersonalAccessToken t = new PersonalAccessToken();
        t.setAppClientId(clientId); t.setAppUserId(user.getId()); t.setUsername(username == null ? "" : username);
        t.setName(n); t.setTokenPrefix(secret.substring(0, 12)); t.setTokenHash(sha256Hex(secret));
        t.setScopes(String.join(",", sc)); t.setCreatedAt(now); t.setExpiresAt(now.plusDays(days));
        return new Created(tokens.save(t), secret);
    }

    public List<PersonalAccessToken> mine(String clientId, Integer appUserId) { return tokens.findByAppClientIdAndAppUserIdOrderByCreatedAtDesc(clientId, appUserId); }
    public List<PersonalAccessToken> all(String clientId) { return tokens.findByAppClientIdOrderByCreatedAtDesc(clientId); }

    /** Revokes one token of this church; {@code ownerOnly} restricts to the caller's own tokens. Idempotent. */
    @Transactional
    public boolean revoke(String clientId, Long id, Integer callerUserId, boolean ownerOnly, String by) {
        PersonalAccessToken t = tokens.findByIdAndAppClientId(id, clientId).orElse(null);
        if (t == null) return false;
        if (ownerOnly && !Objects.equals(t.getAppUserId(), callerUserId)) return false;
        if (t.getRevokedAt() == null) { t.setRevokedAt(LocalDateTime.now()); t.setRevokedBy(by); tokens.save(t); }
        return true;
    }

    @Transactional
    public int revokeAll(String clientId, String by) { return tokens.revokeAll(clientId, by, LocalDateTime.now()); }

    /* ───────────────────────── authentication ───────────────────────── */

    /**
     * The presented secret → the principal, or a refusal. Every check that can change after the
     * token was issued is made HERE, on every request: server switch, token revoked / expired, the
     * person disabled or deleted or moved, the plan, the church opt-in.
     */
    @Transactional
    public Outcome authenticate(String rawToken) {
        if (!enabled) return Outcome.no(503, "MCP_DISABLED", "External AI connections are switched off on this server.");
        if (rawToken == null || !rawToken.startsWith(PREFIX) || rawToken.length() < 20 || rawToken.length() > 200)
            return Outcome.no(401, "INVALID_TOKEN", "Invalid token.");
        PersonalAccessToken t = tokens.findByTokenHash(sha256Hex(rawToken)).orElse(null);
        if (t == null) return Outcome.no(401, "INVALID_TOKEN", "Invalid token.");
        LocalDateTime now = LocalDateTime.now();
        if (t.isRevoked()) return Outcome.no(401, "TOKEN_REVOKED", "This token was revoked.");
        if (t.isExpired(now)) return Outcome.no(401, "TOKEN_EXPIRED", "This token has expired.");
        AppUser u = users.findByIdAndClientId(t.getAppUserId(), t.getAppClientId()).orElse(null);
        if (u == null || u.isDeleteFlag() || !u.isEnabled())
            return Outcome.no(401, "USER_INACTIVE", "The account this token belongs to is no longer active.");
        if (!isStaffRole(u.getRole())) return Outcome.no(403, "STAFF_ONLY", "Tokens work for staff accounts only.");
        if (!planAllows(t.getAppClientId())) return Outcome.no(403, "PLAN", "External AI connections are not included in this church's plan.");
        if (!tenantEnabled(t.getAppClientId())) return Outcome.no(403, "TENANT_OFF", "External AI connections are switched off for this church.");
        String priv = com.churchgeniuspro.util.RolePermissionDefaults.effective(u.getRole(),
                permissions.findByAppUserId(u.getId()).map(UserPermissions::getPermissions).orElse(u.getPrivileges()));
        if (t.getLastUsedAt() == null || t.getLastUsedAt().plusSeconds(LAST_USED_WRITE_GAP_SECONDS).isBefore(now)) {
            t.setLastUsedAt(now);
            try { tokens.save(t); } catch (RuntimeException ignore) { /* last-used is informational */ }
        }
        return Outcome.ok(new Principal(t, u, priv, scopesOf(t)));
    }

    public static boolean isStaffRole(String role) {
        return role != null && Set.of("SuperAdmin", "Admin", "Accountant", "User").contains(role);
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The row as the API shows it — never the hash. */
    public static Map<String, Object> view(PersonalAccessToken t, LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId()); m.put("name", t.getName()); m.put("prefix", t.getTokenPrefix() + "…");
        m.put("owner", t.getUsername()); m.put("scopes", new ArrayList<>(scopesOf(t)));
        m.put("createdAt", String.valueOf(t.getCreatedAt())); m.put("expiresAt", String.valueOf(t.getExpiresAt()));
        m.put("lastUsedAt", t.getLastUsedAt() == null ? null : String.valueOf(t.getLastUsedAt()));
        m.put("revokedAt", t.getRevokedAt() == null ? null : String.valueOf(t.getRevokedAt()));
        m.put("status", t.isRevoked() ? "REVOKED" : t.isExpired(now) ? "EXPIRED" : "ACTIVE");
        return m;
    }
}

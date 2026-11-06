package com.churchgeniuspro.ai.consent;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.consent.AiConsentNotices.Notice;
import com.churchgeniuspro.ai.consent.AiConsentNotices.Type;
import com.churchgeniuspro.hibernate.AiConsent;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AiConsentRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * App Store readiness D6 — per-person consent to the three AI data-sharing notices.
 *
 * <p>Who answers is ALWAYS the individual login of the current session ({@code signup.id}),
 * read from the server session — never from a request parameter. Sign-in puts it on the
 * session as {@code loginId}; a session opened before that change is resolved once by its
 * own client id + username (exactly one live login must match, otherwise the person is asked
 * to sign in again). Temporary-access, NTAG, API-token and Kids Portal sessions cannot give
 * consent, so AI stays refused for them inside the app.
 */
@Service
public class AiConsentService {

    public static final String SESSION_LOGIN_ID = "loginId";

    public enum Kind { STAFF, MEMBER, CHURCH }

    /** The individual login answering, within its church. */
    public record LoginRef(String clientId, Integer loginId, Kind kind, String actor, String role) {}

    private final AiConsentRepository repo;
    private final LoginRepository logins;
    private AiAuditService audit;

    public AiConsentService(AiConsentRepository repo, LoginRepository logins) {
        this.repo = repo;
        this.logins = logins;
    }

    @Autowired(required = false)
    public void setAudit(AiAuditService audit) { this.audit = audit; }

    /** The session's own login, or empty when this session cannot give consent. */
    public Optional<LoginRef> login(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null) return Optional.empty();
        if (s.getAttribute("tempAccessId") != null || s.getAttribute("ntagCredId") != null
                || Boolean.TRUE.equals(s.getAttribute("apiToken")) || RoleGuard.isKidsPortalSession(s)) {
            return Optional.empty();
        }
        String tenant = SessionUtil.getAppClientId(request);
        if (tenant == null || tenant.isBlank()) return Optional.empty();
        Integer loginId = loginIdOf(s);
        if (loginId == null) return Optional.empty();
        boolean church = SessionUtil.isChurchAccount(request);
        boolean member = "Member".equals(s.getAttribute("role")) && s.getAttribute("memberId") != null;
        Kind kind = church ? Kind.CHURCH : member ? Kind.MEMBER : Kind.STAFF;
        Object actor = s.getAttribute("username");
        Object role = s.getAttribute("role");
        return Optional.of(new LoginRef(tenant, loginId, kind,
                actor == null ? null : String.valueOf(actor), role == null ? null : String.valueOf(role)));
    }

    /** Session {@code loginId}, else exactly one live login with the session's client id AND username. */
    Integer loginIdOf(HttpSession s) {
        Object v = s.getAttribute(SESSION_LOGIN_ID);
        if (v instanceof Number n) return n.intValue();
        Object cid = s.getAttribute("clientId");
        Object username = s.getAttribute("username");
        if (!(cid instanceof String c) || c.isBlank() || !(username instanceof String u) || u.isBlank() || logins == null) return null;
        try {
            Optional<SignUp> row = logins.findByClientId(c);
            if (row.isPresent() && row.get().getId() != null && u.equalsIgnoreCase(row.get().getUsername())) {
                s.setAttribute(SESSION_LOGIN_ID, row.get().getId());
                return row.get().getId();
            }
        } catch (RuntimeException ignored) { /* more than one row or a lookup error → not resolvable */ }
        return null;
    }

    /** Valid = agreed, not withdrawn, at the current version. */
    public boolean hasConsent(LoginRef who, Type type) {
        Optional<AiConsent> row = repo.findByAppClientIdAndLoginIdAndNoticeType(who.clientId(), who.loginId(), type.name());
        return row.isPresent() && valid(row.get(), type);
    }

    static boolean valid(AiConsent c, Type type) {
        return c.getAgreedAt() != null && c.getWithdrawnAt() == null
                && c.getNoticeVersion() != null && c.getNoticeVersion() == AiConsentNotices.current(type).version();
    }

    /** Records the agreement. Refused (false) when {@code version} is not the current one. */
    public boolean agree(LoginRef who, Type type, int version, String source) {
        Notice n = AiConsentNotices.current(type);
        if (version != n.version()) return false;
        LocalDateTime now = LocalDateTime.now();
        AiConsent c = repo.findByAppClientIdAndLoginIdAndNoticeType(who.clientId(), who.loginId(), type.name()).orElseGet(AiConsent::new);
        c.setAppClientId(who.clientId());
        c.setLoginId(who.loginId());
        c.setLoginKind(who.kind().name());
        c.setNoticeType(type.name());
        c.setNoticeVersion(n.version());
        c.setAgreedAt(now);
        c.setWithdrawnAt(null);
        c.setSource(source == null ? null : source.length() > 10 ? source.substring(0, 10) : source);
        c.setUpdatedAt(now);
        repo.save(c);
        auditEvent(who, "consent.agreed." + type.name());
        return true;
    }

    /** Withdraws the given notices (all when {@code types} is empty). Returns how many were withdrawn. */
    public int withdraw(LoginRef who, List<Type> types) {
        LocalDateTime now = LocalDateTime.now();
        int n = 0;
        for (AiConsent c : repo.findByAppClientIdAndLoginId(who.clientId(), who.loginId())) {
            Type t = AiConsentNotices.parse(c.getNoticeType());
            if (t == null || (!types.isEmpty() && !types.contains(t))) continue;
            if (c.getWithdrawnAt() != null || c.getAgreedAt() == null) continue;
            c.setWithdrawnAt(now);
            c.setUpdatedAt(now);
            repo.save(c);
            auditEvent(who, "consent.withdrawn." + t.name());
            n++;
        }
        return n;
    }

    /** The three notices with this login's state. */
    public List<Map<String, Object>> status(LoginRef who) {
        Map<String, AiConsent> rows = new LinkedHashMap<>();
        if (who != null) for (AiConsent c : repo.findByAppClientIdAndLoginId(who.clientId(), who.loginId())) rows.put(c.getNoticeType(), c);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Type t : Type.values()) {
            Notice n = AiConsentNotices.current(t);
            AiConsent c = rows.get(t.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.name());
            m.put("version", n.version());
            m.put("title", n.title());
            m.put("paragraphs", n.paragraphs());
            m.put("cancel", AiConsentNotices.BUTTON_CANCEL);
            m.put("agree", AiConsentNotices.BUTTON_AGREE);
            boolean ok = c != null && valid(c, t);
            m.put("agreed", ok);
            m.put("agreedAt", ok ? String.valueOf(c.getAgreedAt()) : null);
            m.put("withdrawnAt", c != null && c.getWithdrawnAt() != null ? String.valueOf(c.getWithdrawnAt()) : null);
            m.put("outdated", c != null && c.getWithdrawnAt() == null && c.getAgreedAt() != null && !ok);
            out.add(m);
        }
        return out;
    }

    private void auditEvent(LoginRef who, String intent) {
        if (audit == null) return;
        audit.record(who.clientId(), who.actor(), who.role(), "CONSENT", "/api/ai/consent", intent, null, 200, "OK", 0);
    }
}

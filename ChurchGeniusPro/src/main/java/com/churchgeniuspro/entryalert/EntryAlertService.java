package com.churchgeniuspro.entryalert;

import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.hibernate.EntryAlertSetting;
import com.churchgeniuspro.repository.EntryAlertSettingRepository;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 2026-11-09 — the ONE place that decides Income / Expense entry alerts (user spec "Income &amp; Expense Alerts,
 * Validation, and AI Entry Confirmation", §2 and §5). Used by every way a new entry is made: the Income and Expense
 * pages, the Home quick add and Bank Import (through {@code POST /api/income|expense}) and the Genius Assistant
 * conversation ({@code AiGuidedRecordService}) — so the same entry gets the same warnings whatever the entry method.
 *
 * <p>Settings belong to one login in one role ({@link EntryAlertSetting}); nothing is shared between users. An alert
 * is a WARNING: the caller shows it and saves only after the user confirms. It never replaces the existing
 * validation (amount &gt; 0, maximum amount, required fields, permissions) — those still refuse.
 */
@Slf4j
@Service
public class EntryAlertService {

    public enum Kind { INCOME, EXPENSE;
        public static Kind of(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(Locale.ROOT)) { case "income" -> INCOME; case "expense" -> EXPENSE; default -> null; };
        }
        public String noun() { return this == INCOME ? "income" : "expense"; }
    }

    /** Limits for the thresholds (sensible validation, §2). */
    public static final int MAX_DAYS = 3650;
    public static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /** One alert: a stable code, the threshold that triggered it and a sentence that explains why. */
    public record Alert(String code, String message) {
        public Map<String, Object> toMap() { Map<String, Object> m = new LinkedHashMap<>(); m.put("code", code); m.put("message", message); return m; }
    }

    /** The settings view sent to the page (and read back from it). */
    public record Settings(boolean futureEnabled, Integer futureDays, boolean pastEnabled, Integer pastDays,
                           boolean maxEnabled, BigDecimal maxAmount, boolean minEnabled, BigDecimal minAmount) {
        public static final Settings NONE = new Settings(false, null, false, null, false, null, false, null);
        public boolean any() { return futureEnabled || pastEnabled || maxEnabled || minEnabled; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("futureEnabled", futureEnabled); m.put("futureDays", futureDays);
            m.put("pastEnabled", pastEnabled);     m.put("pastDays", pastDays);
            m.put("maxEnabled", maxEnabled);       m.put("maxAmount", maxAmount == null ? null : maxAmount.toPlainString());
            m.put("minEnabled", minEnabled);       m.put("minAmount", minAmount == null ? null : minAmount.toPlainString());
            return m;
        }
    }

    private final EntryAlertSettingRepository repo;

    public EntryAlertService(EntryAlertSettingRepository repo) { this.repo = repo; }

    // ── who ───────────────────────────────────────────────────────────────────

    /** The login + role the settings belong to, from the session; null when the session has no login. */
    public record Owner(String clientId, String username, String role, Integer appUserId) {}

    public static Owner owner(HttpServletRequest req) {
        String cid = SessionUtil.getAppClientId(req);
        String user = SessionUtil.getUsername(req);
        if (cid == null || user == null) return null;
        String role = SessionUtil.isChurchAccount(req) ? "Church" : SessionUtil.getRole(req);
        if (role == null || role.isBlank()) role = "Unknown";
        Integer appUserId = null;
        var s = req.getSession(false);
        if (s != null && s.getAttribute("appUserId") instanceof Number n) appUserId = n.intValue();
        return new Owner(cid, user, role, appUserId);
    }

    // ── settings ──────────────────────────────────────────────────────────────

    public Settings settings(Owner o, Kind kind) {
        if (o == null || kind == null) return Settings.NONE;
        try {
            return repo.findByAppClientIdAndUsernameAndRoleAndKind(o.clientId(), o.username(), o.role(), kind.name())
                .map(r -> new Settings(r.isFutureEnabled(), r.getFutureDays(), r.isPastEnabled(), r.getPastDays(),
                                       r.isMaxEnabled(), r.getMaxAmount(), r.isMinEnabled(), r.getMinAmount()))
                .orElse(Settings.NONE);
        } catch (RuntimeException e) {
            // A settings lookup failure must never stop an entry: no alerts, logged.
            log.warn("[entry-alerts] settings lookup failed: {}", e.getClass().getSimpleName());
            return Settings.NONE;
        }
    }

    /**
     * Validates and stores the login's settings for one kind. Returns the problems (field → sentence); empty = saved.
     * A switched-on alert needs a valid value; a switched-off alert keeps a valid value (or none) for next time.
     */
    public Map<String, String> save(Owner o, Kind kind, Map<String, Object> body) {
        Map<String, String> problems = new LinkedHashMap<>();
        boolean fe = bool(body.get("futureEnabled")), pe = bool(body.get("pastEnabled")),
                xe = bool(body.get("maxEnabled")),    ne = bool(body.get("minEnabled"));
        Integer fd = days(body.get("futureDays"), "futureDays", fe, "number of days ahead", problems);
        Integer pd = days(body.get("pastDays"), "pastDays", pe, "number of days in the past", problems);
        BigDecimal max = kind == Kind.EXPENSE ? ExpenseController.AMOUNT_MAX : IncomeController.AMOUNT_MAX;
        BigDecimal xa = money(body.get("maxAmount"), "maxAmount", xe, "amount for “greater than”", max, problems);
        BigDecimal na = money(body.get("minAmount"), "minAmount", ne, "amount for “less than”", max, problems);
        if (xe && ne && xa != null && na != null && na.compareTo(xa) >= 0)
            problems.put("minAmount", "The “less than” amount must be smaller than the “greater than” amount.");
        if (!problems.isEmpty()) return problems;
        EntryAlertSetting r = repo.findByAppClientIdAndUsernameAndRoleAndKind(o.clientId(), o.username(), o.role(), kind.name())
            .orElseGet(EntryAlertSetting::new);
        r.setAppClientId(o.clientId()); r.setUsername(o.username()); r.setRole(o.role()); r.setKind(kind.name());
        r.setAppUserId(o.appUserId());
        r.setFutureEnabled(fe); r.setFutureDays(fd);
        r.setPastEnabled(pe);   r.setPastDays(pd);
        r.setMaxEnabled(xe);    r.setMaxAmount(xa);
        r.setMinEnabled(ne);    r.setMinAmount(na);
        r.setUpdatedAt(LocalDateTime.now());
        repo.save(r);
        return problems;
    }

    private static boolean bool(Object v) { return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)); }

    private static Integer days(Object v, String key, boolean on, String what, Map<String, String> problems) {
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty() || s.equals("null")) { if (on) problems.put(key, "Enter the " + what + " (a whole number from 1 to " + MAX_DAYS + ")."); return null; }
        if (!s.matches("\\d{1,5}")) { problems.put(key, "The " + what + " must be a whole number from 1 to " + MAX_DAYS + "."); return null; }
        int n = Integer.parseInt(s);
        if (n < 1 || n > MAX_DAYS) { problems.put(key, "The " + what + " must be from 1 to " + MAX_DAYS + "."); return null; }
        return n;
    }

    private static BigDecimal money(Object v, String key, boolean on, String what, BigDecimal max, Map<String, String> problems) {
        String s = v == null ? "" : String.valueOf(v).trim().replace(",", "").replaceFirst("^\\$", "");
        if (s.isEmpty() || s.equals("null")) { if (on) problems.put(key, "Enter the " + what + " (greater than $0)."); return null; }
        BigDecimal a;
        try { a = new BigDecimal(s); } catch (NumberFormatException e) { problems.put(key, "The " + what + " must be a number, such as 100 or 100.50."); return null; }
        if (a.signum() <= 0) { problems.put(key, "The " + what + " must be greater than $0."); return null; }
        if (a.scale() > 2) { problems.put(key, "The " + what + " can have at most two decimal places."); return null; }
        if (a.compareTo(max) > 0) { problems.put(key, "The " + what + " is too large."); return null; }
        return a.setScale(2, RoundingMode.UNNECESSARY);
    }

    // ── the check ─────────────────────────────────────────────────────────────

    /** The alerts the login's own settings raise for this entry (empty = none). Never throws. */
    public List<Alert> check(Owner o, Kind kind, LocalDate date, BigDecimal amount, LocalDate today) {
        if (o == null || kind == null) return List.of();
        return evaluate(settings(o, kind), kind, date, amount, today);
    }

    /** Pure rule: the same settings + entry always give the same alerts. */
    public static List<Alert> evaluate(Settings s, Kind kind, LocalDate date, BigDecimal amount, LocalDate today) {
        List<Alert> out = new ArrayList<>();
        if (s == null || !s.any()) return out;
        String noun = kind.noun();
        if (date != null && today != null) {
            long ahead = ChronoUnit.DAYS.between(today, date);
            if (s.futureEnabled() && s.futureDays() != null && ahead > s.futureDays())
                out.add(new Alert("FUTURE_DATE", "The date " + US.format(date) + " is " + days(ahead) + " in the future. Your alert is set for "
                    + noun + " dated more than " + days(s.futureDays()) + " ahead."));
            if (s.pastEnabled() && s.pastDays() != null && -ahead > s.pastDays())
                out.add(new Alert("OLD_DATE", "The date " + US.format(date) + " is " + days(-ahead) + " in the past. Your alert is set for "
                    + noun + " dated more than " + days(s.pastDays()) + " ago."));
        }
        if (amount != null) {
            if (s.maxEnabled() && s.maxAmount() != null && amount.compareTo(s.maxAmount()) > 0)
                out.add(new Alert("AMOUNT_HIGH", "The amount " + usd(amount) + " is greater than " + usd(s.maxAmount()) + ", the limit set in your "
                    + noun + " alerts."));
            if (s.minEnabled() && s.minAmount() != null && amount.compareTo(s.minAmount()) < 0)
                out.add(new Alert("AMOUNT_LOW", "The amount " + usd(amount) + " is less than " + usd(s.minAmount()) + ", the minimum set in your "
                    + noun + " alerts."));
        }
        return out;
    }

    private static String days(long n) { return n == 1 ? "1 day" : n + " days"; }
    public static String usd(BigDecimal a) { return NumberFormat.getCurrencyInstance(Locale.US).format(a); }

    /** The 409 body every entry endpoint returns when alerts need the user's confirmation. */
    public static Map<String, Object> conflictBody(Kind kind, List<Alert> alerts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "ENTRY_ALERTS");
        m.put("error", "Please check this " + kind.noun() + " before saving.");
        m.put("alerts", alerts.stream().map(Alert::toMap).toList());
        m.put("confirmField", "alertsConfirmed");
        return m;
    }
}

package com.churchgeniuspro.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds how often the AI endpoints can be called (AI Phase 0, 2026-11-04).
 *
 * <p>Two fixed windows, both in memory (the app runs as one instance — see the ShedLock
 * note in CLAUDE.md; counters start again after a restart, which only ever lets more
 * through, never less):
 * <ul>
 *   <li><b>per user per minute</b> — {@code ai.rate.per-user-per-minute} (default 60): stops a
 *       stuck client, a script, or a Converse loop from hammering the model;</li>
 *   <li><b>per church per day</b> (church calendar day, America/Chicago) —
 *       {@code ai.rate.per-tenant-per-day} (default 5,000): the spend cap one tenant can
 *       put on the shared OpenAI key.</li>
 * </ul>
 * {@code 0} disables a window. {@code ai.rate.enforce=false} turns refusals into WARN
 * lines (the first production week), so the limits can be tuned on real traffic before
 * they bite.
 */
@Component
public class AiRateLimiter {

    public static final ZoneId CHURCH_ZONE = ZoneId.of("America/Chicago");

    /** Why a call was allowed or refused. {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, String scope, long retryAfterSeconds) {
        public static final Decision OK = new Decision(true, null, 0);
    }

    private final int perUserPerMinute;
    private final int perTenantPerDay;
    private final boolean enforce;
    private final Clock clock;
    private final Map<String, Window> users = new ConcurrentHashMap<>();
    private final Map<String, Window> tenants = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AiRateLimiter(@Value("${ai.rate.per-user-per-minute:60}") int perUserPerMinute,
                         @Value("${ai.rate.per-tenant-per-day:5000}") int perTenantPerDay,
                         @Value("${ai.rate.enforce:true}") boolean enforce) {
        this(perUserPerMinute, perTenantPerDay, enforce, Clock.systemUTC());
    }

    public AiRateLimiter(int perUserPerMinute, int perTenantPerDay, boolean enforce, Clock clock) {
        this.perUserPerMinute = perUserPerMinute;
        this.perTenantPerDay = perTenantPerDay;
        this.enforce = enforce;
        this.clock = clock;
    }

    public boolean enforcing() { return enforce; }

    /**
     * Counts one call and says whether it may proceed. Both windows are charged even when
     * one refuses, so a refused caller cannot probe the other window for free.
     */
    public Decision tryAcquire(String clientId, String actor) {
        Instant now = clock.instant();
        Decision out = Decision.OK;
        if (perUserPerMinute > 0 && actor != null) {
            long minute = now.getEpochSecond() / 60;
            Window w = users.compute(clientId + "|" + actor, (k, old) -> old == null || old.key != minute ? new Window(minute) : old);
            if (w.count.incrementAndGet() > perUserPerMinute)
                out = new Decision(false, "user-minute", 60 - (now.getEpochSecond() % 60));
        }
        if (perTenantPerDay > 0 && clientId != null) {
            LocalDate day = now.atZone(CHURCH_ZONE).toLocalDate();
            long key = day.toEpochDay();
            Window w = tenants.compute(clientId, (k, old) -> old == null || old.key != key ? new Window(key) : old);
            if (w.count.incrementAndGet() > perTenantPerDay && out.allowed()) {
                Instant midnight = day.plusDays(1).atStartOfDay(CHURCH_ZONE).toInstant();
                out = new Decision(false, "tenant-day", Math.max(1, Duration.between(now, midnight).toSeconds()));
            }
        }
        if (tenants.size() > 50_000) tenants.clear();   // bounded memory under a hostile client-id flood
        if (users.size() > 200_000) users.clear();
        return out;
    }

    /** Current use for the admin card: calls this minute by the actor and today by the church. */
    public Map<String, Object> usage(String clientId, String actor) {
        Instant now = clock.instant();
        Window u = users.get(clientId + "|" + actor);
        Window t = tenants.get(clientId);
        long minute = now.getEpochSecond() / 60, day = now.atZone(CHURCH_ZONE).toLocalDate().toEpochDay();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("userThisMinute", u == null || u.key != minute ? 0 : u.count.get());
        m.put("userPerMinuteLimit", perUserPerMinute);
        m.put("tenantToday", t == null || t.key != day ? 0 : t.count.get());
        m.put("tenantPerDayLimit", perTenantPerDay);
        m.put("enforce", enforce);
        return m;
    }

    private static final class Window {
        final long key; final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        Window(long key) { this.key = key; }
    }
}

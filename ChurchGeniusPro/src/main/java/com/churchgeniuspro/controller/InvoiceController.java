package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.BillingService;
import com.churchgeniuspro.util.PublicFormGuard;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The secure invoice page's API ({@code /invoice.html?t=…}). Anonymous by path
 * (AuthFilter) and allowed for an ended subscription (AccountStatusFilter), because a
 * church whose subscription has ended is exactly who needs to see what it owes.
 *
 * <p>The token is the only key: the server hashes it and looks the invoice up. Every
 * refusal — unknown, superseded, expired, draft, void, or another church's session —
 * returns the same 404, and an address that keeps presenting bad tokens is slowed
 * down. Responses are never cached and never leak the URL as a referrer.
 */
@RestController
public class InvoiceController {

    static final String NOT_AVAILABLE = "This invoice link is not valid or has expired. "
            + "Please use the most recent invoice email, or contact support@churchgeniuspro.com.";
    /** Bad tokens allowed per IP within {@link #WINDOW_MS} before requests are refused. */
    static final int MAX_MISSES = 10;
    static final long WINDOW_MS = 10 * 60_000L;

    private final BillingService billing;
    private final Map<String, Deque<Long>> misses = new ConcurrentHashMap<>();

    public InvoiceController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/api/invoice/view")
    public ResponseEntity<Map<String, Object>> view(@RequestParam(required = false) String t, HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);
        if (tooManyMisses(ip)) {
            return secure(ResponseEntity.status(429)).body(error("Too many attempts. Please try again later."));
        }
        String sessionClient = null;
        if (req.getSession(false) != null) {
            String cid = RoleGuard.clientId(req);
            if (cid != null && !cid.isBlank()) sessionClient = cid;
        }
        Optional<Map<String, Object>> inv = billing.publicView(t, sessionClient);
        if (inv.isEmpty()) {
            recordMiss(ip);
            return secure(ResponseEntity.status(404)).body(error(NOT_AVAILABLE));
        }
        Map<String, Object> m = new LinkedHashMap<>(inv.get());
        m.put("status", "success");
        return secure(ResponseEntity.ok()).body(m);
    }

    private static ResponseEntity.BodyBuilder secure(ResponseEntity.BodyBuilder b) {
        return b.cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow");
    }

    private boolean tooManyMisses(String ip) {
        Deque<Long> q = misses.get(ip == null ? "" : ip);
        if (q == null) return false;
        synchronized (q) {
            long now = System.currentTimeMillis();
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            return q.size() >= MAX_MISSES;
        }
    }

    private void recordMiss(String ip) {
        if (misses.size() > 10_000) misses.clear();   // safety valve
        Deque<Long> q = misses.computeIfAbsent(ip == null ? "" : ip, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", msg);
        return m;
    }
}

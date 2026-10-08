package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PlatformSetting;
import com.churchgeniuspro.repository.PlatformSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Platform-wide settings edited by the Service Admin (serviceadminhome → Platform
 * Settings). Each key has a built-in default so nothing breaks on a database where
 * the row has never been written.
 *
 * <p><b>Support Email</b> ({@link #SUPPORT_EMAIL}) is the one inbox for everything the
 * platform sends to its own operators: Trial Request notifications, Support Tickets
 * and Demo Reminders. Only the recipient is centralised — each sender keeps its own
 * content and behaviour.
 *
 * <p>Reads are cached for a short time; {@link #set} clears the cache, so an edit is
 * live on the next send.
 */
@Service
public class PlatformSettingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingService.class);
    private static final long CACHE_TTL_MS = 30_000;

    public static final String SUPPORT_EMAIL         = "support_email";
    public static final String DEFAULT_SUPPORT_EMAIL = "support@churchgeniuspro.com";

    /** Opaque token gating the public Trial Request page; null/absent = no link issued. */
    public static final String TRIAL_REQUEST_TOKEN   = "trial_request_token";

    /** Billing reminders: email the Support Email a digest of clients due (default off). */
    public static final String BILLING_REMINDER_SUPPORT_ENABLED = "billing_reminder_support_enabled";
    /** Billing reminders: email the client its invoice automatically (default off). */
    public static final String BILLING_REMINDER_CLIENT_ENABLED  = "billing_reminder_client_enabled";

    /** A boolean setting; anything but "true" (including never written) is false. */
    public boolean isEnabled(String key) {
        return get(key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(false);
    }

    private final PlatformSettingRepository repo;
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public PlatformSettingService(PlatformSettingRepository repo) {
        this.repo = repo;
    }

    /** The configured Support Email, or the default when unset or blank. */
    public String supportEmail() {
        String v = get(SUPPORT_EMAIL).orElse(null);
        return v == null || v.isBlank() ? DEFAULT_SUPPORT_EMAIL : v.trim();
    }

    /** Raw value of a key, empty when never written. */
    public Optional<String> get(String key) {
        long now = System.currentTimeMillis();
        Object[] c = cache.get(key);
        if (c != null && (long) c[0] > now) return Optional.ofNullable((String) c[1]);
        String v = null;
        try {
            v = repo.findById(key).map(PlatformSetting::getValue).orElse(null);
        } catch (Exception e) {
            log.warn("platform_setting '{}' could not be read ({}); using default", key, e.getMessage());
        }
        cache.put(key, new Object[]{ now + CACHE_TTL_MS, v });
        return Optional.ofNullable(v);
    }

    /** Writes a key (null clears it) and drops the cache so the change is immediate. */
    public void set(String key, String value, String actor) {
        PlatformSetting s = repo.findById(key).orElseGet(PlatformSetting::new);
        s.setKey(key);
        s.setValue(value == null || value.isBlank() ? null : value.trim());
        s.setUpdatedBy(actor);
        s.setUpdatedAt(LocalDateTime.now());
        repo.save(s);
        cache.remove(key);
    }

    /** Minimal shape check for the Support Email field; null when acceptable. */
    public static String validateEmail(String email) {
        if (email == null || email.isBlank()) return null;   // blank = use the default
        String e = email.trim();
        if (e.length() > 320 || !e.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return "Enter a valid email address.";
        return null;
    }
}

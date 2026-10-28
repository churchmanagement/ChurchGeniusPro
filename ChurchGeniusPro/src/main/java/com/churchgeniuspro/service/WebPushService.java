package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PushNotificationLog;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.Security;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends Web Push (VAPID) notifications to subscribed browsers and logs every
 * delivery to {@code push_notification_log} for badge-count tracking and
 * notification history.
 *
 * <p>VAPID keys are configured in {@code application.properties}:
 * <pre>
 *   push.vapid.public-key=...
 *   push.vapid.private-key=...
 *   push.vapid.subject=mailto:admin@churchgeniuspro.com
 * </pre>
 *
 * <p>If the keys are blank/missing, the service runs in no-op mode and logs
 * a startup warning.  Run the key-generation helper once to populate the
 * properties file.
 *
 * <h3>Mobile Phase 5 — one subscription table, two transports</h3>
 * <p>Every send method is the single entry point for ALL devices of a user / org.
 * Rows with {@code platform = web} go out exactly as before (VAPID); rows the app
 * registered ({@code android} / {@code ios}) are handed to {@link NativePushService}
 * (FCM). {@link #isEnabled()} is true when either transport is configured, so the
 * schedulers keep running when only one of them is; the VAPID key endpoint asks
 * {@link #isWebPushEnabled()} specifically.
 *
 * <h3>Logging variants</h3>
 * <p>Every {@code sendToOrg} / {@code sendToUser} call has a corresponding
 * {@code logAndSend*} variant that writes a {@link PushNotificationLog} row
 * per unique user key before delivering the push.  Schedulers should use the
 * logging variants so the badge count and notification history stay accurate.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    @Value("${push.vapid.public-key:}")
    private String vapidPublicKey;

    /** Raw uncompressed EC point (65 bytes, base64url) — this is what browsers need. */
    @Value("${push.vapid.public-key-raw:}")
    private String vapidPublicKeyRaw;

    @Value("${push.vapid.private-key:}")
    private String vapidPrivateKey;

    @Value("${push.vapid.subject:mailto:admin@churchgeniuspro.com}")
    private String vapidSubject;

    private final PushSubscriptionRepository    pushSubRepo;
    private final PushNotificationLogRepository logRepo;
    private final NativePushService             nativePush;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PushService pushService;
    /** VAPID configured (browser delivery possible). */
    private boolean webEnabled = false;

    public WebPushService(PushSubscriptionRepository pushSubRepo,
                          PushNotificationLogRepository logRepo,
                          NativePushService nativePush) {
        this.pushSubRepo = pushSubRepo;
        this.logRepo     = logRepo;
        this.nativePush  = nativePush;
    }

    @PostConstruct
    public void init() {
        // Register BouncyCastle provider for EC key support
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        if (vapidPublicKey == null || vapidPublicKey.isBlank() ||
            vapidPrivateKey == null || vapidPrivateKey.isBlank()) {
            log.warn("[WebPush] VAPID keys not configured — push notifications disabled. " +
                     "Set push.vapid.public-key / push.vapid.private-key in application.properties.");
            return;
        }

        try {
            pushService = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
            webEnabled = true;
            log.info("[WebPush] Initialized — push notifications enabled.");
        } catch (Exception e) {
            log.error("[WebPush] Failed to initialize PushService: {}", e.getMessage(), e);
        }
    }

    // ── Public API — send only (no log) ──────────────────────────────────────

    /** Returns the SubjectPublicKeyInfo DER key (used internally by web-push library). */
    public String getVapidPublicKey() {
        return vapidPublicKey;
    }

    /**
     * Returns the raw uncompressed EC point (base64url, 65 bytes).
     * This is what the browser's {@code pushManager.subscribe()} needs as
     * {@code applicationServerKey}.
     */
    public String getVapidPublicKeyRaw() {
        return (vapidPublicKeyRaw != null && !vapidPublicKeyRaw.isBlank())
               ? vapidPublicKeyRaw : vapidPublicKey;
    }

    /** True when at least one transport (VAPID browsers, FCM app) can deliver. */
    public boolean isEnabled() {
        return webEnabled || (nativePush != null && nativePush.isEnabled());
    }

    /** True only when VAPID is configured — what browsers need before subscribing. */
    public boolean isWebPushEnabled() {
        return webEnabled;
    }

    /** True only when the FCM service account is configured. */
    public boolean isNativePushEnabled() {
        return nativePush != null && nativePush.isEnabled();
    }

    /**
     * Sends a push to every active subscription for the given user key.
     * Does NOT write a log row — use {@link #logAndSendToUser} from schedulers.
     */
    public void sendToUser(String userKey, String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> subs = pushSubRepo.findByUserKeyAndActiveTrue(userKey);
        sendToSubscriptions(subs, title, body, url, tag);
    }

    /**
     * Sends a push to ALL active subscriptions for a given org and user type.
     * Does NOT write log rows — use {@link #logAndSendToOrgByType} from schedulers.
     */
    public void sendToOrgByType(String appClientId, String userType,
                                String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> subs = pushSubRepo
                .findByAppClientIdAndUserTypeAndActiveTrue(appClientId, userType);
        sendToSubscriptions(subs, title, body, url, tag);
    }

    /**
     * Sends a push to ALL active subscriptions for a given org (staff + members).
     * Does NOT write log rows — use {@link #logAndSendToOrg} from schedulers.
     */
    public void sendToOrg(String appClientId,
                          String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> subs = pushSubRepo.findByAppClientIdAndActiveTrue(appClientId);
        sendToSubscriptions(subs, title, body, url, tag);
    }

    // ── Public API — log + send (use from schedulers) ────────────────────────

    /**
     * Logs the notification for the user, then sends the push to all their
     * active subscriptions.  Badge count and notification history will reflect
     * this push immediately.
     */
    public void logAndSendToUser(String userKey, String appClientId, String userType,
                                 String title, String body, String url, String tag) {
        writeLog(userKey, appClientId, userType, title, body, url, tag);
        sendToUser(userKey, title, body, url, tag);
    }

    /**
     * Like {@link #logAndSendToUser} but the push is sent ONLY when the log row was written —
     * i.e. at most once per user, tag and 23-hour window. For polling schedulers whose
     * condition stays true until the user acts (unread messages): without this gate the
     * 60-second poll re-pushed the same notification every minute to every device
     * (data-integrity audit 2026-10-28, H4).
     *
     * @return true when a push was sent, false when the tag was already logged recently
     */
    public boolean logAndSendToUserOnce(String userKey, String appClientId, String userType,
                                        String title, String body, String url, String tag) {
        if (!writeLog(userKey, appClientId, userType, title, body, url, tag)) return false;
        sendToUser(userKey, title, body, url, tag);
        return true;
    }

    /**
     * Logs one row per unique user key for the given org + userType, then
     * sends the push to all their subscriptions.
     */
    public void logAndSendToOrgByType(String appClientId, String userType,
                                      String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> subs = pushSubRepo
                .findByAppClientIdAndUserTypeAndActiveTrue(appClientId, userType);
        // Log once per unique user (not per device)
        subs.stream()
            .map(PushSubscription::getUserKey)
            .collect(Collectors.toSet())
            .forEach(uk -> writeLog(uk, appClientId, userType, title, body, url, tag));
        sendToSubscriptions(subs, title, body, url, tag);
    }

    /**
     * Logs one row per unique user key for the given org (all user types),
     * then sends the push to all their subscriptions.
     */
    public void logAndSendToOrg(String appClientId,
                                String title, String body, String url, String tag) {
        if (!isEnabled()) return;
        List<PushSubscription> subs = pushSubRepo.findByAppClientIdAndActiveTrue(appClientId);
        // Log once per unique user (not per device)
        subs.stream()
            .collect(Collectors.toMap(
                PushSubscription::getUserKey,
                s -> s,
                (a, b) -> a   // keep first if duplicate
            ))
            .values()
            .forEach(s -> writeLog(s.getUserKey(), appClientId, s.getUserType(), title, body, url, tag));
        sendToSubscriptions(subs, title, body, url, tag);
    }

    // ── Key generation helper (operator use) ──────────────────────────────────

    /**
     * Generates a new VAPID EC key pair on the P-256 curve (BouncyCastle) and RETURNS the
     * Base64url-encoded values ({@code publicKey}, {@code publicKeyRaw}, {@code privateKey})
     * for the caller to put into the environment. Nothing in the app calls this; it used to
     * write the private key through the logger, which no credential should ever reach, masked
     * or not (2026-10-28 logging audit M5). Only the fact of generation is logged.
     */
    public static Map<String, String> generateVapidKeys() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            java.security.KeyPairGenerator gen =
                java.security.KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            gen.initialize(new java.security.spec.ECGenParameterSpec("prime256v1"));
            KeyPair keyPair = gen.generateKeyPair();

            String pub  = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(keyPair.getPublic().getEncoded());
            String priv = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(keyPair.getPrivate().getEncoded());
            // Raw uncompressed EC point for browser applicationServerKey
            org.bouncycastle.jce.interfaces.ECPublicKey ecPub =
                (org.bouncycastle.jce.interfaces.ECPublicKey) keyPair.getPublic();
            byte[] rawPoint = ecPub.getQ().getEncoded(false); // uncompressed
            String pubRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPoint);

            log.info("[WebPush] VAPID key pair generated (values returned to the caller, not logged)");
            return Map.of("publicKey", pub, "publicKeyRaw", pubRaw, "privateKey", priv);
        } catch (Exception e) {
            log.error("[WebPush] Key generation failed: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** @return true when a row was written, false when the same tag was logged within 23 h (or on error). */
    boolean writeLog(String userKey, String appClientId, String userType,
                     String title, String body, String url, String tag) {
        try {
            // Dedup guard: skip if we already logged the exact same tag for this user
            // within the last 23 hours (covers daily digest re-runs, cluster restarts,
            // and the two-scheduler overlap where both PushNotificationScheduler and
            // ReminderSchedulerService can fire for the same meeting on the same day).
            if (tag != null) {
                Instant cutoff = Instant.now().minusSeconds(23 * 3600);
                long recent = logRepo.countRecentByTag(userKey, tag, cutoff);
                if (recent > 0) {
                    log.debug("[WebPush] Skipping duplicate log entry for userKey={} tag={}", userKey, tag);
                    return false;
                }
            }
            PushNotificationLog entry = new PushNotificationLog();
            entry.setUserKey(userKey);
            entry.setAppClientId(appClientId);
            entry.setUserType(userType);
            entry.setTitle(title);
            entry.setBody(body);
            entry.setUrl(url);
            entry.setTag(tag);
            entry.setSentAt(Instant.now());
            logRepo.save(entry);
            return true;
        } catch (Exception e) {
            log.warn("[WebPush] Failed to write notification log: {}", e.getMessage());
            return false;
        }
    }

    /** Mobile Phase 5: app rows go through FCM; an unregistered token deactivates the row. */
    private void deliverNative(PushSubscription sub, String title, String body, String url, String tag) {
        if (nativePush == null) return;
        NativePushService.Result r = nativePush.send(sub, title, body, url, tag);
        if (r == NativePushService.Result.UNREGISTERED) {
            try { pushSubRepo.deactivateByEndpoint(sub.getEndpoint()); } catch (Exception ignored) { /* best effort */ }
        }
    }

    private void sendToSubscriptions(List<PushSubscription> subs,
                                     String title, String body, String url, String tag) {
        if (subs == null || subs.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "title",  title,
                "body",   body,
                "url",    url   != null ? url  : "/",
                "tag",    tag   != null ? tag  : "cgp-push",
                "icon",   "/logo.png",
                "badge",  "/badge.png"
            ));
        } catch (Exception e) {
            log.warn("[WebPush] Failed to serialize payload: {}", e.getMessage());
            return;
        }

        for (PushSubscription sub : subs) {
            if (sub.isNative()) {
                deliverNative(sub, title, body, url, tag);
                continue;
            }
            if (!webEnabled) continue;   // VAPID not configured: browser rows are skipped, app rows still go
            try {
                Notification notification = new Notification(
                    sub.getEndpoint(),
                    sub.getP256dh(),
                    sub.getAuth(),
                    payload
                );
                pushService.send(notification);
                log.debug("[WebPush] Sent to endpoint {}", sub.getEndpoint().substring(0, Math.min(40, sub.getEndpoint().length())));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.contains("410") || msg.contains("Gone") || msg.contains("404")) {
                    // Subscription expired — deactivate it
                    pushSubRepo.deactivateByEndpoint(sub.getEndpoint());
                    log.info("[WebPush] Deactivated expired subscription id={}", sub.getId());
                } else {
                    log.warn("[WebPush] Failed to deliver to sub id={}: {}", sub.getId(), msg);
                }
            }
        }
    }
}

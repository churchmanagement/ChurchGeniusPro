package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.entity.PlaidWebhookEvent;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidWebhookEventRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives, verifies, records, and dispatches Plaid webhooks.
 *
 * <p>Security: each webhook is authenticated by verifying the {@code Plaid-Verification}
 * JWT (ES256) against Plaid's rotating signing key, confirming the request body's
 * SHA-256 matches the signed claim, and rejecting stale tokens. Unverified events
 * are recorded but never processed.
 *
 * <p>Processing is asynchronous: the controller returns 200 immediately and the
 * actual sync runs on a background executor so Plaid is not kept waiting.
 */
@Service
public class PlaidWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PlaidWebhookService.class);
    private static final long MAX_AGE_SECONDS = 300; // reject tokens older than 5 min

    private final PlaidClient client;
    private final PlaidItemRepository itemRepo;
    private final PlaidWebhookEventRepository eventRepo;
    private final PlaidSyncService syncService;
    private final PlaidGuard guard;
    private final PlaidAuditService audit;
    // Self-contained mapper (the app does not expose an ObjectMapper bean).
    private final ObjectMapper mapper = new ObjectMapper();

    /** Cache of verification keys by kid (Plaid rotates them; evicted on verify failure). */
    private final Map<String, JsonWebKey> keyCache = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plaid-webhook-worker");
        t.setDaemon(true);
        return t;
    });

    public PlaidWebhookService(PlaidClient client,
                               PlaidItemRepository itemRepo,
                               PlaidWebhookEventRepository eventRepo,
                               PlaidSyncService syncService,
                               PlaidGuard guard,
                               PlaidAuditService audit) {
        this.client = client;
        this.itemRepo = itemRepo;
        this.eventRepo = eventRepo;
        this.syncService = syncService;
        this.guard = guard;
        this.audit = audit;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
    }

    /** Entry point from the controller. Fast and never throws to the caller. */
    public void handle(String verificationJwt, String rawBody) {
        String type = null, code = null, itemId = null, errorCode = null;
        try {
            JsonNode root = mapper.readTree(rawBody == null ? "{}" : rawBody);
            type = text(root, "webhook_type");
            code = text(root, "webhook_code");
            itemId = text(root, "item_id");
            if (root.has("error") && root.get("error").has("error_code")) {
                errorCode = root.get("error").get("error_code").asText(null);
            }
        } catch (Exception e) {
            log.warn("Plaid webhook: unparseable body");
        }

        boolean valid = verify(verificationJwt, rawBody);

        // Record every receipt (audit + idempotency), signature result included.
        PlaidWebhookEvent event = new PlaidWebhookEvent();
        event.setItemId(itemId);
        event.setWebhookType(type);
        event.setWebhookCode(code);
        event.setPayload(truncate(rawBody, 8000));
        event.setSignatureValid(valid);
        event.setProcessed(false);

        String clientIdForAudit = null;
        PlaidItem item = itemId != null ? itemRepo.findByItemId(itemId).orElse(null) : null;
        if (item != null) clientIdForAudit = item.getClientId();

        if (!valid) {
            eventRepo.save(event);
            audit.record(clientIdForAudit, "WEBHOOK", "WEBHOOK_RECEIVED", itemId,
                    type + "/" + code + " [INVALID SIGNATURE — ignored]");
            log.warn("Plaid webhook rejected (invalid signature): type={} code={}", type, code);
            return;
        }

        try {
            dispatch(item, type, code, errorCode);
            event.setProcessed(true);
        } catch (Exception e) {
            log.warn("Plaid webhook dispatch failed for {}/{}: {}", type, code, e.getMessage());
        }
        eventRepo.save(event);
        audit.record(clientIdForAudit, "WEBHOOK", "WEBHOOK_RECEIVED", itemId, type + "/" + code);
    }

    // ── Dispatch ───────────────────────────────────────────────────────────

    private void dispatch(PlaidItem item, String type, String code, String errorCode) {
        if (item == null || type == null) return;
        switch (type) {
            case "TRANSACTIONS" -> {
                // SYNC_UPDATES_AVAILABLE is the primary path; legacy update codes also trigger a sync.
                enqueueSync(item.getId(), item.getClientId());
            }
            case "ITEM" -> handleItemEvent(item, code, errorCode);
            default -> log.debug("Plaid webhook: unhandled type {}", type);
        }
    }

    private void handleItemEvent(PlaidItem item, String code, String errorCode) {
        if (code == null) return;
        switch (code) {
            case "ERROR" -> {
                item.setErrorCode(errorCode);
                item.setStatus("ITEM_LOGIN_REQUIRED".equals(errorCode) ? "LOGIN_REQUIRED" : "ERROR");
                itemRepo.save(item);
            }
            case "PENDING_EXPIRATION" -> { item.setStatus("DEGRADED"); itemRepo.save(item); }
            case "USER_PERMISSION_REVOKED", "USER_ACCOUNT_REVOKED" -> {
                item.setStatus("DISCONNECTED");
                itemRepo.save(item);
            }
            case "WEBHOOK_UPDATE_ACKNOWLEDGED" -> log.debug("Plaid webhook URL update acknowledged for item {}", item.getItemId());
            default -> log.debug("Plaid webhook: unhandled ITEM code {}", code);
        }
    }

    private void enqueueSync(Integer itemPk, String clientId) {
        if (!guard.isSyncEnabled(clientId)) {
            log.debug("Plaid sync disabled for client {} — webhook acknowledged, no sync run", clientId);
            return;
        }
        worker.submit(() -> {
            try {
                PlaidItem fresh = itemRepo.findById(itemPk).orElse(null);
                if (fresh != null && !fresh.isDeleteFlag()) {
                    syncService.sync(fresh, "WEBHOOK");
                }
            } catch (Exception e) {
                log.warn("Background webhook sync failed for item {}: {}", itemPk, e.getMessage());
            }
        });
    }

    // ── Verification ─────────────────────────────────────────────────────────

    private boolean verify(String token, String rawBody) {
        if (token == null || token.isBlank() || rawBody == null) return false;
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(token);
            if (!AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256.equals(jws.getAlgorithmHeaderValue())) {
                return false; // only ES256 is accepted
            }
            String kid = jws.getKeyIdHeaderValue();
            if (kid == null) return false;

            boolean sigOk = verifyWithKey(jws, kid, false);
            if (!sigOk) sigOk = verifyWithKey(jws, kid, true); // refresh key once and retry
            if (!sigOk) return false;

            // Body integrity + freshness from the signed claims.
            JsonNode claims = mapper.readTree(jws.getPayload());
            String expectedHash = text(claims, "request_body_sha256");
            if (expectedHash == null) return false;
            if (!constantTimeEquals(expectedHash, sha256Hex(rawBody))) return false;

            if (claims.has("iat")) {
                long iat = claims.get("iat").asLong();
                if (Math.abs(Instant.now().getEpochSecond() - iat) > MAX_AGE_SECONDS) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Plaid webhook verification error: {}", e.getMessage());
            return false;
        }
    }

    private boolean verifyWithKey(JsonWebSignature jws, String kid, boolean forceRefresh) {
        try {
            JsonWebKey jwk = resolveKey(kid, forceRefresh);
            if (jwk == null) return false;
            jws.setKey(jwk.getKey());
            jws.setAlgorithmConstraints(new AlgorithmConstraints(
                    AlgorithmConstraints.ConstraintType.PERMIT,
                    AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256));
            return jws.verifySignature();
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private JsonWebKey resolveKey(String kid, boolean forceRefresh) throws Exception {
        if (!forceRefresh && keyCache.containsKey(kid)) return keyCache.get(kid);
        keyCache.remove(kid);
        Map<String, Object> resp = client.webhookVerificationKeyGet(kid);
        Object keyObj = resp.get("key");
        if (!(keyObj instanceof Map)) return null;
        JsonWebKey jwk = JsonWebKey.Factory.newJwk((Map<String, Object>) keyObj);
        keyCache.put(kid, jwk);
        return jwk;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String sha256Hex(String body) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}

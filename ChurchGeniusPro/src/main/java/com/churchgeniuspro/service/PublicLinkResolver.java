package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The single authority for "which church does this public link belong to".
 *
 * <p>A public link is a {@link PublicScreenLink} row. Its {@code token} is 32
 * random bytes — it encodes nothing, so it cannot be forged, guessed, or reused
 * for a different page; it can only be looked up. Revoking the row, or letting it
 * expire, ends every URL that carried it. Before this class was the authority,
 * tokens were AES-ECB of {@code clientId|page} under a key shipped in every JAR,
 * and half the public endpoints decrypted them without consulting the database at
 * all — so nothing could be revoked and anyone with the JAR could mint a link for
 * any church.
 *
 * <p>Only one shape exists now. There is no "bare encrypted client id" fallback.
 */
@Component
public class PublicLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(PublicLinkResolver.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PublicScreenLinkRepository linkRepo;
    private final PublicPagePolicy policy;

    public PublicLinkResolver(PublicScreenLinkRepository linkRepo, PublicPagePolicy policy) {
        this.linkRepo = linkRepo;
        this.policy = policy;
    }

    /** 32 random bytes, URL-safe base64 without padding (43 chars). */
    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** True when the link may still be used today. */
    public static boolean isActive(PublicScreenLink l) {
        if (l == null || l.isRevoked()) return false;
        LocalDate exp = l.getExpirationDate();
        return exp == null || !exp.isBefore(LocalDate.now());
    }

    /**
     * @param param   the {@code ?c=} / {@code ?cid=} / {@code ?token=} value from the public URL
     * @param pageUrl the page this parameter is expected to unlock, e.g. {@code "/connect"}
     * @return the tenant's appClientId, or {@code null} if access is refused
     */
    public String resolveClientId(String param, String pageUrl) {
        return resolve(param, pageUrl).map(PublicScreenLink::getAppClientId).orElse(null);
    }

    /**
     * Like {@link #resolveClientId}, but accepts a token minted for ANY of several
     * pages, tried in order — for pages whose public experiences have been merged
     * or are commonly reached from one another (e.g. the Upcoming Events list and
     * the public Event Calendar; see {@code PublicPagePolicy.UPCOMING_EVENTS_FAMILY}).
     * A token is still bound to exactly the one page it was minted for — this does
     * not relax that — it just lets a caller say "any of these page identities is
     * fine here." Each candidate goes through the same {@link #resolve} checks
     * (revocation, expiry, policy) as a direct call would.
     */
    public String resolveClientIdAnyOf(String param, List<String> pageUrls) {
        if (pageUrls == null) return null;
        for (String pageUrl : pageUrls) {
            String clientId = resolveClientId(param, pageUrl);
            if (clientId != null) return clientId;
        }
        return null;
    }

    /** The live link row for this parameter and page, or empty when access is refused. */
    public Optional<PublicScreenLink> resolve(String param, String pageUrl) {
        if (param == null || param.isBlank() || pageUrl == null) return Optional.empty();
        PublicScreenLink l = linkRepo.findByToken(param.trim()).orElse(null);
        if (l == null) return Optional.empty();
        if (!isActive(l)) {
            log.debug("Public link refused: token for {} is revoked or expired", pageUrl);
            return Optional.empty();
        }
        // A token is minted for exactly one page; a Donation token must not open /connect.
        if (!pageUrl.equals(l.getPageUrl())) {
            log.debug("Public link refused: token is for {} not {}", l.getPageUrl(), pageUrl);
            return Optional.empty();
        }
        // A page the church may no longer publish (plan change, trial) stops resolving
        // even for links minted earlier — a URL in the wild must not outlive the rule.
        String denial = policy.denialReason(pageUrl, l.getAppClientId());
        if (denial != null) {
            log.info("Public link refused at resolution — page={} tenant={} reason={}", pageUrl, l.getAppClientId(), denial);
            return Optional.empty();
        }
        return Optional.of(l);
    }

    /** The newest live link this church has for the page, if any. Never creates one. */
    public Optional<PublicScreenLink> activeLink(String clientId, String pageUrl) {
        if (clientId == null || pageUrl == null) return Optional.empty();
        List<PublicScreenLink> rows =
                linkRepo.findByAppClientIdAndPageUrlAndRevokedFalseOrderByCreatedDateDesc(clientId, pageUrl);
        return rows.stream().filter(PublicLinkResolver::isActive).findFirst();
    }

    /**
     * The church's live link for the page, creating one when none exists — for
     * code that needs a public URL on the church's behalf (NTAG landing buttons,
     * reminder emails, the kids check-in QR). Returns empty when the policy says
     * this church may not publish this page; callers then omit the link.
     */
    public Optional<PublicScreenLink> ensureLink(String clientId, String pageUrl, String label) {
        if (clientId == null || clientId.isBlank() || pageUrl == null) return Optional.empty();
        // The policy first, THEN the existing row. The other order handed a trial
        // tenant the URL of a link it had from before the restriction applied — a URL
        // that resolve() then refuses, so the church was given an address that leads
        // to the login page and no reason why.
        if (policy.denialReason(pageUrl, clientId) != null) return Optional.empty();
        Optional<PublicScreenLink> live = activeLink(clientId, pageUrl);
        if (live.isPresent()) return live;
        PublicScreenLink l = new PublicScreenLink();
        l.setAppClientId(clientId);
        l.setPageUrl(pageUrl);
        l.setPageLabel(label != null ? label : pageUrl);
        l.setToken(newToken());
        l.setRevoked(false);
        l.setShowDeclaration(false);
        return Optional.of(linkRepo.save(l));
    }
}

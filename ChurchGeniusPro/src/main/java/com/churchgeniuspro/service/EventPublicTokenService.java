package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.repository.ChurchEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The public identity of an event in URLs: a random token stored on the row.
 * Every "/event-register/{token}" link — registration emails, reminders, the
 * Details link in SMS — goes through here, and every public handler resolves the
 * token back through {@link #resolve}. Nothing is derived from the numeric id.
 */
@Service
public class EventPublicTokenService {

    private final ChurchEventRepository eventRepo;

    public EventPublicTokenService(ChurchEventRepository eventRepo) {
        this.eventRepo = eventRepo;
    }

    /**
     * The event's token, assigning and persisting one for rows created before tokens
     * existed. {@code REQUIRES_NEW}: callers include {@code getAll}/{@code getById}
     * (read-only transactions, e.g. the Events list and the public image endpoint's
     * lookup chain), where Spring/Hibernate suppresses flushing — a save that just
     * joined that transaction would be silently discarded, so the backfilled token
     * would never actually reach the database. The event's image and "Register Page"
     * link would then look broken: the list hands the browser a fresh in-memory token,
     * but the very next request for it (e.g. GET .../image) resolves against the real
     * table, where no row has ever been saved with that token, so it 404s. Committing
     * this one column in its own transaction guarantees it persists regardless of the
     * caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String tokenFor(ChurchEvent ev) {
        if (ev == null) return null;
        if (ev.getPublicToken() == null || ev.getPublicToken().isBlank()) {
            ev.setPublicToken(PublicLinkResolver.newToken());
            eventRepo.save(ev);
        }
        return ev.getPublicToken();
    }

    /** The live event behind a public token, or empty. Unknown and deleted look identical. */
    @Transactional(readOnly = true)
    public Optional<ChurchEvent> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return eventRepo.findByPublicTokenAndDeleteFlagFalse(token.trim());
    }
}

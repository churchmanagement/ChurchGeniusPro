package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;

import java.time.LocalDateTime;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Emulates the repository's three conditional UPDATEs against in-memory rows, with
 * the same WHERE clauses, so the service's claim/complete/release logic is tested
 * against the rule the database will apply.
 */
final class FakeLinkUpdates {

    private FakeLinkUpdates() {}

    static void install(TrialRegistrationLinkRepository repo, Function<String, TrialRegistrationLink> byToken) {
        when(repo.claim(anyString(), any(LocalDateTime.class), anyString())).thenAnswer(i -> {
            TrialRegistrationLink l = byToken.apply(i.getArgument(0));
            LocalDateTime now = i.getArgument(1);
            synchronized (FakeLinkUpdates.class) {
                if (l == null || l.getUsedAt() != null || Boolean.TRUE.equals(l.getRevoked())
                        || l.getDeletedAt() != null
                        || (l.getExpiresAt() != null && !l.getExpiresAt().isAfter(now))) return 0;
                l.setUsedAt(now);
                l.setUsedClientId(i.getArgument(2));
                return 1;
            }
        });
        when(repo.complete(anyString(), anyString(), anyString())).thenAnswer(i -> {
            TrialRegistrationLink l = byToken.apply(i.getArgument(0));
            synchronized (FakeLinkUpdates.class) {
                if (l == null || !i.getArgument(1).equals(l.getUsedClientId())) return 0;
                l.setUsedClientId(i.getArgument(2));
                return 1;
            }
        });
        when(repo.release(anyString(), anyString())).thenAnswer(i -> {
            TrialRegistrationLink l = byToken.apply(i.getArgument(0));
            synchronized (FakeLinkUpdates.class) {
                if (l == null || !i.getArgument(1).equals(l.getUsedClientId())) return 0;
                l.setUsedAt(null);
                l.setUsedClientId(null);
                return 1;
            }
        });
    }
}

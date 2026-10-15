package com.churchgeniuspro.security;

import com.churchgeniuspro.hibernate.RememberMeToken;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.RememberMeTokenRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito-backed in-memory stand-ins for {@link RememberMeTokenRepository} and the
 * two {@link LoginRepository} methods {@code RememberMeService} uses, so the service
 * and the login endpoints can be exercised end to end without a database.
 */
final class InMemoryRememberMeTokens {

    final Map<Long, RememberMeToken> rows   = new HashMap<>();
    final Map<Integer, SignUp>       logins = new HashMap<>();
    final AtomicLong                 ids    = new AtomicLong();

    final RememberMeTokenRepository tokens = mock(RememberMeTokenRepository.class);
    final LoginRepository           loginRepo;

    InMemoryRememberMeTokens(LoginRepository loginRepo) {
        this.loginRepo = loginRepo;

        when(tokens.save(any())).thenAnswer(inv -> {
            RememberMeToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(ids.incrementAndGet());
            rows.put(t.getId(), t);
            return t;
        });
        when(tokens.findByTokenHash(anyString())).thenAnswer(inv ->
                rows.values().stream().filter(t -> inv.getArgument(0).equals(t.getTokenHash())).findFirst());
        when(tokens.findByPrevTokenHash(anyString())).thenAnswer(inv ->
                rows.values().stream().filter(t -> inv.getArgument(0).equals(t.getPrevTokenHash())).findFirst());
        when(tokens.findActiveBySignupId(anyInt(), any())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0); Instant now = inv.getArgument(1);
            List<RememberMeToken> out = new ArrayList<>();
            for (RememberMeToken t : rows.values())
                if (id.equals(t.getSignupId()) && t.getRevokedAt() == null && t.getExpiresAt().isAfter(now)) out.add(t);
            out.sort(Comparator.comparing(RememberMeToken::getCreatedAt));
            return out;
        });
        when(tokens.revokeAllBySignupId(anyInt(), any())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0); Instant now = inv.getArgument(1); int n = 0;
            for (RememberMeToken t : rows.values())
                if (id.equals(t.getSignupId()) && t.getRevokedAt() == null) { t.setRevokedAt(now); n++; }
            return n;
        });
        when(tokens.deleteDeadBefore(any())).thenAnswer(inv -> {
            Instant before = inv.getArgument(0);
            List<Long> dead = new ArrayList<>();
            for (RememberMeToken t : rows.values())
                if ((t.getRevokedAt() != null && t.getRevokedAt().isBefore(before)) || t.getExpiresAt().isBefore(before)) dead.add(t.getId());
            dead.forEach(rows::remove);
            return dead.size();
        });

        when(loginRepo.findById(anyInt())).thenAnswer(inv -> Optional.ofNullable(logins.get((Integer) inv.getArgument(0))));
        when(loginRepo.findByRememberToken(anyString())).thenAnswer(inv ->
                logins.values().stream().filter(u -> inv.getArgument(0).equals(u.getRemember())).findFirst());
        when(loginRepo.save(any(SignUp.class))).thenAnswer(inv -> {
            SignUp u = inv.getArgument(0);
            if (u.getId() != null) logins.put(u.getId(), u);
            return u;
        });
    }

    SignUp signup(int id, String username, String clientId) {
        SignUp s = new SignUp();
        s.setId(id);
        s.setUsername(username);
        s.setClientId(clientId);
        s.setActive(true);
        s.setDeleted(false);
        logins.put(id, s);
        return s;
    }

    long active(int signupId) {
        Instant now = Instant.now();
        return rows.values().stream().filter(t -> t.getSignupId() == signupId && t.isUsable(now)).count();
    }
}

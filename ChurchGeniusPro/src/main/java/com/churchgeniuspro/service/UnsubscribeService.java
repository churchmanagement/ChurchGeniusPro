package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.EmailUnsubscribe;
import com.churchgeniuspro.repository.EmailUnsubscribeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages the per-organization email unsubscribe list.
 * All email lookups normalise the address to lowercase.
 */
@Service
public class UnsubscribeService {

    private final EmailUnsubscribeRepository repo;

    public UnsubscribeService(EmailUnsubscribeRepository repo) {
        this.repo = repo;
    }

    /** Returns {@code true} if the given email has opted out for this organization. */
    public boolean isUnsubscribed(String email, String clientId) {
        if (email == null || clientId == null) return false;
        return repo.existsByEmailAndClientId(email.toLowerCase().trim(), clientId);
    }

    /**
     * Records an unsubscribe. Returns {@code false} if already on the list
     * (idempotent — caller can still show a success message).
     */
    @Transactional
    public boolean unsubscribe(String email, String clientId,
                               String firstName, String lastName) {
        String norm = email.toLowerCase().trim();
        if (repo.existsByEmailAndClientId(norm, clientId)) return false;
        EmailUnsubscribe u = new EmailUnsubscribe();
        u.setEmail(norm);
        u.setClientId(clientId);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        repo.save(u);
        return true;
    }

    /** Removes the unsubscribe record so the address will receive future emails. */
    @Transactional
    public void resubscribe(Integer id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("Unsubscribe record not found: " + id);
        }
        repo.deleteById(id);
    }

    /** Returns all unsubscribed addresses for an organization, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUnsubscribed(String clientId) {
        return repo.findByClientIdOrderByUnsubscribedAtDesc(clientId).stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             u.getId());
                    m.put("email",          u.getEmail());
                    m.put("firstName",      u.getFirstName());
                    m.put("lastName",       u.getLastName());
                    m.put("unsubscribedAt", u.getUnsubscribedAt() != null
                            ? u.getUnsubscribedAt().toLocalDate().toString() : "");
                    return m;
                })
                .collect(Collectors.toList());
    }
}

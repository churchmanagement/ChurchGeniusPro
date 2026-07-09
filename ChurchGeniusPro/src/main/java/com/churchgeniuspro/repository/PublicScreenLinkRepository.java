package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PublicScreenLinkRepository extends JpaRepository<PublicScreenLink, Integer> {

    List<PublicScreenLink> findByAppClientIdAndRevokedFalseOrderByCreatedDateDesc(String appClientId);

    Optional<PublicScreenLink> findByToken(String token);

    /** Find an active donation link for a church (pageUrl starts with /donate). */
    List<PublicScreenLink> findByAppClientIdAndPageUrlStartingWithAndRevokedFalse(
            String appClientId, String pageUrlPrefix);

    /** Active links for a church to a specific page (e.g. /membershipForm), newest first. */
    List<PublicScreenLink> findByAppClientIdAndPageUrlAndRevokedFalseOrderByCreatedDateDesc(
            String appClientId, String pageUrl);
}

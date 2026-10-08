package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface TrialRegistrationLinkRepository extends JpaRepository<TrialRegistrationLink, Integer> {

    Optional<TrialRegistrationLink> findByToken(String token);

    /** The link a provisioned tenant was registered through (Phase C: request ↔ tenant). */
    Optional<TrialRegistrationLink> findFirstByUsedClientId(String usedClientId);

    List<TrialRegistrationLink> findAllByOrderByIdDesc();

    /** The links the admin screen lists — everything not soft-deleted. */
    List<TrialRegistrationLink> findByDeletedAtIsNullOrderByIdDesc();

    /** What the "Show deleted" toggle reveals. */
    List<TrialRegistrationLink> findByDeletedAtIsNotNullOrderByIdDesc();

    /**
     * Still-open links for one prospect — the ones a replacement must revoke.
     *
     * <p>Only unused and unrevoked rows; an expired one is already unusable and is
     * left alone so the admin list still shows what happened to it.
     */
    List<TrialRegistrationLink> findByProspectEmailIgnoreCaseAndUsedAtIsNullAndRevokedFalse(String prospectEmail);

    /**
     * Soft-deletes the named links, and only the ones still listed.
     *
     * <p>A conditional UPDATE rather than a read-then-save: the count that comes
     * back is what actually changed, so re-deleting an already-deleted row reports
     * 0 instead of claiming a deletion that did not happen.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.deletedAt = :now, l.deletedBy = :by "
         + " WHERE l.id IN :ids AND l.deletedAt IS NULL")
    int softDelete(@Param("ids") java.util.Collection<Integer> ids,
                   @Param("now") LocalDateTime now, @Param("by") String by);

    /** Soft-deletes every link still listed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.deletedAt = :now, l.deletedBy = :by "
         + " WHERE l.deletedAt IS NULL")
    int softDeleteAll(@Param("now") LocalDateTime now, @Param("by") String by);

    /** Brings soft-deleted links back into the list. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.deletedAt = NULL, l.deletedBy = NULL "
         + " WHERE l.id IN :ids AND l.deletedAt IS NOT NULL")
    int restore(@Param("ids") java.util.Collection<Integer> ids);

    /** Removes the named links outright. Nothing references this table. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TrialRegistrationLink l WHERE l.id IN :ids")
    int permanentDelete(@Param("ids") java.util.Collection<Integer> ids);

    /** Removes every link outright. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TrialRegistrationLink l")
    int permanentDeleteAll();

    /**
     * Atomically takes the link: one UPDATE whose WHERE clause is the whole
     * "still usable" rule, so of any number of concurrent callers exactly one gets
     * a row count of 1 and everyone else gets 0. This — not a read followed by a
     * save — is what makes a link single-use under concurrency.
     *
     * @param marker a per-claim value written into {@code used_client_id} so the
     *               claim can later be completed or released by its owner only
     * @return 1 when this call took the link, 0 when it was already used, revoked,
     *         expired or unknown
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.usedAt = :now, l.usedClientId = :marker "
         + " WHERE l.token = :token AND l.usedAt IS NULL AND l.revoked = false "
         + "   AND l.deletedAt IS NULL "
         + "   AND (l.expiresAt IS NULL OR l.expiresAt > :now)")
    int claim(@Param("token") String token, @Param("now") LocalDateTime now, @Param("marker") String marker);

    /** Records the tenant a claimed link produced. Only the claim's own marker may be replaced. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.usedClientId = :clientId "
         + " WHERE l.token = :token AND l.usedClientId = :marker")
    int complete(@Param("token") String token, @Param("marker") String marker, @Param("clientId") String clientId);

    /** Hands a claimed link back when provisioning failed. Only the claim's own marker may be undone. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TrialRegistrationLink l SET l.usedAt = NULL, l.usedClientId = NULL "
         + " WHERE l.token = :token AND l.usedClientId = :marker")
    int release(@Param("token") String token, @Param("marker") String marker);
}

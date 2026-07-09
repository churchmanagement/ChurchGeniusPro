package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    /** All favorites for one account, in display order. */
    List<UserFavorite> findByClientIdAndUserRefOrderBySortOrderAscIdAsc(String clientId, String userRef);

    Optional<UserFavorite> findByClientIdAndUserRefAndPageId(String clientId, String userRef, String pageId);

    boolean existsByClientIdAndUserRefAndPageId(String clientId, String userRef, String pageId);

    @Transactional
    void deleteByClientIdAndUserRefAndPageId(String clientId, String userRef, String pageId);

    /** Number of existing favorites for the account (used to append new ones at the end). */
    long countByClientIdAndUserRef(String clientId, String userRef);
}

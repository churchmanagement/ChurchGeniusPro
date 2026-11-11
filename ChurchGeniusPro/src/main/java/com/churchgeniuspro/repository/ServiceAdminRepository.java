package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ServiceAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServiceAdminRepository extends JpaRepository<ServiceAdmin, Integer> {

    /** Case-insensitive username lookup for service-admin login. */
    @Query("SELECT s FROM ServiceAdmin s WHERE UPPER(s.username) = UPPER(:username) AND s.deleted = false")
    Optional<ServiceAdmin> findByUsernameAndDeletedFalse(@Param("username") String username);

    /** Credential version of a live service admin (null when gone or deleted). */
    @Query("SELECT s.credentialVersion FROM ServiceAdmin s WHERE s.id = :id AND s.deleted = false")
    Integer findLiveCredentialVersion(@Param("id") Integer id);
}

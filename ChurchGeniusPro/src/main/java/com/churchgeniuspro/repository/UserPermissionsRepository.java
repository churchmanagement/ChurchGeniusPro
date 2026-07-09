package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.UserPermissions;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserPermissionsRepository extends JpaRepository<UserPermissions, Integer> {

    /** Returns the permissions row for the given app_user id, if one exists. */
    Optional<UserPermissions> findByAppUserId(Integer appUserId);
}

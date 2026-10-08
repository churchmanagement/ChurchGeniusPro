package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, Integer> {

    List<UploadedFile> findByAppClientIdAndDeleteFlagFalseOrderByUploadDateDesc(String appClientId);

    Optional<UploadedFile> findByIdAndDeleteFlagFalse(Integer id);

    /** Tenant-scoped lookup (security audit, week 1). */
    Optional<UploadedFile> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}

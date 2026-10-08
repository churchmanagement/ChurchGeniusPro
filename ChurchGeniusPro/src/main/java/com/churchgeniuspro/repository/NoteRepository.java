package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Note;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Integer> {

    List<Note> findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(String appClientId);

    Optional<Note> findByIdAndDeleteFlagFalse(Integer id);

    /** Tenant-scoped lookup (security audit, week 1). */
    Optional<Note> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
}

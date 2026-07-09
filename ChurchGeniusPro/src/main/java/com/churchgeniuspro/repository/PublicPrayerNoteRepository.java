package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicPrayerNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PublicPrayerNoteRepository extends JpaRepository<PublicPrayerNote, Long> {
    List<PublicPrayerNote> findByPublicPrayerIdOrderByCreatedAtDesc(Long publicPrayerId);
}

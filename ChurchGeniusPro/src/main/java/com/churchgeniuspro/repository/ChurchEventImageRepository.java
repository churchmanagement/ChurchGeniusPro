package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchEventImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ChurchEventImage} — at most one image per event.
 * Database audit P7: the event's flyer lives here rather than on the {@code church_event}
 * row, and is fetched only when a caller needs it.
 */
@Repository
public interface ChurchEventImageRepository extends JpaRepository<ChurchEventImage, Integer> {

    Optional<ChurchEventImage> findByEventId(Integer eventId);

    /** Loads only the image payload for one event, without materialising the whole row. */
    @Query("SELECT i.imageData FROM ChurchEventImage i WHERE i.eventId = :eventId")
    Optional<String> findImageDataByEventId(@Param("eventId") Integer eventId);

    @Transactional
    void deleteByEventId(Integer eventId);
}

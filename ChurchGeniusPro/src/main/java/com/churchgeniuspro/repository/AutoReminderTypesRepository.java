package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AutoReminderTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutoReminderTypesRepository extends JpaRepository<AutoReminderTypes, Integer> {

    /** Returns all reminder types ordered by their stable ID. */
    List<AutoReminderTypes> findAllByOrderByIdAsc();
}

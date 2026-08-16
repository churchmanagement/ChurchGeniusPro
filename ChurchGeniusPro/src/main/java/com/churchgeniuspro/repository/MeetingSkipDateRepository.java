package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MeetingSkipDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface MeetingSkipDateRepository extends JpaRepository<MeetingSkipDate, Long> {

    List<MeetingSkipDate> findByMeetingId(Integer meetingId);

    List<MeetingSkipDate> findByMeetingIdIn(Collection<Integer> meetingIds);

    boolean existsByMeetingIdAndSkipDate(Integer meetingId, LocalDate skipDate);

    void deleteByMeetingIdAndSkipDateIn(Integer meetingId, Collection<LocalDate> dates);

    void deleteByMeetingId(Integer meetingId);

    void deleteByMeetingIdAndSkipDateAfter(Integer meetingId, LocalDate date);
}

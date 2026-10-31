package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * AI Search Phase 1 (2026-10-31) — cross-referenced questions that combine two modules
 * ("came at Easter but not since", "regular attenders not in any group", …).
 *
 * <p>Every query here is read-only, tenant-scoped on EVERY table it touches (the attendance /
 * income / group / follow-up row AND the family it joins to must belong to {@code :cid}),
 * bounded by {@code LIMIT :limit} (callers ask for one row more than they show) and by a
 * date window the caller has already checked against {@code ai.search.max-range-days}.
 * {@code COUNT(*) OVER()} returns the full match count in the same statement.
 *
 * <p>People are Families-page records that are neither deleted nor inactive (member or family).
 * "Attended" = an attendance row linked to the member with status Present or Late (rows from
 * before statuses existed have none and count as present); visits are counted as distinct days.
 * Rows of every list: [memberId, firstName, lastName, lastDate, count, extra, campus, total].
 */
public interface AiCrossReferenceRepository extends Repository<AttendanceRecord, Long> {

    String PRESENT =
            "FROM attendance_record ar JOIN family_member fm ON fm.id = ar.family_member_id JOIN family f ON f.id = fm.family_id " +
            "WHERE ar.client_id = :cid AND f.app_client_id = :cid AND ar.delete_flag = false AND ar.family_member_id IS NOT NULL " +
            "AND (ar.status IS NULL OR ar.status IN ('PRESENT', 'LATE')) " +
            "AND fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false ";

    /** The member's campus on their most recent attendance row (attendance stores campus per check-in). */
    String CAMPUS =
            "(SELECT c.campus FROM attendance_record c WHERE c.client_id = :cid AND c.family_member_id = fm.id AND c.delete_flag = false " +
            " ORDER BY c.attendance_date DESC, c.id DESC LIMIT 1)";

    /** Live group rows of this church only. */
    String LIVE_GROUP_MEMBER =
            "FROM group_member gm JOIN app_group g ON g.id = gm.group_id " +
            "WHERE g.app_client_id = :cid AND g.delete_flag = false AND gm.delete_flag = false ";

    /**
     * Confirmed group membership (decision 2, 2026-10-31): the group row is linked to the member, OR its
     * email equals the member's email exactly (case/space-insensitive) AND that email belongs to only one
     * live member of the church — a family email shared by spouses never confirms either of them.
     */
    String CONFIRMED_IN_GROUP =
            "EXISTS (SELECT 1 " + LIVE_GROUP_MEMBER +
            "AND (gm.family_member_id = fm.id OR (fm.email IS NOT NULL AND TRIM(fm.email) <> '' " +
            "     AND LOWER(TRIM(gm.email)) = LOWER(TRIM(fm.email)) " +
            "     AND (SELECT COUNT(*) FROM family_member x JOIN family xf ON xf.id = x.family_id " +
            "          WHERE xf.app_client_id = :cid AND x.delete_flag = false AND xf.delete_flag = false " +
            "          AND LOWER(TRIM(x.email)) = LOWER(TRIM(fm.email))) = 1)))";

    /** An UNVERIFIED possible match: a group row with the same first and last name that is not linked to anyone else. */
    String NAME_ONLY_GROUP =
            "(SELECT MIN(g.group_name) " + LIVE_GROUP_MEMBER +
            "AND gm.family_member_id IS NULL " +
            "AND LOWER(TRIM(gm.first_name)) = LOWER(TRIM(fm.first_name)) AND LOWER(TRIM(gm.last_name)) = LOWER(TRIM(fm.last_name)))";

    /**
     * Q1 — attended on at least {@code minDays} distinct days in [refFrom, refTo] and not at all in
     * [lapseFrom, today]. Only those two windows are read (never the days between them), so a
     * "last Easter … since February" question scans two short ranges. Extra column: days attended
     * in the reference window. "Last attended" is the latest day inside those windows.
     */
    @Query(value = "SELECT fm.id, fm.first_name, fm.last_name, MAX(ar.attendance_date), " +
            "COUNT(DISTINCT CASE WHEN ar.attendance_date BETWEEN :refFrom AND :refTo THEN ar.attendance_date END), " +
            "NULL, " + CAMPUS + ", COUNT(*) OVER() " + PRESENT +
            "AND (ar.attendance_date BETWEEN :refFrom AND :refTo OR ar.attendance_date BETWEEN :lapseFrom AND :today) " +
            "GROUP BY fm.id, fm.first_name, fm.last_name " +
            "HAVING COUNT(DISTINCT CASE WHEN ar.attendance_date BETWEEN :refFrom AND :refTo THEN ar.attendance_date END) >= :minDays " +
            "AND COUNT(CASE WHEN ar.attendance_date BETWEEN :lapseFrom AND :today THEN 1 END) = 0 " +
            "ORDER BY MAX(ar.attendance_date) DESC, fm.last_name, fm.first_name LIMIT :limit", nativeQuery = true)
    List<Object[]> lapsedAttenders(@Param("cid") String cid, @Param("refFrom") LocalDate refFrom, @Param("refTo") LocalDate refTo,
                                   @Param("minDays") int minDays, @Param("lapseFrom") LocalDate lapseFrom,
                                   @Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * Q2 — regular attenders (≥ minDays distinct days in [from, today]) with no CONFIRMED group membership.
     * Extra column: the name of a group with an unverified same-name row, or null.
     */
    @Query(value = "SELECT fm.id, fm.first_name, fm.last_name, MAX(ar.attendance_date), COUNT(DISTINCT ar.attendance_date), " +
            NAME_ONLY_GROUP + ", " + CAMPUS + ", COUNT(*) OVER() " + PRESENT +
            "AND ar.attendance_date BETWEEN :from AND :today AND NOT " + CONFIRMED_IN_GROUP + " " +
            "GROUP BY fm.id, fm.first_name, fm.last_name " +
            "HAVING COUNT(DISTINCT ar.attendance_date) >= :minDays " +
            "ORDER BY fm.last_name, fm.first_name LIMIT :limit", nativeQuery = true)
    List<Object[]> regularNotInGroup(@Param("cid") String cid, @Param("from") LocalDate from, @Param("today") LocalDate today,
                                     @Param("minDays") int minDays, @Param("limit") int limit);

    /** Q2 — how many of ALL the matches have an unverified same-name group row (the list above is capped). */
    @Query(value = "SELECT COUNT(*) FROM (SELECT fm.id " + PRESENT +
            "AND ar.attendance_date BETWEEN :from AND :today AND NOT " + CONFIRMED_IN_GROUP + " " +
            "AND " + NAME_ONLY_GROUP + " IS NOT NULL " +
            "GROUP BY fm.id HAVING COUNT(DISTINCT ar.attendance_date) >= :minDays) q", nativeQuery = true)
    long countNotInGroupWithNameMatch(@Param("cid") String cid, @Param("from") LocalDate from, @Param("today") LocalDate today,
                                      @Param("minDays") int minDays);

    /**
     * Q5 — regular attenders with no volunteer assignment (not declined) dated on/after servingFrom
     * (upcoming ones count as serving; an undated assignment counts too).
     */
    @Query(value = "SELECT fm.id, fm.first_name, fm.last_name, MAX(ar.attendance_date), COUNT(DISTINCT ar.attendance_date), " +
            "NULL, " + CAMPUS + ", COUNT(*) OVER() " + PRESENT +
            "AND ar.attendance_date BETWEEN :from AND :today " +
            "AND NOT EXISTS (SELECT 1 FROM volunteer_assignment va WHERE va.app_client_id = :cid AND va.family_member_id = fm.id " +
            "     AND va.delete_flag = false AND LOWER(COALESCE(va.assignment_status, 'pending')) <> 'declined' " +
            "     AND (va.event_date IS NULL OR va.event_date >= :servingFrom)) " +
            "GROUP BY fm.id, fm.first_name, fm.last_name " +
            "HAVING COUNT(DISTINCT ar.attendance_date) >= :minDays " +
            "ORDER BY fm.last_name, fm.first_name LIMIT :limit", nativeQuery = true)
    List<Object[]> regularNotServing(@Param("cid") String cid, @Param("from") LocalDate from, @Param("today") LocalDate today,
                                     @Param("minDays") int minDays, @Param("servingFrom") LocalDate servingFrom, @Param("limit") int limit);

    /**
     * Q4 — members who gave in [prevFrom, prevTo] and not in [curFrom, curTo]. Extra column: the
     * amount recorded in the earlier period (gross income rows; fees are expenses and never netted here).
     */
    @Query(value = "SELECT fm.id, fm.first_name, fm.last_name, MAX(i.income_date), " +
            "COUNT(CASE WHEN i.income_date BETWEEN :prevFrom AND :prevTo THEN 1 END), " +
            "SUM(CASE WHEN i.income_date BETWEEN :prevFrom AND :prevTo THEN i.amount ELSE 0 END), NULL, COUNT(*) OVER() " +
            "FROM income i JOIN family_member fm ON fm.id = i.member_id JOIN family f ON f.id = fm.family_id " +
            "WHERE i.app_client_id = :cid AND f.app_client_id = :cid AND i.delete_flag = false " +
            "AND fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false " +
            "AND i.income_date BETWEEN :prevFrom AND :curTo " +
            "GROUP BY fm.id, fm.first_name, fm.last_name " +
            "HAVING COUNT(CASE WHEN i.income_date BETWEEN :prevFrom AND :prevTo THEN 1 END) > 0 " +
            "AND COUNT(CASE WHEN i.income_date BETWEEN :curFrom AND :curTo THEN 1 END) = 0 " +
            "ORDER BY 6 DESC, fm.last_name, fm.first_name LIMIT :limit", nativeQuery = true)
    List<Object[]> lapsedGivers(@Param("cid") String cid, @Param("prevFrom") LocalDate prevFrom, @Param("prevTo") LocalDate prevTo,
                                @Param("curFrom") LocalDate curFrom, @Param("curTo") LocalDate curTo, @Param("limit") int limit);

    /**
     * Q3 — Connect With Us submissions received in [from, toExclusive) that are not marked Contacted/Closed
     * and whose follow-up task is missing or not completed. Follow-ups ownership: unless {@code all}, only
     * submissions whose task is assigned to {@code username} (the Follow-ups page rule for non-admins).
     * Rows: [firstName, lastName, receivedAt, submissionStatus, taskStatus, taskAssignee, taskCreatedAt, total].
     */
    @Query(value = "SELECT cs.first_name, cs.last_name, cs.created_at, cs.status, fu.status, fu.assigned_to, fu.created_at, COUNT(*) OVER() " +
            "FROM connect_submission cs LEFT JOIN follow_up fu ON fu.id = cs.follow_up_id AND fu.client_id = :cid AND fu.delete_flag = false " +
            "WHERE cs.client_id = :cid AND cs.delete_flag = false AND cs.created_at >= :from AND cs.created_at < :toExclusive " +
            "AND COALESCE(cs.status, 'New') NOT IN ('Contacted', 'Closed') " +
            "AND (fu.id IS NULL OR fu.status IS NULL OR fu.status <> 'COMPLETED') " +
            "AND (:all = true OR fu.assigned_to = :username) " +
            "ORDER BY cs.created_at, cs.id LIMIT :limit", nativeQuery = true)
    List<Object[]> connectNotFollowedUp(@Param("cid") String cid, @Param("from") Date from, @Param("toExclusive") Date toExclusive,
                                        @Param("all") boolean all, @Param("username") String username, @Param("limit") int limit);
}

package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ETL Migration Phase 4 — read-only, tenant-scoped access to the live rows of the new
 * data types (pledge campaigns, pledges, events, registrations, attendance, groups,
 * group memberships, Kids Ministry children / classrooms / check-ins), for duplicate
 * matching, load-time lookups and rollback dependency checks.
 *
 * <p>Every query carries the tenant in its WHERE clause; there is no bare by-id read.
 * Written as JPQL here rather than as new methods on each feature's repository, so the
 * import adds no surface to repositories the rest of the app uses.
 */
@Service
public class EtlLiveLookup {

    @PersistenceContext
    private EntityManager em;

    /** For hand-built tests. */
    public void setEntityManager(EntityManager em) { this.em = em; }

    private <T> List<T> list(String jpql, Class<T> type, Object... params) {
        var q = em.createQuery(jpql, type);
        for (int i = 0; i < params.length; i += 2) q.setParameter((String) params[i], params[i + 1]);
        return q.getResultList();
    }

    private long count(String jpql, Object... params) {
        var q = em.createQuery(jpql, Long.class);
        for (int i = 0; i < params.length; i += 2) q.setParameter((String) params[i], params[i + 1]);
        Long n = q.getSingleResult();
        return n == null ? 0 : n;
    }

    // ── live rows for matching ──

    @Transactional(readOnly = true)
    public List<PledgeCampaign> campaigns(String t) {
        return list("select c from PledgeCampaign c where c.clientId = :t and c.deleteFlag = false", PledgeCampaign.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<PledgeMember> pledges(String t) {
        return list("select p from PledgeMember p where p.clientId = :t and p.deleteFlag = false", PledgeMember.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<ChurchEvent> events(String t) {
        return list("select e from ChurchEvent e where e.appClientId = :t and e.deleteFlag = false", ChurchEvent.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<EventRegistration> registrations(String t) {
        return list("select r from EventRegistration r where r.clientId = :t", EventRegistration.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<AttendanceRecord> attendance(String t, LocalDate from, LocalDate to) {
        if (from == null || to == null) return List.of();
        return list("select a from AttendanceRecord a where a.clientId = :t and a.deleteFlag = false and a.attendanceDate between :f and :to",
            AttendanceRecord.class, "t", t, "f", from, "to", to);
    }
    @Transactional(readOnly = true)
    public List<Group> groups(String t) {
        return list("select g from Group g where g.appClientId = :t and g.deleteFlag = false", Group.class, "t", t);
    }
    /** Active memberships as [groupId, familyMemberId, firstName, lastName, email, membershipId]. */
    @Transactional(readOnly = true)
    public List<Object[]> groupMembers(String t) {
        return list("select m.group.id, m.familyMemberId, m.firstName, m.lastName, m.email, m.id from GroupMember m "
            + "where m.appClientId = :t and m.deleteFlag = false", Object[].class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<KmChild> children(String t) {
        return list("select c from KmChild c where c.clientId = :t and c.deleteFlag = false", KmChild.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<KmClassroom> classrooms(String t) {
        return list("select c from KmClassroom c where c.clientId = :t and c.deleteFlag = false", KmClassroom.class, "t", t);
    }
    @Transactional(readOnly = true)
    public List<KmCheckin> checkins(String t, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) return List.of();
        return list("select c from KmCheckin c where c.clientId = :t and c.childId is not null and c.checkinTime between :f and :to",
            KmCheckin.class, "t", t, "f", from, "to", to);
    }
    /** Active funds (sub sources) of the tenant — a campaign's fund resolves to one of these by name. */
    @Transactional(readOnly = true)
    public List<SubSource> funds(String t) {
        return list("select s from SubSource s where s.appClientId = :t and s.deleteFlag = false", SubSource.class, "t", t);
    }

    // ── one live row, tenant-checked ──

    @Transactional(readOnly = true)
    public <T> Optional<T> one(String table, Long id, String t) {
        if (id == null) return Optional.empty();
        String jpql = switch (table) {
            case "pledge_campaign"    -> "select x from PledgeCampaign x where x.id = :id and x.clientId = :t";
            case "pledge"             -> "select x from PledgeMember x where x.id = :id and x.clientId = :t";
            case "event"              -> "select x from ChurchEvent x where x.id = :id and x.appClientId = :t";
            case "event_registration" -> "select x from EventRegistration x where x.id = :id and x.clientId = :t";
            case "attendance"         -> "select x from AttendanceRecord x where x.id = :id and x.clientId = :t";
            case "group"              -> "select x from Group x where x.id = :id and x.appClientId = :t";
            case "group_member"       -> "select x from GroupMember x where x.id = :id and x.appClientId = :t";
            case "km_child"           -> "select x from KmChild x where x.id = :id and x.clientId = :t";
            case "km_checkin"         -> "select x from KmCheckin x where x.id = :id and x.clientId = :t";
            case "km_classroom"       -> "select x from KmClassroom x where x.id = :id and x.clientId = :t";
            case "family_member"      -> "select x from FamilyMember x where x.id = :id and x.family.appClientId = :t";
            default -> throw new IllegalArgumentException("Unknown table " + table);
        };
        Object key = switch (table) {   // id column types differ per entity
            case "attendance", "km_child", "km_checkin", "km_classroom" -> id;
            default -> id.intValue();
        };
        @SuppressWarnings("unchecked")
        List<T> r = (List<T>) em.createQuery(jpql).setParameter("id", key).setParameter("t", t).setMaxResults(1).getResultList();
        return r.stream().findFirst();
    }

    /** Ids of every active row of a table for the tenant (one query) — EXACT matching ignores links to removed rows. */
    @Transactional(readOnly = true)
    public Set<Long> activeIds(String table, String t) {
        String jpql = switch (table) {
            case "pledge_campaign"    -> "select x.id from PledgeCampaign x where x.clientId = :t and x.deleteFlag = false";
            case "pledge"             -> "select x.id from PledgeMember x where x.clientId = :t and x.deleteFlag = false";
            case "event"              -> "select x.id from ChurchEvent x where x.appClientId = :t and x.deleteFlag = false";
            case "event_registration" -> "select x.id from EventRegistration x where x.clientId = :t";
            case "attendance"         -> "select x.id from AttendanceRecord x where x.clientId = :t and x.deleteFlag = false";
            case "group"              -> "select x.id from Group x where x.appClientId = :t and x.deleteFlag = false";
            case "group_member"       -> "select x.id from GroupMember x where x.appClientId = :t and x.deleteFlag = false";
            case "km_child"           -> "select x.id from KmChild x where x.clientId = :t and x.deleteFlag = false";
            case "km_checkin"         -> "select x.id from KmCheckin x where x.clientId = :t";
            default -> throw new IllegalArgumentException("Unknown table " + table);
        };
        Set<Long> out = new HashSet<>();
        for (Object o : em.createQuery(jpql).setParameter("t", t).getResultList()) out.add(((Number) o).longValue());
        return out;
    }

    /** Is this live row still active (not soft-deleted / removed)? Used to ignore stale links. */
    @Transactional(readOnly = true)
    public boolean active(String table, Long id, String t) {
        Optional<Object> o = one(table, id, t);
        if (o.isEmpty()) return false;
        Object x = o.get();
        if (x instanceof PledgeCampaign c) return !c.isDeleteFlag();
        if (x instanceof PledgeMember p) return !p.isDeleteFlag();
        if (x instanceof ChurchEvent e) return !e.isDeleteFlag();
        if (x instanceof AttendanceRecord a) return !a.isDeleteFlag();
        if (x instanceof Group g) return !g.isDeleteFlag();
        if (x instanceof GroupMember m) return !m.isDeleteFlag();
        if (x instanceof KmChild k) return !k.isDeleteFlag();
        if (x instanceof KmClassroom k) return !k.isDeleteFlag();
        return true;   // registrations / check-ins have no soft delete
    }

    // ── dependents (rollback refuses to remove a parent the church has since built on) ──

    @Transactional(readOnly = true)
    public long dependents(String table, Long id, String t) {
        return switch (table) {
            case "pledge_campaign" -> count("select count(p) from PledgeMember p where p.clientId = :t and p.deleteFlag = false and p.campaignId = :id", "t", t, "id", id.intValue());
            case "event"           -> count("select count(r) from EventRegistration r where r.clientId = :t and r.eventId = :id", "t", t, "id", id.intValue());
            case "group"           -> count("select count(m) from GroupMember m where m.appClientId = :t and m.deleteFlag = false and m.group.id = :id", "t", t, "id", id.intValue());
            case "km_child"        -> count("select count(c) from KmCheckin c where c.clientId = :t and c.childId = :id", "t", t, "id", id);
            case "km_classroom"    -> count("select count(c) from KmChild c where c.clientId = :t and c.deleteFlag = false and c.classroomId = :id", "t", t, "id", id)
                                    + count("select count(c) from KmCheckin c where c.clientId = :t and c.classroomId = :id and c.checkoutTime is null", "t", t, "id", id);
            case "sub_source"      -> count("select count(c) from PledgeCampaign c where c.clientId = :t and c.deleteFlag = false and c.subSourceId = :id", "t", t, "id", id.intValue());
            default -> 0;
        };
    }

    // ── writes the loader needs on entities without a soft-delete flag ──

    /** Hard-delete one row the import created (registrations and check-ins have no delete flag). Tenant-checked. */
    @Transactional
    public boolean remove(String table, Long id, String t) {
        Optional<Object> o = one(table, id, t);
        if (o.isEmpty()) return false;
        em.remove(o.get());
        return true;
    }

    public <T> T save(T entity) {
        if (entityId(entity) == null) { em.persist(entity); return entity; }
        return em.merge(entity);
    }

    private static Object entityId(Object e) {
        try { return new org.springframework.beans.BeanWrapperImpl(e).getPropertyValue("id"); } catch (Exception ex) { return null; }
    }
}

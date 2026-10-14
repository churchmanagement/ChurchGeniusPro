package com.churchgeniuspro.worship;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Minimal in-memory behaviour for the worship repositories, so the controller
 * tests exercise the REAL key semantics (one row per client+group+date+workflow,
 * members replaced per assignment id) instead of asserting on mock calls.
 *
 * <p>Only the finders the assignment and song handlers use are modelled.
 */
final class InMemoryWorshipRepos {

    final Map<Long, WorshipAssignment>        assignments = new LinkedHashMap<>();
    final Map<Long, WorshipAssignmentMember>  members     = new LinkedHashMap<>();
    final Map<Long, WorshipSong>              songs       = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);

    final WorshipGroupRepository            groupRepo            = mock(WorshipGroupRepository.class);
    final WorshipInstrumentRepository       instrumentRepo       = mock(WorshipInstrumentRepository.class);
    final WorshipGroupMemberRepository      groupMemberRepo      = mock(WorshipGroupMemberRepository.class);
    final WorshipAssignmentRepository       assignmentRepo       = mock(WorshipAssignmentRepository.class);
    final WorshipAssignmentMemberRepository assignmentMemberRepo = mock(WorshipAssignmentMemberRepository.class);
    final WorshipSongRepository             songRepo             = mock(WorshipSongRepository.class);

    InMemoryWorshipRepos() {
        Mockito.lenient();
        // ── assignments ────────────────────────────────────────────────────
        when(assignmentRepo.save(any(WorshipAssignment.class))).thenAnswer(inv -> {
            WorshipAssignment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(ids.incrementAndGet());
            assignments.put(a.getId(), a);
            return a;
        });
        when(assignmentRepo.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(assignments.get(inv.<Long>getArgument(0))));
        when(assignmentRepo.findFirstByClientIdAndGroupIdAndAssignmentDateAndAssignmentTypeAndDeleteFlagFalse(
                anyString(), anyLong(), any(LocalDate.class), anyString())).thenAnswer(inv ->
                assignments.values().stream()
                        .filter(a -> !a.isDeleteFlag())
                        .filter(a -> a.getClientId().equals(inv.getArgument(0)))
                        .filter(a -> a.getGroupId().equals(inv.getArgument(1)))
                        .filter(a -> a.getAssignmentDate().equals(inv.getArgument(2)))
                        .filter(a -> a.getAssignmentType().equals(inv.getArgument(3)))
                        .findFirst());
        when(assignmentRepo.findFirstByClientIdAndGroupIdAndAssignmentDateAndDeleteFlagFalse(
                anyString(), anyLong(), any(LocalDate.class))).thenAnswer(inv ->
                assignments.values().stream()
                        .filter(a -> !a.isDeleteFlag())
                        .filter(a -> a.getClientId().equals(inv.getArgument(0)))
                        .filter(a -> a.getGroupId().equals(inv.getArgument(1)))
                        .filter(a -> a.getAssignmentDate().equals(inv.getArgument(2)))
                        .findFirst());
        when(assignmentRepo.findByClientIdAndAssignmentDateBetweenAndDeleteFlagFalse(
                anyString(), any(LocalDate.class), any(LocalDate.class))).thenAnswer(inv ->
                range(inv.getArgument(0), null, inv.getArgument(1), inv.getArgument(2)));
        when(assignmentRepo.findByClientIdAndAssignmentTypeAndAssignmentDateBetweenAndDeleteFlagFalse(
                anyString(), anyString(), any(LocalDate.class), any(LocalDate.class))).thenAnswer(inv ->
                range(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        // ── assignment members ─────────────────────────────────────────────
        when(assignmentMemberRepo.save(any(WorshipAssignmentMember.class))).thenAnswer(inv -> {
            WorshipAssignmentMember m = inv.getArgument(0);
            if (m.getId() == null) m.setId(ids.incrementAndGet());
            members.put(m.getId(), m);
            return m;
        });
        doAnswer(inv -> {
            Long aid = inv.getArgument(0);
            members.values().removeIf(m -> aid.equals(m.getAssignmentId()));
            return null;
        }).when(assignmentMemberRepo).deleteByAssignmentId(anyLong());
        when(assignmentMemberRepo.findByAssignmentIdInOrderBySortOrderAsc(anyCollection())).thenAnswer(inv -> {
            Collection<Long> aids = inv.getArgument(0);
            return members.values().stream()
                    .filter(m -> aids.contains(m.getAssignmentId()))
                    .sorted(Comparator.comparingInt(m -> m.getSortOrder() == null ? 0 : m.getSortOrder()))
                    .collect(Collectors.toList());
        });
        // ── songs ──────────────────────────────────────────────────────────
        when(songRepo.save(any(WorshipSong.class))).thenAnswer(inv -> {
            WorshipSong s = inv.getArgument(0);
            if (s.getId() == null) s.setId(ids.incrementAndGet());
            songs.put(s.getId(), s);
            return s;
        });
        when(songRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(songs.get(inv.<Long>getArgument(0))));
        when(songRepo.findByClientIdAndGroupIdAndServiceDateAndScheduleTypeAndDeleteFlagFalseOrderBySortOrderAsc(
                anyString(), anyLong(), any(LocalDate.class), anyString())).thenAnswer(inv ->
                songs.values().stream()
                        .filter(s -> !s.isDeleteFlag())
                        .filter(s -> s.getClientId().equals(inv.getArgument(0)))
                        .filter(s -> s.getGroupId().equals(inv.getArgument(1)))
                        .filter(s -> inv.getArgument(2).equals(s.getServiceDate()))
                        .filter(s -> s.getScheduleType().equals(inv.getArgument(3)))
                        .sorted(Comparator.comparingInt(s -> s.getSortOrder() == null ? 0 : s.getSortOrder()))
                        .collect(Collectors.toList()));
        when(songRepo.findByClientIdAndGroupIdAndScheduleTypeAndServiceDateBetweenAndDeleteFlagFalseOrderByServiceDateAscSortOrderAsc(
                anyString(), anyLong(), anyString(), any(LocalDate.class), any(LocalDate.class))).thenAnswer(inv ->
                songs.values().stream()
                        .filter(s -> !s.isDeleteFlag() && s.getServiceDate() != null)
                        .filter(s -> s.getClientId().equals(inv.getArgument(0)))
                        .filter(s -> s.getGroupId().equals(inv.getArgument(1)))
                        .filter(s -> s.getScheduleType().equals(inv.getArgument(2)))
                        .filter(s -> !s.getServiceDate().isBefore(inv.getArgument(3)) && !s.getServiceDate().isAfter(inv.getArgument(4)))
                        .collect(Collectors.toList()));
        doAnswer(inv -> {
            songs.values().removeIf(s -> s.getClientId().equals(inv.getArgument(0))
                    && s.getGroupId().equals(inv.getArgument(1))
                    && inv.getArgument(2).equals(s.getServiceDate())
                    && s.getScheduleType().equals(inv.getArgument(3)));
            return null;
        }).when(songRepo).deleteByClientIdAndGroupIdAndServiceDateAndScheduleType(anyString(), anyLong(), any(LocalDate.class), anyString());
        when(songRepo.findByClientIdAndGroupIdAndServiceDateIsNullAndDeleteFlagFalseOrderBySortOrderAsc(anyString(), anyLong()))
                .thenAnswer(inv -> songs.values().stream()
                        .filter(s -> !s.isDeleteFlag() && s.getServiceDate() == null)
                        .filter(s -> s.getClientId().equals(inv.getArgument(0)) && s.getGroupId().equals(inv.getArgument(1)))
                        .collect(Collectors.toList()));
        // ── lookups used by the detail builder ─────────────────────────────
        when(instrumentRepo.findByIdIn(anyCollection())).thenReturn(List.of());
        when(groupRepo.findAllById(anyIterable())).thenReturn(List.of());
    }

    private List<WorshipAssignment> range(String clientId, String type, LocalDate from, LocalDate to) {
        return assignments.values().stream()
                .filter(a -> !a.isDeleteFlag())
                .filter(a -> a.getClientId().equals(clientId))
                .filter(a -> type == null || a.getAssignmentType().equals(type))
                .filter(a -> !a.getAssignmentDate().isBefore(from) && !a.getAssignmentDate().isAfter(to))
                .collect(Collectors.toList());
    }

    /** Registers a group for a tenant (tenant-scoped finder + bare finder). */
    WorshipGroup group(long id, String clientId, String name) {
        WorshipGroup g = new WorshipGroup(); g.setId(id); g.setClientId(clientId); g.setGroupName(name);
        when(groupRepo.findByIdAndClientId(id, clientId)).thenReturn(Optional.of(g));
        when(groupRepo.findById(id)).thenReturn(Optional.of(g));
        return g;
    }

    /** Registers an instrument under a group (tenant resolved through the group). */
    WorshipInstrument instrument(long id, long groupId, String name) {
        WorshipInstrument i = new WorshipInstrument(); i.setId(id); i.setGroupId(groupId); i.setInstrumentName(name);
        when(instrumentRepo.findById(id)).thenReturn(Optional.of(i));
        return i;
    }

    List<WorshipAssignmentMember> membersOf(long assignmentId) {
        return members.values().stream().filter(m -> m.getAssignmentId() == assignmentId).collect(Collectors.toList());
    }

    List<WorshipAssignment> active() {
        return assignments.values().stream().filter(a -> !a.isDeleteFlag()).collect(Collectors.toList());
    }
}

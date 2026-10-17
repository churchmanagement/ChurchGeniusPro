package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shared harness for the Migration Phase 4 tests: a real {@link EtlRecordService} over mocked
 * repositories, a mocked {@link EtlLiveLookup} (live rows per table) and an in-memory
 * staging_record store, so validation, load and rollback run exactly as in production code.
 */
class EtlPhase4Support {

    static final String TENANT = "CHR-test";
    static final Long RUN = 91L;
    static final ObjectMapper JSON = new ObjectMapper();

    final StagingRecordRepository recordRepo = mock(StagingRecordRepository.class);
    final StagingFamilyRepository familyRepo = mock(StagingFamilyRepository.class);
    final FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
    final ImportRunService runService = mock(ImportRunService.class);
    final EtlLiveLookup live = mock(EtlLiveLookup.class);
    final EtlLinkService links = mock(EtlLinkService.class);
    final TransformStageService stage = mock(TransformStageService.class);
    final MainSourceRepository mainRepo = mock(MainSourceRepository.class);
    final SubSourceRepository subRepo = mock(SubSourceRepository.class);
    final EtlRecordService svc;
    final ImportRun run = new ImportRun();

    /** The in-memory staging_record table. */
    final List<StagingRecord> store = new ArrayList<>();
    final List<StagingFamily> people = new ArrayList<>();
    final List<FamilyMember> members = new ArrayList<>();
    /** Everything the loader persisted through the live lookup, in order. */
    final List<Object> saved = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(5000), liveIds = new AtomicLong(800);

    EtlPhase4Support() {
        when(mainRepo.save(any(MainSource.class))).thenAnswer(inv -> { MainSource m = inv.getArgument(0); if (m.getId() == null) m.setId(61); return m; });
        when(subRepo.save(any(SubSource.class))).thenAnswer(inv -> { SubSource x = inv.getArgument(0); if (x.getId() == null) x.setId(62); return x; });
        EtlReferenceResolver ref = new EtlReferenceResolver(mainRepo, subRepo, mock(PurposeRepository.class), mock(TransactionTypeRepository.class));
        svc = new EtlRecordService(recordRepo, familyRepo, memberRepo, runService, live, ref);
        svc.setLinkService(links);
        svc.setStageService(stage);
        run.setId(RUN); run.setClientId(TENANT); run.setSourcePreset("GENERIC"); run.setStatus("STAGED");
        when(runService.getRun(RUN, TENANT)).thenReturn(run);
        when(stage.hasSourceIdsForTarget(eq(RUN), anyString())).thenReturn(true);

        when(recordRepo.save(any(StagingRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.findByRunIdAndTargetTableOrderByIdAsc(eq(RUN), anyString()))
            .thenAnswer(inv -> store.stream().filter(r -> r.getTargetTable().equals(inv.getArgument(1))).toList());
        when(recordRepo.findByRunIdAndTargetTableAndRowStatus(eq(RUN), anyString(), anyString()))
            .thenAnswer(inv -> store.stream().filter(r -> r.getTargetTable().equals(inv.getArgument(1)) && r.getRowStatus().equals(inv.getArgument(2))).toList());
        when(recordRepo.findByBatchIdOrderByIdAsc(anyLong()))
            .thenAnswer(inv -> store.stream().filter(r -> Objects.equals(r.getBatchId(), inv.getArgument(0))).toList());
        when(recordRepo.findByIdAndClientId(anyLong(), eq(TENANT)))
            .thenAnswer(inv -> store.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(recordRepo.findByIdInAndClientId(any(), eq(TENANT)))
            .thenAnswer(inv -> { Collection<Long> want = inv.getArgument(0); return store.stream().filter(r -> want.contains(r.getId())).toList(); });
        when(recordRepo.findByRunId(RUN)).thenAnswer(inv -> List.copyOf(store));
        when(familyRepo.findByRunId(RUN)).thenAnswer(inv -> List.copyOf(people));
        when(memberRepo.findActiveByTenantForEtl(TENANT)).thenAnswer(inv -> List.copyOf(members));
        when(links.index(anyString(), anyString(), anyString())).thenReturn(Map.of());
        when(links.resolve(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        // live lookup: empty by default; save assigns ids like the database would
        when(live.campaigns(TENANT)).thenReturn(List.of());
        when(live.pledges(TENANT)).thenReturn(List.of());
        when(live.events(TENANT)).thenReturn(List.of());
        when(live.registrations(TENANT)).thenReturn(List.of());
        when(live.attendance(eq(TENANT), any(), any())).thenReturn(List.of());
        when(live.groups(TENANT)).thenReturn(List.of());
        when(live.groupMembers(TENANT)).thenReturn(List.of());
        when(live.children(TENANT)).thenReturn(List.of());
        when(live.classrooms(TENANT)).thenReturn(List.of());
        when(live.checkins(eq(TENANT), any(), any())).thenReturn(List.of());
        when(live.funds(TENANT)).thenReturn(List.of());
        when(live.activeIds(anyString(), eq(TENANT))).thenReturn(Set.of());
        when(live.save(any())).thenAnswer(inv -> {
            Object e = inv.getArgument(0);
            var w = new org.springframework.beans.BeanWrapperImpl(e);
            if (w.getPropertyValue("id") == null) {
                Class<?> t = w.getPropertyType("id");
                w.setPropertyValue("id", t == Long.class ? (Object) liveIds.incrementAndGet() : (Object) (int) liveIds.incrementAndGet());
                saved.add(e);
            }
            liveRow(e);
            return e;
        });
        doAnswer(inv -> Optional.ofNullable(liveRows.get(inv.getArgument(0) + ":" + ((Number) inv.getArgument(1)).longValue())))
            .when(live).one(anyString(), any(), eq(TENANT));
    }

    /** Live rows by "table:id" — what {@link EtlLiveLookup#one} returns. */
    final Map<String, Object> liveRows = new HashMap<>();
    private static final Map<Class<?>, String> TABLE_OF = Map.of(
        PledgeCampaign.class, "pledge_campaign", PledgeMember.class, "pledge", ChurchEvent.class, "event",
        EventRegistration.class, "event_registration", AttendanceRecord.class, "attendance", Group.class, "group",
        GroupMember.class, "group_member", KmChild.class, "km_child", KmCheckin.class, "km_checkin", KmClassroom.class, "km_classroom");

    /** Register an existing live row so the loader / rollback can find it. */
    <T> T liveRow(T e) {
        Object id = new org.springframework.beans.BeanWrapperImpl(e).getPropertyValue("id");
        String table = e instanceof FamilyMember ? "family_member" : TABLE_OF.get(e.getClass());
        if (id != null && table != null) liveRows.put(table + ":" + ((Number) id).longValue(), e);
        return e;
    }

    /** A staged row of a record table, as the stage step writes it. */
    StagingRecord staged(String table, String sourceRowId, Map<String, Object> values) {
        StagingRecord r = new StagingRecord();
        r.setId(ids.incrementAndGet()); r.setRunId(RUN); r.setClientId(TENANT); r.setTargetTable(table);
        r.setSourceRowId(sourceRowId); r.setSourcePayload("{}"); r.setRowStatus("PENDING");
        try { r.setValuesJson(JSON.writeValueAsString(values)); } catch (Exception e) { throw new RuntimeException(e); }
        store.add(r);
        return r;
    }

    static Map<String, Object> v(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** A live member (and household) of the tenant. */
    FamilyMember member(int id, String first, String last, String email, String phone) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setFirstName(first); m.setLastName(last); m.setEmail(email); m.setPhone(phone);
        Family f = new Family(); f.setId(id * 10); f.setAppClientId(TENANT); m.setFamily(f); m.setAppClientId(TENANT);
        members.add(m);
        liveRow(m);
        return m;
    }

    /** A people row staged in this run (optionally already loaded onto a live member). */
    StagingFamily person(String sourceId, String first, String last, Long loadedAs) {
        StagingFamily f = new StagingFamily(); f.setId(ids.incrementAndGet()); f.setRunId(RUN); f.setClientId(TENANT);
        f.setSourceRowId(sourceId); f.setFamilyKey(sourceId); f.setFirstName(first); f.setLastName(last);
        f.setRowStatus(loadedAs == null ? "VALID" : "LOADED"); f.setTargetId(loadedAs);
        people.add(f);
        return f;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, String>> msgs(StagingRecord r) {
        try { return r.getValidationMsgs() == null ? List.of() : JSON.readValue(r.getValidationMsgs(), List.class); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String msgText(StagingRecord r) { return msgs(r).stream().map(m -> m.get("level") + ":" + m.get("message")).reduce("", (a, b) -> a + " | " + b); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> links(StagingRecord r) {
        try { return r.getLinksJson() == null ? Map.of() : JSON.readValue(r.getLinksJson(), Map.class); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Approve the table's loadable rows into batch {@code batchId} and load them in one chunk. */
    int[] load(String table, long batchId) {
        svc.tagLoadable(RUN, table);
        svc.assignBatch(RUN, table, batchId);
        List<Long> ids = store.stream().filter(r -> table.equals(r.getTargetTable()) && "APPROVED".equals(r.getRowStatus())).map(StagingRecord::getId).toList();
        return svc.loadChunk(table, ids, TENANT, "actor", RUN, batchId, "GENERIC", true, svc.cache(TENANT));
    }

    <T> T savedOf(Class<T> type, int index) {
        return saved.stream().filter(type::isInstance).map(type::cast).toList().get(index);
    }

    ImportBatch batch(long id, String table, String status) {
        ImportBatch b = new ImportBatch(); b.setId(id); b.setRunId(RUN); b.setClientId(TENANT); b.setTargetTable(table); b.setStatus(status);
        return b;
    }
}

package com.churchgeniuspro.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns "the snapshot tables that exist for one date" into a restore that can actually be
 * executed: which tables, in which order, copying which columns — or the precise reasons
 * it must not be executed at all. Pure and side-effect free, so every rule is unit-tested
 * without a database; {@link BackupService#restoreSnapshot} reads the catalogue, calls
 * {@link #plan}, and runs the plan inside one transaction.
 *
 * <p>Database audit C2 (Sept 2026) found the previous restore doing, per table in
 * catalogue order, {@code TRUNCATE … CASCADE} then {@code INSERT … SELECT *}. Real foreign
 * keys exist on exactly the aggregates that matter ({@code income → sub_source,
 * family_member, transaction_type}; {@code expense → main_source, purpose,
 * transaction_type}; {@code sub_source → main_source}; {@code meeting → meeting_type};
 * …), so restoring a parent after its child truncated the child again through
 * {@code CASCADE}: {@code income}, {@code expense} and {@code meeting} came back empty
 * from a restore that reported SUCCESS. {@code SELECT *} also assumed the snapshot and
 * the live table still had the same columns, which {@code ddl-auto} silently breaks
 * (39 of production's 172 snapshots no longer matched), and no sequence was ever re-synced,
 * so the first insert after a restore failed on a duplicate key.
 *
 * <p>The rules, each pinned by {@code SnapshotRestorePlannerTest}:
 * <ul>
 *   <li><b>Parents first.</b> Tables are ordered so that every foreign-key parent is loaded
 *       before its children; the caller truncates all of them in one statement, never with
 *       {@code CASCADE}.</li>
 *   <li><b>No silent cascade.</b> A table that a foreign key references from a table with
 *       no snapshot for this date cannot be truncated without emptying that other table —
 *       so it is a blocker, and nothing runs.</li>
 *   <li><b>Columns by name.</b> Each table copies the columns the snapshot and the live table
 *       share, in the live table's order. A live column added since the snapshot is left
 *       to its default (a warning); a snapshot column dropped since is not restored (a
 *       warning); a live {@code NOT NULL} column without a default that the snapshot lacks
 *       cannot be filled — a blocker.</li>
 *   <li><b>Everything or nothing.</b> Any blocker means no statement is executed; the caller
 *       also runs the whole plan in one transaction so a failure part-way leaves the
 *       database exactly as it was.</li>
 * </ul>
 */
public final class SnapshotRestorePlanner {

    private SnapshotRestorePlanner() {}

    /** One column as {@code information_schema.columns} describes it. */
    public record Column(String name, boolean nullable, boolean hasDefault, boolean generated) {
        public Column(String name, boolean nullable, boolean hasDefault) {
            this(name, nullable, hasDefault, false);
        }
    }

    /** A foreign key between two base tables (a self-reference has {@code child == parent}). */
    public record ForeignKey(String name, String childTable, String parentTable) {}

    /** One table's restore: the snapshot it is loaded from and the columns copied by name. */
    public record Step(String table, String snapshot, List<String> columns) {}

    /** The plan. When {@link #blockers} is non-empty nothing may be executed. */
    public record Plan(List<Step> steps, List<String> blockers, List<String> warnings) {
        public boolean executable() { return blockers.isEmpty(); }
        public List<String> tables() { return steps.stream().map(Step::table).toList(); }
    }

    /**
     * @param snapshotByTable live table → its snapshot table for the date being restored
     *                        (already minus the tables that are never restored)
     * @param columns         table → its columns in ordinal order, for every live table in
     *                        {@code snapshotByTable} that exists and every snapshot table
     * @param foreignKeys     every foreign key between base tables in the schema
     * @param baseTables      every base table in the schema (snapshots excluded), used only
     *                        to say which live tables this date has no snapshot for
     */
    public static Plan plan(Map<String, String> snapshotByTable,
                            Map<String, List<Column>> columns,
                            List<ForeignKey> foreignKeys,
                            Set<String> baseTables) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Step> steps = new TreeMap<>();

        // ── columns: what each table can copy, and what it cannot ────────────────────
        for (Map.Entry<String, String> e : new TreeMap<>(snapshotByTable).entrySet()) {
            String table = e.getKey();
            String snapshot = e.getValue();
            List<Column> live = columns.get(table);
            List<Column> snap = columns.get(snapshot);
            if (live == null) {
                warnings.add(table + ": no longer exists, so " + snapshot + " is not restored");
                continue;
            }
            if (snap == null || snap.isEmpty()) {
                blockers.add(table + ": snapshot " + snapshot + " has no columns the catalogue can describe");
                continue;
            }
            Set<String> snapNames = new LinkedHashSet<>();
            snap.forEach(c -> snapNames.add(c.name()));
            List<String> copy = new ArrayList<>();
            List<String> addedSince = new ArrayList<>();
            for (Column c : live) {
                if (c.generated()) {
                    continue;                          // the database computes it; it cannot be inserted
                }
                if (snapNames.contains(c.name())) {
                    copy.add(c.name());
                } else if (!c.nullable() && !c.hasDefault()) {
                    blockers.add(table + "." + c.name() + " is NOT NULL with no default and is absent from "
                            + snapshot + " — the rows cannot be loaded; give the column a default first");
                } else {
                    addedSince.add(c.name());
                }
            }
            Set<String> liveNames = new TreeSet<>();
            live.forEach(c -> liveNames.add(c.name()));
            List<String> droppedSince = new ArrayList<>();
            for (String s : snapNames) {
                if (!liveNames.contains(s)) droppedSince.add(s);
            }
            if (!addedSince.isEmpty()) {
                warnings.add(table + ": column(s) added since the snapshot are left at their default — "
                        + String.join(", ", addedSince));
            }
            if (!droppedSince.isEmpty()) {
                warnings.add(table + ": column(s) dropped since the snapshot are not restored — "
                        + String.join(", ", droppedSince));
            }
            if (copy.isEmpty()) {
                blockers.add(table + ": shares no column with " + snapshot);
                continue;
            }
            steps.put(table, new Step(table, snapshot, List.copyOf(copy)));
        }

        // ── foreign keys: what truncating the set would drag along ───────────────────
        Set<String> set = steps.keySet();
        Map<String, Set<String>> childrenOf = new TreeMap<>();   // parent → children, inside the set
        Map<String, Integer> parentsLeft = new TreeMap<>();      // child → number of in-set parents not yet ordered
        for (String t : set) {
            childrenOf.put(t, new TreeSet<>());
            parentsLeft.put(t, 0);
        }
        Set<String> reportedOutsiders = new TreeSet<>();
        for (ForeignKey fk : foreignKeys) {
            boolean parentIn = set.contains(fk.parentTable());
            boolean childIn = set.contains(fk.childTable());
            if (parentIn && !childIn) {
                if (reportedOutsiders.add(fk.childTable() + "→" + fk.parentTable())) {
                    blockers.add("cannot empty " + fk.parentTable() + ": " + fk.childTable() + " references it ("
                            + fk.name() + ") and has no snapshot for this date, so restoring "
                            + fk.parentTable() + " would also empty " + fk.childTable());
                }
            } else if (parentIn && !fk.parentTable().equals(fk.childTable())) {
                if (childrenOf.get(fk.parentTable()).add(fk.childTable())) {
                    parentsLeft.merge(fk.childTable(), 1, Integer::sum);
                }
            }
        }

        // ── order: parents before children, alphabetical among peers (Kahn) ─────────
        List<Step> ordered = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        parentsLeft.forEach((t, n) -> { if (n == 0) ready.add(t); });   // TreeMap → alphabetical
        while (!ready.isEmpty()) {
            String t = ready.poll();
            ordered.add(steps.get(t));
            TreeSet<String> newlyReady = new TreeSet<>();
            for (String child : childrenOf.get(t)) {
                int left = parentsLeft.merge(child, -1, Integer::sum);
                if (left == 0) newlyReady.add(child);
            }
            // keep alphabetical order among tables that become ready together
            List<String> queued = new ArrayList<>(ready);
            queued.addAll(newlyReady);
            queued.sort(null);
            ready.clear();
            ready.addAll(queued);
        }
        if (ordered.size() != steps.size()) {
            List<String> cyclic = new ArrayList<>();
            parentsLeft.forEach((t, n) -> { if (n > 0) cyclic.add(t); });
            blockers.add("circular foreign keys among " + String.join(", ", cyclic)
                    + " — they cannot be loaded in any order without deferring the constraints");
        }

        // ── tables this date has no snapshot for ────────────────────────────────────
        List<String> untouched = new ArrayList<>();
        for (String t : new TreeSet<>(baseTables)) {
            if (!snapshotByTable.containsKey(t) && !BackupService.EXCLUDED_TABLES.contains(t)) {
                untouched.add(t);
            }
        }
        if (!untouched.isEmpty()) {
            String shown = String.join(", ", untouched.subList(0, Math.min(untouched.size(), 12)));
            if (untouched.size() > 12) shown += ", … and " + (untouched.size() - 12) + " more";
            warnings.add(untouched.size() + " live table(s) have no snapshot for this date and are left as they are: " + shown);
        }

        return new Plan(List.copyOf(ordered), List.copyOf(blockers), List.copyOf(warnings));
    }
}

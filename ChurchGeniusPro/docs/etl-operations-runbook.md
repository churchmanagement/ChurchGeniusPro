# ETL Import — Operations Runbook

Operational guide for Service Admins running the AI-assisted data import
(`/etlImport`). Covers what to do before, during, and after a load, and how to
recover from problems. Pairs with the design doc (`docs/etl-pipeline-design.md`).

---

## 1. Before you load — back up

The loader writes to the live `family`, `family_member`, `income`, and `expense`
tables (and may create `main_source` / `sub_source` / `purpose` reference rows).
A batch is reversible (see §4), but **always take a database backup before the
first load into a tenant** — it is the only recovery path if something outside
the ETL system goes wrong mid-load.

PostgreSQL (the app's database):

```bash
# Whole database (simplest, recommended before a large import)
pg_dump -h localhost -U postgres -d postgres -F c -f churchgenius_pre_import.dump

# Just the tables the loader touches, for a faster targeted backup
pg_dump -h localhost -U postgres -d postgres -F c \
  -t family -t family_member -t income -t expense \
  -t main_source -t sub_source -t purpose \
  -f churchgenius_tables_pre_import.dump
```

Restore (only if you must roll the whole DB back):

```bash
pg_restore -h localhost -U postgres -d postgres --clean churchgenius_pre_import.dump
```

The Service Admin console also has a Backup panel (`/serviceadminhome` →
Backup) — run a snapshot there before importing if you prefer the in-app flow.

---

## 2. The safe sequence

1. **Create a run** for the target church. The run is bound to that one tenant
   for life; nothing it stages or loads can land in another church.
2. **Upload** each source file (CSV/JSON). Data is captured verbatim into
   `staging_raw` and profiled — no live writes.
3. **Map** each target table (family → income → expense). Confirm `NEEDS_REVIEW`
   mappings; AI suggestions are always review-only.
4. **Stage & validate.** Review the preview grid; filter to `error` and `warn`.
   Fix mappings and re-stage if needed.
5. **Approve a batch** for one target table, then **Load**. Load **family
   first** so income/expense `family_link`s resolve to the new members.
6. **Verify** (see §5) and spot-check a few records in the live app.
7. Repeat 5–6 for income, then expense.

> Rule of thumb: load **family before income/expense**. Money rows whose
> `family_link` cannot be resolved still load, but **unlinked** (a WARN), and
> cannot be auto-linked later without re-import.

---

## 3. Duplicates & overwrites

- The validator flags a staged family row as a possible duplicate of an existing
  member (by email, then name+phone) and sets its action to **SKIP** by default.
- Default behaviour is **INSERT-or-SKIP** — the loader never overwrites existing
  data unless you explicitly change a row's action to **UPDATE**.
- To update instead of skip, set the row's `dedupeAction` to `UPDATE` (the loader
  saves a before-image so the change is reversible).

---

## 4. Rollback

Every load is a **batch**. Each inserted live row is tagged with the batch, and
each staged row keeps its live `target_id`.

- **Roll back a batch** from the wizard (or `POST /batches/{id}/rollback`):
  inserts are **soft-deleted** (`delete_flag = true`), updates are **restored**
  from the before-image, and any Family rows the batch created are soft-deleted.
- Rollback is only available while the batch status is `LOADED`.
- Rollback does **not** remove auto-created reference rows (`main_source`,
  `sub_source`, `purpose`) — they are harmless empties and may be reused. Remove
  them manually if required.

---

## 5. Verify after every load

Run **Verify run** in the wizard (or `GET /runs/{id}/verify`). It reconciles
staging ↔ batches ↔ live `target_id`s ↔ the audit trail and reports:

| Check | What a FAIL means |
|---|---|
| `tenant_stamp` | A staged row carries a different `client_id` than the run — stop and investigate. |
| `loaded_have_target` | A row marked LOADED has no live id — the load did not complete cleanly. |
| `batch_audit_present` | A loaded/rolled-back batch has no audit event — audit gap. |
| `count_reconciliation` | Batch counters disagree with the staged LOADED rows. |
| `rollback_complete` | A rolled-back batch still has live LOADED rows. |
| `pending_loadable` (WARN) | VALID/WARN rows not yet approved — informational. |

A clean run shows **ALL CHECKS PASSED**. Investigate any FAIL before trusting the
import; the per-batch **Report** lists each row's outcome and live id.

---

## 6. Concurrency

Mutating operations on a run (suggest, stage, approve, load, rollback) are
**serialized per run**. A second concurrent attempt on the same run returns
`409 — Run is busy`; wait and retry. Different runs proceed in parallel.

Avoid double-clicking **Load**; if you get a busy error, do not retry until the
first operation finishes.

---

## 7. Large imports

Loads commit in **chunks** (default 500 rows, `etl.load.chunk-size`). Benefits:

- One enormous transaction is never held open.
- If a chunk fails (e.g. a DB hiccup), earlier chunks are already committed and
  reversible; re-running **Load** continues only the still-APPROVED rows.

Tune the chunk size in `application.properties` for very wide rows or constrained
memory:

```
etl.load.chunk-size=200
```

---

## 8. Failure recovery checklist

| Symptom | Action |
|---|---|
| Load shows non-zero **failed** | Open the batch **Report**, find FAILED rows, read their validation messages. Common cause: a money row with no date (NOT NULL) or an unparseable value. Fix the mapping/source and re-stage that table. |
| Load interrupted (crash / 500 mid-load) | Re-run **Load** on the same batch — it resumes the remaining APPROVED rows. Then **Verify**. |
| Wrong data loaded | **Roll back** the batch, fix mappings, re-stage, re-approve, re-load. |
| `409 Run is busy` | Another operation is in flight; wait and retry. |
| Verify reports a FAIL | Use the table in §5; inspect the batch Report; if integrity is in doubt, roll back the batch and restore from the §1 backup as a last resort. |
| Income/expense loaded **unlinked** | Ensure family was loaded **before** the money tables; re-import the money table after family exists so `family_link` resolves. |

---

## 9. What the system guarantees

- **Tenant isolation** — every live insert is stamped with the run's `client_id`;
  reads/updates/rollbacks are tenant-scoped. Other churches are never touched.
- **No silent overwrite** — INSERT-or-SKIP by default; UPDATE only on explicit
  per-row confirmation, with a saved before-image.
- **Reversibility** — any batch can be rolled back as a unit.
- **Auditability** — every meaningful action (create, map, stage, validate,
  approve, load, rollback) is recorded in `import_audit`; `Verify` cross-checks it.

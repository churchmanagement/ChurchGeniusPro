# Data Migration (ETL Import) — Service Admin guide

How ChurchGeniusPro support moves a church's records from another church-management
product into ChurchGeniusPro. Only Service Admins can do this. The page is
**Service Admin → Data Migrations → New migration** (`/etlImport`).

A migration is a **run**. A run belongs to one church. Everything it stages or loads is
stamped with that church, and every load can be undone.

## The six steps

| Step | What you do | What changes |
|---|---|---|
| **1 · Start** | Pick the church and the product it is leaving (Breeze, CHMeetings, Gracely, Planning Center, Church Community Builder, or *Generic CSV / Excel* for anything else). Check the date order, add a note, and choose whether to allow AI mapping suggestions. The church's earlier imports are listed here with **Open**. | Only a new run. Nothing in the church's data changes. |
| **2 · Files** | Drop every exported file at once (CSV, Excel, JSON). Each file is profiled and given a data type based on its column names; change the type if the guess is wrong. If a file was already uploaded for this church, it is refused until you click **Import it again**. Keep one file per data type in a run. | Files are captured in staging only. |
| **3 · Map** | You get one tab per file, with required columns first. **AUTO** means a confident match and **REVIEW** means please check it. Confirm or reject each row. Unify values such as funds, roles, gender, member status and payment methods. If dates could be read more than one way, the date panel shows each sample read four ways; pick the order the old system used. | Mapping rules only. |
| **4 · Review** | **Next** stages and validates every file. Click a tile to see its rows. Possible duplicates need a decision before that type can be approved: Skip, Update, Merge (not for money) or Import as new. Proposed households (same last name and address, no household in the file) are combined only if you **Accept** them. Each type has a validation report (CSV). | Staging only. |
| **5 · Load** | Use **Approve all ready types**, then **Load all in order**. People and the records others point to load first; a type waits for the types it depends on. Each type shows progress, a per-row **Report**, **Roll back**, and **Retry remaining** if a load failed. **Verify run** re-checks tenant stamps, counts and the audit trail. | Live data for that church. |
| **6 · Summary** | Totals and per-type counts, plus anything that needs attention (failed rows, errors, rows not loaded). Download the migration summary and the audit trail as CSV. **Undo entire import** rolls back every batch, in reverse order. | Only if you undo. |

The bar at the top always shows the church, the source product, the run, its status and the
last action. **Audit trail** opens every event of the run, with a CSV download.

## Rules that never change

- **Tenant.** Every request names the church, and the run must belong to it. Another
  church's run is "not found".
- **Money.** Gifts and expenses are never merged. They are updated only from an exact
  re-import or a strong match. A gift whose donor cannot be found is imported under the
  donor's name as a guest gift.
- **Households.** Inferred households are proposals and are never merged automatically.
- **Rollback.** Rolling back removes the rows the import inserted and restores the rows it
  updated. Funds, groups and classrooms the import created are removed only when nothing
  else uses them. If the church has since added its own records under an imported event,
  campaign, group or child, the rollback is refused and nothing changes.
- **Presets** are marked *Preset (unverified)* until a real export from that product has
  been run end to end. Review every mapping when using one.
- **AI suggestions** are off unless you tick the box for the run. Only column names and
  types are sent, never the church's data.

## What the church sees

The Church Settings page (`/logo`) shows a read-only card, **Data Imported for Your
Church**. Each entry reads "Data was imported on *date* by ChurchGeniusPro support": the
source product, the number of records added or updated per data type, and whether the
import was later undone. Run notes, file names, operator names and row data are not
shown. Church users cannot open `/etlImport` or the `/api/serviceadmin/etl/*` APIs.

## Testing

Only ever test against a **local** PostgreSQL with the sanitized fixtures in
`src/test/resources/etl/` (all invented people and amounts). Never test against production,
and never put real member or financial data in a fixture.

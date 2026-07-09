# ETL sample imports — end-to-end test plan

Small CSVs that exercise every path in the import wizard (`/etlImport`). Use a
**test church** — these create live family/income/expense rows (all reversible).

Suggested order: **family → income → expense → families2 (dedupe) → verify → rollback**.

## Files & what they test

### `families.csv` (load first)
Legacy-style headers (First Name, Surname, Nickname, E-mail Address, Mobile, Zip…)
to exercise the deterministic matcher. `Family ID` is the household/link key.

| Row | Expected |
|---|---|
| John Smith (Family ID 1001) | INSERT |
| Mary Smith (Family ID 1001) | INSERT — **same household** as John (one Family row, two members) |
| David Jones (1002) | INSERT |
| Sarah Lee (1003) | INSERT |
| blank name (1004) | **ERROR** (first/last required) — excluded from load |

→ Stage shows 5 staged, 1 error. Approve + Load → **4 inserted**.

### `income.csv`
Headers: Gift Date, Amount, Fund, Sub Fund, Method, Reference No, Member ID, Note.
`Member ID` links to the family `Family ID`.

| Row | Expected |
|---|---|
| 150.00, Member 1001 | VALID → INSERT, linked to John |
| $75.50, Member 1002 | VALID → INSERT (currency parsed), linked to David |
| 200, Member 9999 | **WARN** (unresolved link) → INSERT **unlinked** |
| 50, **missing date** | WARN → load **FAILS** (date is required on the live table) — demonstrates failed-row isolation |
| abc, bad amount | **ERROR** (amount won't parse) — excluded |
| 0, zero amount | **ERROR** (amount must be > 0) — excluded |

→ Load → ~3 inserted, 1 failed; errors excluded. (Load **after** family so links resolve.)

### `expense.csv`
| Row | Expected |
|---|---|
| 120.00 Utilities/Electricity | INSERT (auto-creates Purpose + Main Source) |
| $45.99 Supplies/Office | INSERT |
| 300 Missions/Outreach | INSERT |
| (25.00) negative | **ERROR** (amount must be > 0; parens parse as −25) — excluded |

→ Load → **3 inserted**, errors excluded.

### `families2.csv` (load after families.csv — tests dedupe)
| Row | Expected |
|---|---|
| John Smith (same email) | **duplicate** of the loaded John → flagged, default action **SKIP** |
| Peter Brown | INSERT |

→ Preview shows John as a possible duplicate (matched by email). Load with
defaults → **1 inserted, 1 skipped**. (Set John's action to UPDATE to test the
update + before-image path instead.)

## After loading
- **Verify run** → all checks should PASS (tenant stamp, loaded-have-target,
  audit present, count reconciliation).
- Spot-check the live app: family directory, income, expense.
- **Roll back** a batch → inserts are soft-deleted, families removed; re-Verify.

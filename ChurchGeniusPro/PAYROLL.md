# Payroll module — index

W-2 employee payroll for ChurchGenius Pro: employee/W-4/deduction setup, payroll
runs with an approval workflow, paystub PDFs, and reporting (pay history, YTD,
W-2, payroll register, tax liability) with an audit trail.

- **Code:** `src/main/java/com/churchgeniuspro/payroll/`
- **Architecture & tax methodology:** see `src/main/java/com/churchgeniuspro/payroll/README.md`
- **Pages:** `src/main/resources/static/payroll-*.html`

All data is multi-tenant (`app_client_id`). Every payroll page and API is gated by
`RoleGuard.requirePayroll` — access is limited to **SuperAdmin, Admin, Accountant,
and Church** logins (User and Member/Child are denied) — and scoped to the
caller's tenant via `RoleGuard.clientId`, which resolves the organization id for
both staff (`appClientId`) and church (`clientId`) logins. The audit actor is the
session `username`. Authorization lives in two places: `RoleGuard.requirePayroll`
(the page routes) and `PayrollAuth.authorize` (the REST controllers, which reuse
the same guard).

## Pages (UI)

| Route (gated) | Purpose |
|---------------|---------|
| `/payroll/employees` | Employees, W-4, deduction catalog + assignments, per-employee pay history & YTD |
| `/payroll/runs` | Create runs, process employees (hours entry for hourly), approval workflow, paystub view/download |
| `/payroll/reports` | Year-end W-2 (CSV + per-employee PDF), payroll register & tax liability |
| `/payroll/activity` | Activity / audit log |

Each route applies `RoleGuard.requirePayroll` then forwards to the matching
`payroll-*.html` in `static/` (`PayrollPageController`).

## Typical flow

1. Add employees (`payroll-employees.html`) → set each W-4 → define deduction
   types and assign them.
2. Create a run (`payroll-run.html`) → **Process all salaried** and/or enter
   hours for hourly employees → **Submit** → **Approve** → **Mark paid**.
3. Download paystubs from the run view; review YTD/W-2 per employee and the
   register / tax liability on the Reports page.

## REST endpoints

### Employee setup — `PayrollSetupController`
| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/payroll/employees` | List employees |
| GET | `/api/payroll/employees/{id}` | Employee + current W-4 + deduction assignments |
| POST | `/api/payroll/employees` | Create (body `EmployeeRequest`) |
| PUT | `/api/payroll/employees/{id}` | Partial update |
| POST | `/api/payroll/employees/{id}/deactivate` | Soft-deactivate |
| GET / PUT | `/api/payroll/employees/{id}/w4` | Get / replace W-4 (`W4Request`; prior W-4 kept, deactivated) |
| GET / POST | `/api/payroll/deduction-definitions` | List / create deduction types (`DeductionDefinitionRequest`) |
| PUT | `/api/payroll/deduction-definitions/{id}` | Update |
| POST | `/api/payroll/deduction-definitions/{id}/deactivate` | Deactivate |
| GET / POST | `/api/payroll/employees/{id}/deductions` | List / assign (`AssignDeductionRequest`) |
| DELETE | `/api/payroll/employees/{id}/deductions/{assignmentId}` | Remove assignment |

### Run lifecycle — `PayrollAdminController`
| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/payroll/runs/create` | Create run (`CreateRunRequest`) |
| POST | `/api/payroll/runs/{runId}/employees/{employeeId}/process` | Process one employee (`ProcessEmployeeRequest`; salaried may omit earnings) |
| POST | `/api/payroll/runs/{runId}/process-all` | Process all active employees (skips those it can't auto-pay) |
| POST | `/api/payroll/runs/{runId}/submit` | DRAFT → PENDING_APPROVAL |
| POST | `/api/payroll/runs/{runId}/approve` | PENDING_APPROVAL → APPROVED |
| POST | `/api/payroll/runs/{runId}/paid` | APPROVED → PAID |
| POST | `/api/payroll/runs/{runId}/void` | Void run + its paystubs (body `{reason}`) |

### Run & paystub reads — `PayrollRunController`, `PayrollPaystubController`
| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/payroll/runs` | Tenant's runs (newest first) |
| GET | `/api/payroll/runs/{runId}/paystubs` | Paystubs in a run + run summary |
| GET | `/api/payroll/paystubs/{id}/pdf` | Paystub PDF (`?download=true` to force download) |

### Reporting & audit — `PayrollReportsController`
| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/payroll/audit` | Activity log (`?entityType=&entityId=&limit=`) |
| GET | `/api/payroll/employees/{id}/paystubs` | Employee paystub history |
| GET | `/api/payroll/employees/{id}/ytd` | YTD gross/taxes/net + W-2 boxes (`?year=`) |

### Exports — `PayrollExportController`
| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/payroll/reports/w2/csv` | Bulk W-2 CSV, all employees (`?year=`) |
| GET | `/api/payroll/employees/{id}/w2/pdf` | Per-employee W-2 summary PDF (`?year=&download=`) |
| GET | `/api/payroll/runs/{runId}/register` | Payroll register for a run |
| GET | `/api/payroll/runs/{runId}/tax-liability` | Employer withholding liability by category |

## Tax configuration

Federal withholding (IRS Pub 15-T 2026, Worksheet 1A) and FICA are held in
effective-dated DB tables and seeded on first use by `TaxConfigService`
(`payroll_federal_bracket`, `payroll_federal_std_deduction`, `payroll_fica_rate`).
State tax is configurable per state/year (`payroll_state_tax_config`,
`payroll_state_bracket`); none is seeded by default. Change a row or add a new
`effective_year` to update rates — no code change. See the module README for the
seeded 2026 values and sources.

## Build & run notes

- Entities are registered via `@EntityScan` on `ChurchGeniusProApplication`
  (`com.churchgeniuspro.hibernate` **and** `com.churchgeniuspro.payroll.entity`).
- Hibernate `ddl-auto=update` creates the `payroll_*` tables on startup.
- Static pages are served from `target/classes/static/`, so changes under
  `src/main/resources/static/` require `./mvnw clean package` + restart.

```bash
./mvnw clean package -DskipTests && ./mvnw spring-boot:run
```

## Before production — important

- **Authorization is in place — review the policy.** Pages and APIs are gated to
  SuperAdmin/Admin/Accountant/Church via `RoleGuard.requirePayroll` (the single
  place to change who has access). Two notes: (1) the underlying `payroll-*.html`
  files in `static/` remain directly reachable by URL — as with every page in
  this app — but render no data without the gated `/api/payroll/**` calls; move
  them out of `static/` or add a resource guard if you must block direct access.
  (2) There is no separation-of-duties (the same authorized user can create and
  approve a run); add a stricter guard on `submit`/`approve` if your church
  requires it. These endpoints surface SSNs (masked), pay, and tax data.
- **Verify tax figures.** The seeded 2026 federal/FICA values should be
  re-checked against the final IRS publications and reviewed by a payroll/tax
  professional before running real payroll.
- The per-employee W-2 PDF is a readable **summary**, not the official IRS
  Copy A form; file official forms through SSA-approved channels / tax software.
- PII is intentionally limited to masked values (SSN/account last-4). Full-PII
  capture and encryption are out of scope and should be designed before storing
  real employee data.

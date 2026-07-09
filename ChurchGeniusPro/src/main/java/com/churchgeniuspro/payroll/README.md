# Payroll module (`com.churchgeniuspro.payroll`)

W-2 employee payroll, withholding, and paystub generation with configurable,
effective-dated tax tables. This iteration delivers the **data model + calculation
engine core** (per the agreed scope): entities, repositories, the pure withholding
engine seeded with verified 2026 federal/FICA figures, the run/approval/void
workflow with an audit trail, and year-end W-2 + reporting aggregation. REST
controllers, the paystub **PDF renderer**, and admin UI are intentionally left as
the next layer (see *Not yet implemented*).

## Package layout

```
payroll/
  model/        Enums: PayType, PayFrequency, FilingStatus, EarningType,
                DeductionScope, PayrollRunStatus
  config/       Pure, framework-free tax model: TaxBracket, TaxRateSchedule,
                FederalWithholdingConfig, FicaConfig, StateWithholdingConfig,
                Federal2026TaxData (seeded, verified 2026 values)
  engine/       Pure calculator + value objects: EarningLine, DeductionLine,
                W4Input, YtdAmounts, PaystubLineItem, PayrollCalculationInput,
                PayrollCalculationResult, PayrollCalculator
  entity/       JPA entities (multi-tenant app_client_id, Lombok @Data)
  repository/   Spring Data JpaRepository interfaces
  service/      TaxConfigService, PayScheduleService, PayrollService,
                PayrollReportingService (+ W2Box, PayPeriod DTOs)
```

The **engine and config packages have no Spring or JPA dependencies**, so the
math is unit-testable in isolation; the service layer maps entities into the
engine's value objects and persists the results.

## Calculation methodology

Federal income-tax withholding implements **IRS Publication 15-T, Worksheet 1A**
(Percentage Method for Automated Payroll Systems) for 2020-or-later Forms W-4:

```
annualWage   = periodFederalTaxableWage × payPeriodsPerYear + W-4 4(a) − W-4 4(b)
annualTax    = schedule(annualWage − standardDeductionAddBack)     // add-back $0 if Step 2 checked
annualTax    = max(0, annualTax − W-4 Step 3 credits)
federalWH    = max(0, annualTax / payPeriodsPerYear + W-4 Step 4(c))
```

The **Standard** schedules are used when the W-4 Step 2 box is unchecked (with a
$12,900 MFJ / $8,600 other standard-deduction add-back); the **Step 2 Checkbox**
schedules are used when it is checked (no add-back).

FICA respects prior year-to-date FICA wages: Social Security stops at the annual
wage base; Additional Medicare (0.9%) applies to the portion of wages carrying
cumulative YTD above $200,000.

A key correctness detail the model captures: **which** taxes a pre-tax deduction
is exempt from. Section 125 medical/dental/vision premiums reduce federal, state,
**and** FICA wages; a traditional 401(k) deferral reduces federal/state income-tax
wages but **not** FICA wages. See `DeductionLine.section125(...)` vs
`DeductionLine.retirement401k(...)` and the `reduces*` flags on
`DeductionDefinition`.

## Tax-rate sourcing (2026)

Seeded in `config/Federal2026TaxData.java`, verified at build time:

- Federal percentage-method schedules (Standard + Step-2, MFJ/Single/HoH): IRS
  **Publication 15-T (2026)**, reflecting the One Big Beautiful Bill Act
  (P.L. 119-21). https://www.irs.gov/publications/p15t
- Social Security: 6.2% employee, **$184,500** wage base (2026); Medicare 1.45%;
  Additional Medicare 0.9% over $200,000. IRS Topic 751 / SSA 2026 figures.

Internal consistency check (encoded in the data and verified): each Standard
zero-tax threshold = exactly 2× the Step-2-checkbox threshold for the same status
(e.g. Single: $8,600 + $7,500 = $16,100 = 2 × $8,050).

## Configurability — changing rates without code

Rates live in DB tables (`payroll_federal_bracket`, `payroll_federal_std_deduction`,
`payroll_fica_rate`, `payroll_state_tax_config`, `payroll_state_bracket`), keyed by
`effective_year`. `TaxConfigService`:

- `seed2026IfAbsent()` writes the verified 2026 rows on first use.
- `loadFederal(year)`, `loadFica(year)`, `loadState(year, stateCode)` build the
  pure config objects the engine consumes (falling back to the in-code 2026 data
  if a year hasn't been seeded).

To add **2027**: insert bracket/FICA rows for `effective_year = 2027` (or add an
analogous factory). To add a **state**: insert a `payroll_state_tax_config` row
(flat via `flatRate`, or bracketed via `payroll_state_bracket`); no state is
seeded by default.

## Workflow, history, audit

`PayrollService` drives `DRAFT → PENDING_APPROVAL → APPROVED → PAID`, with
`voidRun(...)` from any state. Every employee paystub is itemized into
`payroll_paystub_item` rows (earnings / pre-tax / tax / post-tax) with current and
YTD amounts; YTD is recomputed from prior non-voided stubs. Every significant
action writes a `payroll_audit_log` row. Paystubs snapshot employer/employee
detail and **masked** direct-deposit (last-4 only), so historical stubs stay
reproducible even as tax tables change.

## Reporting & W-2

`PayrollReportingService` provides the payroll register, employee earnings,
tax-liability summary, and **W-2 box aggregation** (`generateW2`): Box 1 from
federal taxable wages, Box 3 capped at the SS wage base, Box 5 uncapped Medicare
wages, Box 6 including Additional Medicare, plus state boxes 16–19.

## Validation status

- **Engine math: validated.** The Worksheet 1A algorithm and the seeded 2026
  schedules were reproduced and checked against 18 hand-computed expectations,
  covering each filing status, the Step-2 path, W-4 credits/extra withholding,
  the Section-125-vs-401(k) FICA distinction, the Social Security wage-base cap,
  and the Additional Medicare threshold — all pass.
- **Full `mvn compile`: NOT run here.** The build sandbox is offline (the Spring
  Boot parent POM isn't cached) and has only a JRE (no `javac`). Code was instead
  checked with a static accessor-resolution pass across all 52 files. Please run
  `./mvnw clean package -DskipTests` locally to confirm compilation; Hibernate
  `ddl-auto=update` will create the new `payroll_*` tables on startup.

## Quick usage sketch

```java
taxConfigService.seed2026IfAbsent();
PayrollRun run = payrollService.createRun(clientId, PayFrequency.BIWEEKLY,
        periodStart, periodEnd, payDate, "admin");
// salaried employee: earnings auto-derived; hourly: pass EarningLine.hourly(...)
payrollService.processEmployee(run.getId(), employeeId, List.of(), "admin");
payrollService.submitForApproval(run.getId(), "manager");
payrollService.approve(run.getId(), "owner");
W2Box w2 = reportingService.generateW2(clientId, employeeId, 2026);
```

## Not yet implemented (next layers)

- **Paystub PDF rendering** (format chosen: PDF). The `Paystub` + `PaystubItem`
  data and a `PayrollReportingService` read model are ready; a renderer (e.g. via
  the project's `pdf` skill / a `PaystubPdfService`) is the remaining step.
- **REST controllers** and **admin UI** (employee setup, run payroll, view stubs).
- Additional states; W-2 boxes 11–14 and codes; garnishment legal limits (CCPA);
  the supplemental-wage 22%/37% flat methods; and OBBBA qualified-tips / qualified-
  overtime deduction reporting (the 2026 W-4 / Pub 15-T additions).
- Tax figures should be re-verified against the final IRS PDF before production
  payroll, and reviewed by a payroll/tax professional.
```

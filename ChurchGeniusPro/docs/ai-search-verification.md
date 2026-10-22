# AI Search enhancement — verification report (Phase G, 2026-10-22)

Branch `feature1`, commits 13632f6 (A) · ae634ff (B) · 37ef96a (C) · d06b423 (D) · ce61827 (E) · a7aa2c5 (F) · Phase G.
Nothing has been released or deployed; no production data was touched.

## What was verified

| Area | How | Result |
|---|---|---|
| Authorization policy mirrors the page rules | `AiAccessPolicyTest` (10), `AiSearchEndpointSecurityTest` (9) | pass |
| Navigation catalogue never looser than the routes | `AiNavigationCatalogRouteCrossCheckTest` — reads every controller's `RoleGuard` calls (class-level `@RequestMapping` prefixes included) and compares roles / church / page key / opt-in key for 54 catalogue pages | pass; one real finding fixed (`/filesUpload` lacked `admin.files`) |
| Strict clientId isolation of every AI data path | `AiSearchTenantIsolationIT` — two tenants seeded in a real PostgreSQL; every structured intent answered for Tenant A contains nothing of Tenant B; every `ai*` repository method called with Tenant B's ids as Tenant A returns nothing; AI meeting creation writes only into the caller's tenant | pass (4 tests) |
| Bounded work per question | `AiSearchQueryBudgetTest` — repository statements per intent counted on mocks: ≤ 2 for single-entity intents, 2 + 2 batch lookups for assigned volunteers, 3 for Kids Ministry, 1 + 2 per campaign (list bounded by max-rows) | pass |
| Structured answers, period reports, meeting creation, UI | `AiStructuredAnswerServiceTest` (19), `AiMeetingCreateServiceTest` (18), `AiSearchUiPolishTest` (5), `AiAssistantChatPageTest` | pass |
| Every endpoint × every login type (live) | `scripts/ai-search-checks/ai-e2e-roles.cjs` on the demo tenant: SuperAdmin, Admin, Accountant, User, church, Member, Kids — 159 checks | pass |
| Member with AI + Directory, Kids with the AI flag, User minus `general.meetings` (live) | `ai-e2e-roles2.cjs` — 25 checks | pass |
| Response time (live, demo data) | `ai-timing.cjs` — median of 3 per intent | worst 21 ms; largest list response 2.1 KB |
| Response time at scale (SQL) | 200,000 income + 100,000 expense rows for one tenant in the IT database: income aggregate 22 ms, bounded rows (LIMIT 21) 6.5 ms, expense aggregate 18 ms, report GROUP BY 38 ms — all on `idx_income_tenant_date` / `idx_expense_tenant_date` | bounded |
| Full unit suite | `mvn package` | 2,697 run, 0 failures, 3 pre-existing skips |

## Rules the tests pin down

- Kids Portal: 401/403 on every AI endpoint (data, navigation, nav catalogue, help search, confirm) even with `more.aiassistant` set.
- Member portal: no AI unless `more.aiassistant` is explicitly true; then only member counts / birthdays via `member.directory`, portal-tab links only, no finance, no meetings, no creation.
- Church login: navigation only; every data intent denied.
- Staff: each module follows its page's role guard + alias-aware page key + plan feature; switching a page key off removes the answer, the link and (for meetings) creation within the 15 s permission refresh.
- Tenant: clientId/role/permission flags in the request body are ignored; every query carries the session's clientId.
- Creation: one-time meetings only, category + date required, confirm before write, single-use token, duplicate slot refused.
- Frontend: one turn at a time, 30 s watchdog, results bounded to 20 rows with a link to the page.

## Remaining risks

1. Acceptance scenarios 1–27 from the original requirements document could not be re-run verbatim in this session (the document text was not available); the live passes cover every intent, every login type and the navigation targets named in the phase logs.
2. `AiSearchTenantIsolationIT` needs Docker (or `CGP_IT_JDBC_URL`) — it is skipped in a plain `mvn test`, like the other `*IT` classes.
3. The catalogue cross-check reads guards statically; a route guarded through a helper it does not recognise is treated as "any staff login", which can only make the check stricter, never looser.
4. Pre-existing, untouched: `/api/pledges/campaigns` nullable-endDate query fails on PostgreSQL (`IncomeRepository.sumMemberSubSourceTotal`); single-slot member-name cache in `AiDataSearchService`; temporary-access / NTag sessions pass the AI feature gate as staff.
5. The 30 s client timeout is a constant in `ai-assistant.js`; the home page's own delegated search is outside it.

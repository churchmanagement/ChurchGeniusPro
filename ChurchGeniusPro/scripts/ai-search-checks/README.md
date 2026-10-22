# AI Search — browser checks (Phases A–G)

Playwright scripts that drive the running app at `http://localhost:8080` with the demo
tenant's logins (`grace.pastor_799791` SuperAdmin, `daniel.manager_799791` Admin,
`ruth.treasurer_799791` Accountant, `sam.wilson_799791` User, `church_799791` church login,
`member_john_5` Member, `child_lily_27` Kids). They are verification aids, not part of the
Maven build: run them by hand after `./scripts/build.sh` and a local start.

    npm i -D playwright            # once; uses the Chromium Playwright downloads (or set executablePath)
    node scripts/ai-search-checks/ai-e2e-roles.cjs    # every endpoint × every login type (159 checks)
    node scripts/ai-search-checks/ai-e2e-roles2.cjs   # Member with AI + Directory, Kids with the flag, User minus general.meetings
    node scripts/ai-search-checks/ai-timing.cjs       # median response time per intent
    node scripts/ai-search-checks/ai-ui-check.cjs     # Phase F: one turn at a time, 30 s watchdog, ARIA, mobile layout, help modal
    node scripts/ai-search-checks/ai-meeting-check.cjs# Phase E: ask → confirm → create, token reuse, duplicate, cancel (creates and deletes 2 meetings)
    node scripts/ai-search-checks/ai-nav-check.cjs    # Phase D navigation targets
    node scripts/ai-search-checks/ai-data-check.cjs   # Phase B data intents
    node scripts/ai-search-checks/ai-report-check.cjs # Phase C period reports vs the report endpoints
    node scripts/ai-search-checks/ai-gate-check.cjs   # Phase A feature gate
    node scripts/ai-search-checks/ai-synonym-meeting-links-check.cjs  # synonyms (API + bar), the 4-question meeting flow + link, public-link names (creates and deletes 1 meeting)
    node scripts/ai-search-checks/ai-synonym-perm-check.cjs           # the new wordings stay denied for User / Member / Accountant without Income (needs the privilege rows below)

`ai-e2e-roles2.cjs` expects these temporary privilege rows (restore afterwards):

    update family_member set member_privileges='{"more.aiassistant":true,"member.directory":true,"member.family":true}' where id=5;
    update family_member set member_privileges='{"more.aiassistant":true}' where id=27;
    update app_user set privileges='{"general.meetings":false}' where user_id='USRb480eaeb-121e-4cae-8dcc-a49f383056f7';

`ai-synonym-perm-check.cjs` expects (restore afterwards):

    update family_member set member_privileges='{"more.aiassistant":true,"member.directory":true}' where id=5;
    update app_user set privileges='{"accounting.income":false}' where user_id='USR6072faa0-4ccc-416d-bc27-50d2a73b3820';

The database-level tenant isolation test is a Maven integration test:
`AiSearchTenantIsolationIT` (runs on `verify` with Docker, or against `CGP_IT_JDBC_URL`).

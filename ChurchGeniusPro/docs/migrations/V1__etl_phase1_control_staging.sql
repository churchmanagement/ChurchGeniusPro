-- =============================================================================
-- ETL Pipeline — Phase 1: control + staging tables
-- =============================================================================
-- REFERENCE / DOCUMENTATION ONLY.
--
-- The running app uses Hibernate with spring.jpa.hibernate.ddl-auto=update, so
-- these tables are auto-created from the JPA entities on startup. This file is
-- the human-readable schema of record (review, code review, and a starting point
-- if the project later adopts Flyway/Liquibase). It mirrors:
--   hibernate/ImportRun, ImportBatch, MappingRule, ImportAudit,
--   hibernate/StagingRaw, StagingFamily, StagingIncome, StagingExpense
--
-- Tenant model: every table carries client_id. A run is bound to ONE tenant at
-- creation; all staging/audit rows copy that client_id. Nothing here writes to
-- the live family/income/expense tables — that is a later phase.
-- Target dialect: PostgreSQL.
-- =============================================================================

-- ─────────────────────────── control tables ───────────────────────────

CREATE TABLE IF NOT EXISTS import_run (
    id            BIGSERIAL    PRIMARY KEY,
    client_id     VARCHAR(64)  NOT NULL,            -- the ONLY tenant this run touches
    created_by    VARCHAR(128) NOT NULL,
    source_label  VARCHAR(256),
    status        VARCHAR(24)  NOT NULL,            -- see common.ImportRunStatus
    created_date  TIMESTAMP    NOT NULL,
    updated_date  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_import_run_tenant ON import_run (client_id);

CREATE TABLE IF NOT EXISTS import_batch (
    id             BIGSERIAL    PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    client_id      VARCHAR(64)  NOT NULL,
    target_table   VARCHAR(32)  NOT NULL,           -- family | income | expense
    status         VARCHAR(16)  NOT NULL,           -- STAGED|APPROVED|LOADED|ROLLED_BACK|FAILED
    approved_by    VARCHAR(128),
    approved_date  TIMESTAMP,
    inserted_count INTEGER,
    updated_count  INTEGER,
    skipped_count  INTEGER,
    failed_count   INTEGER,
    created_date   TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_import_batch_run ON import_batch (run_id);

CREATE TABLE IF NOT EXISTS mapping_rule (
    id             BIGSERIAL    PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    target_table   VARCHAR(64)  NOT NULL,
    target_column  VARCHAR(64)  NOT NULL,
    source_table   VARCHAR(128),
    source_column  VARCHAR(128),
    transform      VARCHAR(255),
    confidence     NUMERIC(4,3),                    -- 0.000 .. 1.000
    status         VARCHAR(16)  NOT NULL,           -- AUTO|NEEDS_REVIEW|CONFIRMED|REJECTED
    rationale      TEXT,
    decided_by     VARCHAR(128),
    CONSTRAINT uq_mapping_rule UNIQUE (run_id, target_table, target_column)
);

CREATE TABLE IF NOT EXISTS import_audit (
    id            BIGSERIAL    PRIMARY KEY,         -- append-only; never updated/deleted
    run_id        BIGINT       NOT NULL,
    batch_id      BIGINT,
    client_id     VARCHAR(64)  NOT NULL,
    actor         VARCHAR(128) NOT NULL,
    action        VARCHAR(48)  NOT NULL,
    detail        TEXT,                             -- JSON
    created_date  TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_import_audit_run ON import_audit (run_id);

-- ─────────────────────────── staging tables ───────────────────────────

CREATE TABLE IF NOT EXISTS staging_raw (
    id             BIGSERIAL    PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    client_id      VARCHAR(64)  NOT NULL,
    source_table   VARCHAR(128),
    source_row_id  VARCHAR(128),
    payload        TEXT         NOT NULL,           -- exact source row as JSON
    created_date   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS staging_family (
    id              BIGSERIAL    PRIMARY KEY,
    run_id          BIGINT       NOT NULL,
    client_id       VARCHAR(64)  NOT NULL,
    source_row_id   VARCHAR(128),
    family_key      VARCHAR(128),                   -- links income/expense child rows
    source_payload  TEXT         NOT NULL,
    -- target-shaped (mirror family_member) --
    first_name      VARCHAR(255),
    last_name       VARCHAR(255),
    other_name      VARCHAR(255),                   -- nickname
    email           VARCHAR(255),
    phone           VARCHAR(64),
    gender          VARCHAR(16),
    member_type     VARCHAR(32),
    role            VARCHAR(64),
    address1        VARCHAR(255),
    address2        VARCHAR(255),
    city            VARCHAR(128),
    state           VARCHAR(64),
    country         VARCHAR(64),
    pin_code        VARCHAR(32),
    birthday_month  INTEGER,
    birthday_day    INTEGER,
    birthday_year   INTEGER,
    phone_private   BOOLEAN,
    email_private   BOOLEAN,
    address_private BOOLEAN,
    -- bookkeeping --
    row_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    validation_msgs TEXT,
    dedupe_match_id BIGINT,
    dedupe_action   VARCHAR(16),
    target_id       BIGINT,
    batch_id        BIGINT,
    created_date    TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_stg_family_run    ON staging_family (run_id, row_status);
CREATE INDEX IF NOT EXISTS ix_stg_family_tenant ON staging_family (client_id);

CREATE TABLE IF NOT EXISTS staging_income (
    id                 BIGSERIAL    PRIMARY KEY,
    run_id             BIGINT       NOT NULL,
    client_id          VARCHAR(64)  NOT NULL,
    source_row_id      VARCHAR(128),
    source_payload     TEXT         NOT NULL,
    family_link        VARCHAR(128),                -- SOURCE family key, resolved at load
    -- target-shaped --
    amount             NUMERIC(14,2),
    income_date        DATE,
    source_name        VARCHAR(128),
    sub_source_name    VARCHAR(128),
    purpose_name       VARCHAR(128),
    method             VARCHAR(64),
    reference_no       VARCHAR(128),
    notes              TEXT,
    -- bookkeeping --
    row_status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    validation_msgs    TEXT,
    dedupe_match_id    BIGINT,
    dedupe_action      VARCHAR(16),
    resolved_member_id BIGINT,
    target_id          BIGINT,
    batch_id           BIGINT,
    created_date       TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_stg_income_run    ON staging_income (run_id, row_status);
CREATE INDEX IF NOT EXISTS ix_stg_income_tenant ON staging_income (client_id);

CREATE TABLE IF NOT EXISTS staging_expense (
    id                 BIGSERIAL    PRIMARY KEY,
    run_id             BIGINT       NOT NULL,
    client_id          VARCHAR(64)  NOT NULL,
    source_row_id      VARCHAR(128),
    source_payload     TEXT         NOT NULL,
    family_link        VARCHAR(128),
    -- target-shaped --
    amount             NUMERIC(14,2),
    expense_date       DATE,
    category           VARCHAR(128),
    purpose_name       VARCHAR(128),
    payee              VARCHAR(255),
    method             VARCHAR(64),
    reference_no       VARCHAR(128),
    notes              TEXT,
    -- bookkeeping --
    row_status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    validation_msgs    TEXT,
    dedupe_match_id    BIGINT,
    dedupe_action      VARCHAR(16),
    resolved_member_id BIGINT,
    target_id          BIGINT,
    batch_id           BIGINT,
    created_date       TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_stg_expense_run    ON staging_expense (run_id, row_status);
CREATE INDEX IF NOT EXISTS ix_stg_expense_tenant ON staging_expense (client_id);

-- =============================================================================
-- End Phase 1. Later phases add: profiling, AI+deterministic mapping population,
-- transform/validate, preview/approve, batch load (INSERT-or-SKIP, no overwrite),
-- and batch rollback (soft-delete + before-images).
-- =============================================================================

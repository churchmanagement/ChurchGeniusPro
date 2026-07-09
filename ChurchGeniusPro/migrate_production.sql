-- ============================================================
-- Production migration script
-- Run this against the production PostgreSQL database
-- before deploying the latest JAR.
-- All statements are idempotent (IF NOT EXISTS / DO NOTHING).
-- ============================================================

-- ── family_member: columns added during development ──────────────────────────

ALTER TABLE family_member
    ADD COLUMN IF NOT EXISTS photo_thumbnail        TEXT,
    ADD COLUMN IF NOT EXISTS disable_alerts         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS include_contributions  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS other_name             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS comments               TEXT,
    ADD COLUMN IF NOT EXISTS same_as_family_address BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS member_ref             VARCHAR(255);

-- ── church_event: created_by column ──────────────────────────────────────────

ALTER TABLE church_event
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);

-- ── reminder_sent_log: deduplication table + sequence ────────────────────────

CREATE SEQUENCE IF NOT EXISTS reminder_sent_log_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS reminder_sent_log (
    id              BIGINT NOT NULL DEFAULT nextval('reminder_sent_log_id_seq'),
    app_client_id   VARCHAR(64)  NOT NULL,
    reminder_type   VARCHAR(64)  NOT NULL,
    reference_key   VARCHAR(128) NOT NULL,
    sent_date       DATE         NOT NULL,
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_reminder_sent_log PRIMARY KEY (id),
    CONSTRAINT uq_reminder_sent_log
        UNIQUE (app_client_id, reminder_type, reference_key, sent_date)
);

CREATE INDEX IF NOT EXISTS idx_rsl_client_type_date
    ON reminder_sent_log (app_client_id, reminder_type, sent_date);

-- ── push_notification_log ─────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS push_notification_log (
    id            BIGSERIAL    PRIMARY KEY,
    app_client_id VARCHAR(64)  NOT NULL,
    title         VARCHAR(255),
    body          TEXT,
    url           VARCHAR(500),
    tag           VARCHAR(128),
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_read       BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_pnl_client_sent
    ON push_notification_log (app_client_id, sent_at DESC);

-- ── volunteer tables ──────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS volunteer_role (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    description   TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS volunteer_profile (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    member_id     INTEGER,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255),
    phone         VARCHAR(64),
    notes         TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS volunteer_profile_role (
    id                  BIGSERIAL PRIMARY KEY,
    volunteer_id        BIGINT NOT NULL,
    role_id             BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS volunteer_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    volunteer_id  BIGINT      NOT NULL,
    event_name    VARCHAR(255),
    event_date    DATE,
    role          VARCHAR(255),
    notes         TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS volunteer_attendance (
    id              BIGSERIAL   PRIMARY KEY,
    app_client_id   VARCHAR(64) NOT NULL,
    volunteer_id    BIGINT      NOT NULL,
    assignment_id   BIGINT,
    attended        BOOLEAN     NOT NULL DEFAULT FALSE,
    attended_date   DATE,
    notes           TEXT,
    created_date    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── event_volunteer / event_volunteer_role ───────────────────────────────────

CREATE TABLE IF NOT EXISTS event_volunteer (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    event_id      BIGINT      NOT NULL,
    member_id     INTEGER,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255),
    phone         VARCHAR(64),
    status        VARCHAR(64),
    notes         TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS event_volunteer_role (
    id                   BIGSERIAL PRIMARY KEY,
    event_volunteer_id   BIGINT NOT NULL,
    role_id              BIGINT,
    role_name            VARCHAR(255)
);

-- ── follow_up ─────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS follow_up (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    member_id     INTEGER,
    assigned_to   VARCHAR(255),
    subject       VARCHAR(255),
    notes         TEXT,
    status        VARCHAR(64),
    due_date      DATE,
    completed_at  TIMESTAMPTZ,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── kids ministry tables ──────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS km_classroom (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    age_range     VARCHAR(64),
    capacity      INTEGER,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS km_child (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    date_of_birth DATE,
    allergies     TEXT,
    notes         TEXT,
    classroom_id  BIGINT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS km_authorized_pickup (
    id         BIGSERIAL PRIMARY KEY,
    child_id   BIGINT NOT NULL,
    name       VARCHAR(255),
    phone      VARCHAR(64),
    relation   VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS km_checkin (
    id           BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    child_id     BIGINT      NOT NULL,
    classroom_id BIGINT,
    checked_in_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    checked_out_at TIMESTAMPTZ,
    checked_in_by  VARCHAR(255),
    checked_out_by VARCHAR(255),
    notes          TEXT
);

CREATE TABLE IF NOT EXISTS km_volunteer (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    member_id     INTEGER,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255),
    phone         VARCHAR(64),
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS km_volunteer_role (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS km_volunteer_role_assignment (
    id             BIGSERIAL PRIMARY KEY,
    km_volunteer_id BIGINT   NOT NULL,
    km_role_id      BIGINT   NOT NULL
);

-- ── worship planning tables ───────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS worship_group (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    description   TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS worship_instrument (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS worship_group_member (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT    NOT NULL,
    member_id      INTEGER,
    first_name     VARCHAR(255),
    last_name      VARCHAR(255),
    instrument_id  BIGINT,
    role           VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS worship_song (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    title         VARCHAR(255),
    author        VARCHAR(255),
    key_signature VARCHAR(32),
    tempo         VARCHAR(64),
    notes         TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS worship_assignment (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    assignment_date DATE,
    notes         TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS worship_assignment_member (
    id              BIGSERIAL PRIMARY KEY,
    assignment_id   BIGINT NOT NULL,
    member_id       INTEGER,
    first_name      VARCHAR(255),
    last_name       VARCHAR(255),
    instrument_id   BIGINT,
    role            VARCHAR(128)
);

-- ── sunday school tables ──────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS ss_class (
    id            BIGSERIAL   PRIMARY KEY,
    app_client_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255),
    description   TEXT,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ss_teacher (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT    NOT NULL,
    member_id     INTEGER,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS ss_student (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT    NOT NULL,
    member_id     INTEGER,
    first_name    VARCHAR(255),
    last_name     VARCHAR(255),
    email         VARCHAR(255),
    enrolled_date DATE
);

CREATE TABLE IF NOT EXISTS ss_lesson (
    id            BIGSERIAL   PRIMARY KEY,
    class_id      BIGINT      NOT NULL,
    title         VARCHAR(255),
    content       TEXT,
    lesson_date   DATE,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ss_exam (
    id            BIGSERIAL   PRIMARY KEY,
    class_id      BIGINT      NOT NULL,
    title         VARCHAR(255),
    exam_date     DATE,
    delete_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ss_question (
    id         BIGSERIAL PRIMARY KEY,
    exam_id    BIGINT    NOT NULL,
    question   TEXT,
    option_a   VARCHAR(500),
    option_b   VARCHAR(500),
    option_c   VARCHAR(500),
    option_d   VARCHAR(500),
    answer     VARCHAR(4)
);

CREATE TABLE IF NOT EXISTS ss_submission (
    id            BIGSERIAL   PRIMARY KEY,
    exam_id       BIGINT      NOT NULL,
    student_id    BIGINT,
    submitted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    score         INTEGER
);

CREATE TABLE IF NOT EXISTS ss_answer (
    id             BIGSERIAL PRIMARY KEY,
    submission_id  BIGINT    NOT NULL,
    question_id    BIGINT    NOT NULL,
    selected       VARCHAR(4)
);

CREATE TABLE IF NOT EXISTS ss_note (
    id            BIGSERIAL   PRIMARY KEY,
    class_id      BIGINT      NOT NULL,
    student_id    BIGINT,
    content       TEXT,
    created_date  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ss_file (
    id            BIGSERIAL   PRIMARY KEY,
    class_id      BIGINT      NOT NULL,
    file_name     VARCHAR(255),
    file_data     TEXT,
    uploaded_date TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── ss_exam: columns added during development ────────────────────────────────

ALTER TABLE ss_exam
    ADD COLUMN IF NOT EXISTS client_id          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS exam_title         VARCHAR(300),
    ADD COLUMN IF NOT EXISTS total_questions    INTEGER,
    ADD COLUMN IF NOT EXISTS required_questions INTEGER,
    ADD COLUMN IF NOT EXISTS duration_minutes   INTEGER,
    ADD COLUMN IF NOT EXISTS status             VARCHAR(50) NOT NULL DEFAULT 'Draft',
    ADD COLUMN IF NOT EXISTS copy_to_hoh        BOOLEAN     NOT NULL DEFAULT FALSE;

-- ss_exam sequence (used by the entity generator)
CREATE SEQUENCE IF NOT EXISTS ss_exam_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

-- ── mid_reg_meet + rsvp ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS mid_reg_meet (
    id                    BIGSERIAL    PRIMARY KEY,
    app_client_id         VARCHAR(64)  NOT NULL,
    title                 VARCHAR(255),
    description           TEXT,
    start_date            DATE,
    end_date              DATE,
    location              VARCHAR(500),
    registration_deadline DATE,
    max_attendees         INTEGER,
    include_transaction_fee BOOLEAN    NOT NULL DEFAULT FALSE,
    flyer_data            TEXT,
    delete_flag           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_date          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS mid_reg_meet_rsvp (
    id              BIGSERIAL    PRIMARY KEY,
    meet_id         BIGINT       NOT NULL,
    app_client_id   VARCHAR(64),
    first_name      VARCHAR(255),
    last_name       VARCHAR(255),
    email           VARCHAR(255),
    phone           VARCHAR(64),
    notes           TEXT,
    amount_paid     NUMERIC(10,2),
    payment_status  VARCHAR(64),
    stripe_session  VARCHAR(500),
    created_date    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ── prayer_schedule: last_sent_date column ────────────────────────────────────

ALTER TABLE prayer_schedule
    ADD COLUMN IF NOT EXISTS last_sent_date DATE;


-- ── Event Registration Reminder (2026-07-08) ─────────────────────────────────
-- Per-event "remind non-registered contacts to register" feature:
-- flags on church_event + contact list + attempt/audit log.

ALTER TABLE church_event
    ADD COLUMN IF NOT EXISTS registration_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS registration_reminder_days    INTEGER;

CREATE SEQUENCE IF NOT EXISTS event_registration_reminder_contact_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS event_registration_reminder_contact (
    id            INTEGER      PRIMARY KEY DEFAULT nextval('event_registration_reminder_contact_id_seq'),
    event_id      INTEGER      NOT NULL,
    email         VARCHAR(255),
    phone         VARCHAR(40),
    app_client_id VARCHAR(255),
    created_date  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_errc_event
    ON event_registration_reminder_contact (event_id);

CREATE SEQUENCE IF NOT EXISTS event_registration_reminder_log_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS event_registration_reminder_log (
    id             BIGINT       PRIMARY KEY DEFAULT nextval('event_registration_reminder_log_id_seq'),
    event_id       INTEGER      NOT NULL,
    contact_id     INTEGER,
    channel        VARCHAR(10)  NOT NULL,
    recipient      VARCHAR(255),
    recipient_norm VARCHAR(255),
    status         VARCHAR(10)  NOT NULL,
    reason         VARCHAR(255),
    days_before    INTEGER,
    app_client_id  VARCHAR(255),
    created_date   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_errl_event
    ON event_registration_reminder_log (event_id);
CREATE INDEX IF NOT EXISTS idx_errl_event_recip
    ON event_registration_reminder_log (event_id, channel, recipient_norm);

-- Invitations share the log table; message_type separates INVITE from REMINDER
-- rows so duplicate-prevention is scoped per message kind.
ALTER TABLE event_registration_reminder_log
    ADD COLUMN IF NOT EXISTS message_type VARCHAR(10) DEFAULT 'REMINDER';
UPDATE event_registration_reminder_log SET message_type = 'REMINDER' WHERE message_type IS NULL;

-- ── Public marketing website visitor tracking (2026-07-08) ───────────────────
-- One row per visit to the public /web/* pages; aggregated on the Service
-- Admin dashboard. Not tenant-scoped (pages exist outside any church account).

CREATE SEQUENCE IF NOT EXISTS public_page_visit_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS public_page_visit (
    id         BIGINT       PRIMARY KEY DEFAULT nextval('public_page_visit_id_seq'),
    page       VARCHAR(50)  NOT NULL,
    visited_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    user_agent TEXT,
    ip_address VARCHAR(64),
    referrer   VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_ppv_page
    ON public_page_visit (page);
CREATE INDEX IF NOT EXISTS idx_ppv_page_date
    ON public_page_visit (page, visited_at);


-- ── Done ──────────────────────────────────────────────────────────────────────

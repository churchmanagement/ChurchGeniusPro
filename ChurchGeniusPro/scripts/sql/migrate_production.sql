-- ============================================================
-- Production migration script
-- Run this against the production PostgreSQL database
-- before deploying the latest JAR.
-- All statements are idempotent (IF NOT EXISTS / DO NOTHING).
--
-- Every CREATE TABLE block below is GENERATED from the entity model
-- (database audit C3 / P5, 16 Sep 2026): the columns, types, nullability,
-- keys, indexes and sequences are exactly what Hibernate creates from the
-- @Entity classes, so a database this script creates first and the
-- application then starts against (ddl-auto=update) ends up identical to
-- one Hibernate created on its own. Do not edit those blocks by hand — change
-- the entity and regenerate (gen_ddl.py, delivered with the September 2026
-- database audit, reads a schema Hibernate created and rewrites the blocks in
-- place; MigrationScriptIT proves the result against PostgreSQL).
--
-- The script assumes an existing production database: the ALTER TABLE
-- statements for the core tables (family_member, church_event, income, …)
-- expect those tables to exist and fail harmlessly on an empty database,
-- where the application creates them on first start.
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


CREATE SEQUENCE IF NOT EXISTS reminder_sent_log_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS reminder_sent_log (
    sent_date     date           NOT NULL,
    id            bigint         NOT NULL,
    sent_at       timestamptz(6) NOT NULL,
    app_client_id varchar(64)    NOT NULL,
    reminder_type varchar(64)    NOT NULL,
    reference_key varchar(128)   NOT NULL,
    CONSTRAINT reminder_sent_log_pkey PRIMARY KEY (id),
    CONSTRAINT uq_reminder_sent_log UNIQUE (app_client_id, reminder_type, reference_key, sent_date)
);
CREATE INDEX IF NOT EXISTS idx_rsl_client_type_date ON reminder_sent_log USING btree (app_client_id, reminder_type, sent_date);

-- ── push_notification_log ─────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS push_notification_log_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS push_notification_log (
    id            bigint         NOT NULL,
    read_at       timestamptz(6),
    sent_at       timestamptz(6) NOT NULL,
    user_type     varchar(16),
    app_client_id varchar(64),
    tag           varchar(64),
    user_key      varchar(128)   NOT NULL,
    url           varchar(512),
    body          text,
    title         varchar(255)   NOT NULL,
    CONSTRAINT push_notification_log_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_pnl_app_client ON push_notification_log USING btree (app_client_id);
CREATE INDEX IF NOT EXISTS idx_pnl_sent_at ON push_notification_log USING btree (sent_at);
CREATE INDEX IF NOT EXISTS idx_pnl_user_key ON push_notification_log USING btree (user_key);

CREATE INDEX IF NOT EXISTS idx_pnl_client_sent
    ON push_notification_log (app_client_id, sent_at DESC);

-- ── volunteer tables ──────────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS volunteer_role_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS volunteer_role (
    delete_flag   boolean      NOT NULL,
    max_capacity  integer,
    id            bigint       NOT NULL,
    app_client_id varchar(100) NOT NULL,
    ministry      varchar(100),
    role_name     varchar(200) NOT NULL,
    description   text,
    CONSTRAINT volunteer_role_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS volunteer_profile_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS volunteer_profile (
    delete_flag      boolean      NOT NULL,
    family_member_id integer      NOT NULL,
    joined_date      date,
    id               bigint       NOT NULL,
    status           varchar(20),
    app_client_id    varchar(100) NOT NULL,
    availability     text,
    notes            text,
    skills           text,
    CONSTRAINT volunteer_profile_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS volunteer_profile_role_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS volunteer_profile_role (
    delete_flag          boolean      NOT NULL,
    assigned_at          timestamp(6),
    id                   bigint       NOT NULL,
    role_id              bigint       NOT NULL,
    volunteer_profile_id bigint       NOT NULL,
    app_client_id        varchar(100) NOT NULL,
    CONSTRAINT volunteer_profile_role_pkey PRIMARY KEY (id),
    CONSTRAINT uq_vol_profile_role UNIQUE (app_client_id, volunteer_profile_id, role_id)
);

CREATE SEQUENCE IF NOT EXISTS volunteer_assignment_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS volunteer_assignment (
    delete_flag       boolean      NOT NULL,
    event_date        date,
    event_id          integer,
    family_member_id  integer      NOT NULL,
    notification_sent boolean      NOT NULL,
    created_at        timestamp(6),
    id                bigint       NOT NULL,
    responded_at      timestamp(6),
    role_id           bigint,
    assignment_status varchar(30),
    app_client_id     varchar(100) NOT NULL,
    shift_time        varchar(100),
    event_label       varchar(300),
    notes             text,
    CONSTRAINT volunteer_assignment_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS volunteer_attendance_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS volunteer_attendance (
    assignment_id bigint       NOT NULL,
    checkin_time  timestamp(6),
    checkout_time timestamp(6),
    id            bigint       NOT NULL,
    app_client_id varchar(100) NOT NULL,
    marked_by     varchar(200),
    CONSTRAINT volunteer_attendance_pkey PRIMARY KEY (id)
);

-- ── event_volunteer / event_volunteer_role ───────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS event_volunteer_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS event_volunteer (
    delete_flag      boolean      NOT NULL,
    event_id         integer      NOT NULL,
    family_member_id integer,
    is_manual        boolean      NOT NULL,
    created_at       timestamp(6),
    id               bigint       NOT NULL,
    status           varchar(30),
    phone            varchar(50),
    app_client_id    varchar(100) NOT NULL,
    first_name       varchar(100),
    last_name        varchar(100),
    email            varchar(255),
    notes            text,
    CONSTRAINT event_volunteer_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS event_volunteer_role_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS event_volunteer_role (
    delete_flag        boolean      NOT NULL,
    created_at         timestamp(6),
    event_volunteer_id bigint       NOT NULL,
    id                 bigint       NOT NULL,
    app_client_id      varchar(100) NOT NULL,
    role_name          varchar(200) NOT NULL,
    CONSTRAINT event_volunteer_role_pkey PRIMARY KEY (id)
);

-- ── follow_up ─────────────────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS follow_up_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS follow_up (
    delete_flag  boolean      NOT NULL,
    due_date     date,
    created_at   timestamp(6) NOT NULL,
    id           bigint       NOT NULL,
    linked_id    bigint,
    updated_at   timestamp(6),
    priority     varchar(10),
    status       varchar(15),
    linked_type  varchar(20),
    client_id    varchar(100) NOT NULL,
    assigned_to  varchar(150),
    created_by   varchar(150),
    description  text,
    linked_label varchar(255),
    title        varchar(255) NOT NULL,
    CONSTRAINT follow_up_pkey PRIMARY KEY (id)
);

-- ── kids ministry tables ──────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS km_classroom_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_classroom (
    capacity    integer,
    delete_flag boolean      NOT NULL,
    max_age     integer,
    min_age     integer,
    id          bigint       NOT NULL,
    room_number varchar(50),
    client_id   varchar(100) NOT NULL,
    class_name  varchar(200) NOT NULL,
    description text,
    CONSTRAINT km_classroom_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_child_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_child (
    created_date            date,
    delete_flag             boolean      NOT NULL,
    dob                     date,
    family_member_id        integer,
    inactive                boolean      NOT NULL,
    pickup_alerts_enabled   boolean,
    classroom_id            bigint,
    id                      bigint       NOT NULL,
    registered_at           timestamp(6),
    gender                  varchar(20),
    emergency_contact_phone varchar(30),
    parent_phone            varchar(30),
    grade                   varchar(50),
    client_id               varchar(100) NOT NULL,
    first_name              varchar(100) NOT NULL,
    last_name               varchar(100) NOT NULL,
    registered_by           varchar(100),
    emergency_contact_name  varchar(200),
    parent_email            varchar(200),
    parent_name             varchar(200),
    allergies               text,
    form_image_data         text,
    medical_notes           text,
    photo_url               text,
    CONSTRAINT km_child_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_authorized_pickup_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_authorized_pickup (
    delete_flag  boolean      NOT NULL,
    child_id     bigint       NOT NULL,
    id           bigint       NOT NULL,
    phone        varchar(30),
    client_id    varchar(100) NOT NULL,
    relationship varchar(100),
    person_name  varchar(200) NOT NULL,
    CONSTRAINT km_authorized_pickup_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_checkin_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_checkin (
    guardian_member_id     integer,
    checkin_time           timestamp(6),
    checkout_time          timestamp(6),
    child_id               bigint,
    classroom_id           bigint,
    id                     bigint       NOT NULL,
    last_alert_sent_at     timestamp(6),
    pickup_deadline        timestamp(6),
    snooze_until           timestamp(6),
    family_checkin_code    varchar(20),
    security_code          varchar(20),
    checkout_method        varchar(30),
    pickup_token           varchar(64),
    checked_out_user       varchar(100),
    client_id              varchar(100) NOT NULL,
    checked_out_by         varchar(200),
    notes                  text,
    pickup_extension_notes text,
    CONSTRAINT km_checkin_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_volunteer_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_volunteer (
    delete_flag      boolean      NOT NULL,
    family_member_id integer,
    inactive         boolean      NOT NULL,
    is_manual        boolean      NOT NULL,
    classroom_id     bigint,
    id               bigint       NOT NULL,
    phone            varchar(30),
    status           varchar(30),
    client_id        varchar(100) NOT NULL,
    first_name       varchar(100),
    last_name        varchar(100),
    role             varchar(100),
    email            varchar(200),
    name             varchar(200) NOT NULL,
    notes            text,
    CONSTRAINT km_volunteer_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_volunteer_role_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_volunteer_role (
    delete_flag boolean      NOT NULL,
    id          bigint       NOT NULL,
    client_id   varchar(100) NOT NULL,
    role_name   varchar(100) NOT NULL,
    CONSTRAINT km_volunteer_role_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS km_volunteer_role_assignment_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS km_volunteer_role_assignment (
    delete_flag  boolean      NOT NULL,
    id           bigint       NOT NULL,
    volunteer_id bigint       NOT NULL,
    client_id    varchar(100) NOT NULL,
    role_name    varchar(100) NOT NULL,
    CONSTRAINT km_volunteer_role_assignment_pkey PRIMARY KEY (id)
);

-- ── worship planning tables ───────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS worship_group_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_group (
    delete_flag boolean      NOT NULL,
    id          bigint       NOT NULL,
    client_id   varchar(100) NOT NULL,
    group_name  varchar(200) NOT NULL,
    CONSTRAINT worship_group_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS worship_instrument_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_instrument (
    delete_flag     boolean      NOT NULL,
    group_id        bigint       NOT NULL,
    id              bigint       NOT NULL,
    instrument_name varchar(200) NOT NULL,
    CONSTRAINT worship_instrument_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_worship_instrument_group_id ON worship_instrument USING btree (group_id, delete_flag);

CREATE SEQUENCE IF NOT EXISTS worship_group_member_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_group_member (
    delete_flag    boolean      NOT NULL,
    rotation_order integer,
    id             bigint       NOT NULL,
    instrument_id  bigint       NOT NULL,
    member_name    varchar(200) NOT NULL,
    CONSTRAINT worship_group_member_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_worship_group_member_instrument_id ON worship_group_member USING btree (instrument_id, delete_flag);

CREATE SEQUENCE IF NOT EXISTS worship_song_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_song (
    delete_flag  boolean      NOT NULL,
    is_heading   boolean      NOT NULL,
    service_date date,
    sort_order   integer,
    group_id     bigint       NOT NULL,
    id           bigint       NOT NULL,
    client_id    varchar(100) NOT NULL,
    song_title   varchar(500) NOT NULL,
    CONSTRAINT worship_song_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_worship_song_client_group_date ON worship_song USING btree (client_id, group_id, service_date, delete_flag);

CREATE SEQUENCE IF NOT EXISTS worship_assignment_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_assignment (
    assignment_date date         NOT NULL,
    delete_flag     boolean      NOT NULL,
    group_id        bigint       NOT NULL,
    id              bigint       NOT NULL,
    assignment_type varchar(20),
    client_id       varchar(100) NOT NULL,
    CONSTRAINT worship_assignment_pkey PRIMARY KEY (id),
    CONSTRAINT uq_worship_assignment_client_group_date UNIQUE (client_id, group_id, assignment_date)
);
CREATE INDEX IF NOT EXISTS idx_worship_assignment_client_date ON worship_assignment USING btree (client_id, assignment_date);
CREATE INDEX IF NOT EXISTS idx_worship_assignment_client_flag ON worship_assignment USING btree (client_id, delete_flag);

CREATE SEQUENCE IF NOT EXISTS worship_assignment_member_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS worship_assignment_member (
    sort_order    integer,
    assignment_id bigint       NOT NULL,
    id            bigint       NOT NULL,
    instrument_id bigint       NOT NULL,
    member_name   varchar(200) NOT NULL,
    CONSTRAINT worship_assignment_member_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_worship_assignment_member_assignment_id ON worship_assignment_member USING btree (assignment_id);
CREATE INDEX IF NOT EXISTS idx_worship_assignment_member_instrument_id ON worship_assignment_member USING btree (instrument_id);

-- ── sunday school tables ──────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS ss_class_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_class (
    delete_flag boolean       NOT NULL,
    id          bigint        NOT NULL,
    client_id   varchar(100)  NOT NULL,
    class_name  varchar(200)  NOT NULL,
    description varchar(1000),
    CONSTRAINT ss_class_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_teacher_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_teacher (
    delete_flag  boolean      NOT NULL,
    class_id     bigint       NOT NULL,
    id           bigint       NOT NULL,
    client_id    varchar(100) NOT NULL,
    member_ref   varchar(100),
    email        varchar(200),
    teacher_name varchar(200) NOT NULL,
    CONSTRAINT ss_teacher_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_student_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_student (
    delete_flag      boolean      NOT NULL,
    family_member_id integer,
    class_id         bigint       NOT NULL,
    id               bigint       NOT NULL,
    teacher_id       bigint       NOT NULL,
    client_id        varchar(100) NOT NULL,
    member_ref       varchar(100),
    contact_email    varchar(200),
    student_name     varchar(200) NOT NULL,
    CONSTRAINT ss_student_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_lesson_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_lesson (
    delete_flag  boolean       NOT NULL,
    lesson_date  date,
    sort_order   integer,
    class_id     bigint        NOT NULL,
    id           bigint        NOT NULL,
    student_id   bigint        NOT NULL,
    status       varchar(50)   NOT NULL,
    client_id    varchar(100)  NOT NULL,
    lesson_title varchar(300)  NOT NULL,
    remarks      varchar(2000),
    CONSTRAINT ss_lesson_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_exam_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_exam (
    copy_to_hoh        boolean      NOT NULL DEFAULT false,
    delete_flag        boolean      NOT NULL DEFAULT false,
    duration_minutes   integer,
    exam_date          date,
    required_questions integer,
    total_questions    integer,
    class_id           bigint       NOT NULL,
    id                 bigint       NOT NULL,
    status             varchar(50)  NOT NULL,
    client_id          varchar(100) NOT NULL,
    exam_title         varchar(300) NOT NULL,
    CONSTRAINT ss_exam_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_question_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_question (
    delete_flag    boolean      NOT NULL,
    marks          integer,
    sort_order     integer,
    exam_id        bigint       NOT NULL,
    id             bigint       NOT NULL,
    question_type  varchar(50)  NOT NULL,
    client_id      varchar(100) NOT NULL,
    correct_answer text,
    options_json   text,
    question_text  text         NOT NULL,
    CONSTRAINT ss_question_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_submission_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_submission (
    auto_marks         integer,
    delete_flag        boolean      NOT NULL,
    extra_time_minutes integer      NOT NULL DEFAULT 0,
    manual_marks       integer,
    reviewed           boolean      NOT NULL DEFAULT false,
    total_marks        integer,
    exam_id            bigint       NOT NULL,
    id                 bigint       NOT NULL,
    started_at         timestamp(6),
    student_id         bigint       NOT NULL,
    submitted_at       timestamp(6),
    status             varchar(50)  NOT NULL,
    client_id          varchar(100) NOT NULL,
    CONSTRAINT ss_submission_pkey PRIMARY KEY (id),
    CONSTRAINT uq_ss_submission_exam_student UNIQUE (exam_id, student_id)
);

CREATE SEQUENCE IF NOT EXISTS ss_answer_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_answer (
    is_correct       boolean,
    manual_marks     integer,
    suggested_marks  integer,
    id               bigint       NOT NULL,
    question_id      bigint       NOT NULL,
    submission_id    bigint       NOT NULL,
    client_id        varchar(100) NOT NULL,
    answer_text      text,
    match_highlights text,
    CONSTRAINT ss_answer_pkey PRIMARY KEY (id),
    CONSTRAINT uq_ss_answer_submission_question UNIQUE (submission_id, question_id)
);

CREATE SEQUENCE IF NOT EXISTS ss_note_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_note (
    delete_flag boolean      NOT NULL,
    class_id    bigint       NOT NULL,
    created_at  timestamp(6),
    id          bigint       NOT NULL,
    client_id   varchar(100) NOT NULL,
    note_text   text         NOT NULL,
    CONSTRAINT ss_note_pkey PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS ss_file_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS ss_file (
    delete_flag          boolean      NOT NULL,
    shared_with_students boolean      NOT NULL DEFAULT false,
    class_id             bigint       NOT NULL,
    id                   bigint       NOT NULL,
    uploaded_at          timestamp(6),
    client_id            varchar(100) NOT NULL,
    content_type         varchar(200),
    uploaded_by_name     varchar(200),
    original_name        varchar(300) NOT NULL,
    file_data            text,
    CONSTRAINT ss_file_pkey PRIMARY KEY (id)
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

-- ── mid_reg_meet + rsvp ───────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS mid_reg_meet_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS mid_reg_meet (
    include_transaction_fee   boolean      NOT NULL DEFAULT false,
    id                        bigint       NOT NULL,
    registration_end_date     timestamp(6),
    updated_at                timestamp(6),
    sender_phone              varchar(50),
    client_id                 varchar(100) NOT NULL,
    flyer_image_content_type  varchar(100),
    tshirt_image_content_type varchar(100),
    sender_email              varchar(200),
    sender_name               varchar(200),
    event_name                varchar(300),
    event_address             text,
    event_days                text,
    note                      text,
    flyer_image               bytea,
    tshirt_image              bytea,
    CONSTRAINT mid_reg_meet_pkey PRIMARY KEY (id),
    CONSTRAINT mid_reg_meet_client_id_key UNIQUE (client_id)
);

CREATE SEQUENCE IF NOT EXISTS mid_reg_meet_rsvp_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS mid_reg_meet_rsvp (
    adults                integer,
    children              integer,
    created_at            timestamp(6) NOT NULL,
    id                    bigint       NOT NULL,
    updated_at            timestamp(6),
    rsvp_saturday_mission varchar(10),
    rsvp_saturday_music   varchar(10),
    rsvp_sunday           varchar(10),
    phone                 varchar(50),
    client_id             varchar(100) NOT NULL,
    edit_token            varchar(100),
    first_name            varchar(100) NOT NULL,
    last_name             varchar(100),
    church                varchar(200),
    email                 varchar(200) NOT NULL,
    music_options         text,
    note                  text,
    tshirt_sizes          text,
    CONSTRAINT mid_reg_meet_rsvp_pkey PRIMARY KEY (id),
    CONSTRAINT mid_reg_meet_rsvp_edit_token_key UNIQUE (edit_token)
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


CREATE SEQUENCE IF NOT EXISTS event_registration_reminder_contact_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS event_registration_reminder_contact (
    event_id      integer      NOT NULL,
    id            integer      NOT NULL,
    created_date  timestamp(6) NOT NULL,
    phone         varchar(40),
    app_client_id varchar(255),
    email         varchar(255),
    CONSTRAINT event_registration_reminder_contact_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_errc_event ON event_registration_reminder_contact USING btree (event_id);

CREATE SEQUENCE IF NOT EXISTS event_registration_reminder_log_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS event_registration_reminder_log (
    contact_id     integer,
    days_before    integer,
    event_id       integer      NOT NULL,
    created_date   timestamp(6) NOT NULL,
    id             bigint       NOT NULL,
    channel        varchar(10)  NOT NULL,
    message_type   varchar(10)  DEFAULT 'REMINDER'::character varying,
    status         varchar(10)  NOT NULL,
    app_client_id  varchar(255),
    reason         varchar(255),
    recipient      varchar(255),
    recipient_norm varchar(255),
    CONSTRAINT event_registration_reminder_log_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_errl_event ON event_registration_reminder_log USING btree (event_id);
CREATE INDEX IF NOT EXISTS idx_errl_event_recip ON event_registration_reminder_log USING btree (event_id, channel, recipient_norm);

-- Invitations share the log table; message_type separates INVITE from REMINDER
-- rows so duplicate-prevention is scoped per message kind.
ALTER TABLE event_registration_reminder_log
    ADD COLUMN IF NOT EXISTS message_type VARCHAR(10) DEFAULT 'REMINDER';
UPDATE event_registration_reminder_log SET message_type = 'REMINDER' WHERE message_type IS NULL;

-- ── Public marketing website visitor tracking (2026-07-08) ───────────────────
-- One row per visit to the public /web/* pages; aggregated on the Service
-- Admin dashboard. Not tenant-scoped (pages exist outside any church account).


CREATE SEQUENCE IF NOT EXISTS public_page_visit_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS public_page_visit (
    id         bigint       NOT NULL,
    visited_at timestamp(6) NOT NULL,
    page       varchar(50)  NOT NULL,
    ip_address varchar(64),
    referrer   varchar(500),
    user_agent text,
    CONSTRAINT public_page_visit_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ppv_page ON public_page_visit USING btree (page);
CREATE INDEX IF NOT EXISTS idx_ppv_page_date ON public_page_visit USING btree (page, visited_at);

-- ── Subscription plans + monthly usage counters (2026-07-14) ─────────────────
-- Plans are managed by the Service Admin; each church's service_client.subscription_type
-- maps to a plan (FREE→FREE, LIMITED→STANDARD, FULL→PRO, or a plan_code directly).
-- Defaults are seeded by the application on startup (SubscriptionPlanSeeder).


CREATE SEQUENCE IF NOT EXISTS subscription_plan_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS subscription_plan (
    active                      boolean      NOT NULL,
    max_emails_per_month        integer,
    max_kids_portals            integer,
    max_member_portals          integer,
    max_online_giving_per_month integer,
    max_people                  integer,
    max_sms_per_month           integer,
    sort_order                  integer,
    created_date                timestamp(6) NOT NULL,
    id                          bigint       NOT NULL,
    plan_code                   varchar(40)  NOT NULL,
    plan_name                   varchar(100) NOT NULL,
    description                 text,
    features_json               text,
    CONSTRAINT subscription_plan_pkey PRIMARY KEY (id),
    CONSTRAINT subscription_plan_plan_code_key UNIQUE (plan_code)
);


-- Extra SMS credits are granted PER CLIENT (on top of the plan's monthly limit)
ALTER TABLE service_client
    ADD COLUMN IF NOT EXISTS extra_sms_count INTEGER NOT NULL DEFAULT 0;

CREATE SEQUENCE IF NOT EXISTS subscription_usage_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS subscription_usage (
    emails_sent  integer      NOT NULL,
    giving_count integer      NOT NULL,
    sms_sent     integer      NOT NULL,
    usage_month  varchar(7)   NOT NULL,
    created_date timestamp(6) NOT NULL,
    id           bigint       NOT NULL,
    client_id    varchar(100) NOT NULL,
    CONSTRAINT subscription_usage_pkey PRIMARY KEY (id),
    CONSTRAINT uq_subscription_usage UNIQUE (client_id, usage_month)
);

-- ── Multiple Song Books (2026-07-16) ─────────────────────────────────────────
-- Each church may now have several Song Books. The DEFAULT book keeps the plain
-- client_id on all existing song tables (no data migration needed — existing
-- data becomes the default book, named "Musical Night"); additional books store
-- their rows under the scoped client id "<client_id>#B<book_id>".

CREATE TABLE IF NOT EXISTS song_book (
    default_book boolean        NOT NULL,
    sort_order   integer        NOT NULL,
    created_at   timestamptz(6),
    id           bigint         GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    client_id    varchar(100)   NOT NULL,
    name         varchar(200)   NOT NULL,
    CONSTRAINT song_book_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_song_book_client ON song_book (client_id);


-- ── Meeting occurrence deletion (2026-07-21) ─────────────────────────────────
-- Recurring meetings are one `meeting` row whose occurrences are computed on
-- the fly. Deleting a single occurrence records an exception here; the Event
-- Calendar, ICS feed and the reminder scheduler all exclude these dates, so
-- no reminders (email/SMS/WhatsApp/push/weekly digest) are sent for them.
-- "Delete this & all future" instead rewrites meeting.end_date.
CREATE TABLE IF NOT EXISTS meeting_skip_date (
    meeting_id    integer      NOT NULL,
    skip_date     date         NOT NULL,
    created_date  timestamp(6),
    id            bigint       GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    app_client_id varchar(64),
    CONSTRAINT meeting_skip_date_pkey PRIMARY KEY (id),
    CONSTRAINT uq_meeting_skip UNIQUE (meeting_id, skip_date)
);

CREATE INDEX IF NOT EXISTS idx_meeting_skip_meeting ON meeting_skip_date (meeting_id);


-- ── Connect submissions admin (2026-07-22) ───────────────────────────────────
-- Admin-facing record of every public "Connect With Us" submission. The
-- submission still creates a Visitor family_member; this row adds workflow
-- status, volunteer assignment and links to the auto follow-up (follow_up rows
-- with linked_type='CONNECT' hold the follow-up history).
CREATE TABLE IF NOT EXISTS connect_submission (
    assigned_member_id  integer,
    delete_flag         boolean      NOT NULL,
    member_id           integer,
    created_at          timestamp(6) NOT NULL,
    follow_up_id        bigint,
    id                  bigint       GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    updated_at          timestamp(6),
    birth_date          varchar(20),
    confirmation_sent   varchar(20),
    gender              varchar(20),
    status              varchar(20),
    marital_status      varchar(30),
    phone               varchar(40),
    client_id           varchar(64)  NOT NULL,
    first_name          varchar(100),
    last_name           varchar(100),
    contact_preferences varchar(120),
    assigned_to         varchar(150),
    email               varchar(200),
    address             varchar(300),
    how_heard           text,
    CONSTRAINT connect_submission_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_connect_submission_client ON connect_submission (client_id);


-- ── Song Book advertisement pages (2026-07-22) ───────────────────────────────
-- Sponsor ads / promotions / announcements insertable anywhere in a Song Book.
-- position: cover | toc | fsec:<finalized_section_id> | end; multiple pages per
-- position ordered by sort_order. PDF uploads are rasterized to PNG (one row
-- per page). Book-scoped client ids ("<client_id>#B<book_id>") supported.
CREATE TABLE IF NOT EXISTS song_book_ad (
    sort_order   integer        NOT NULL,
    created_at   timestamptz(6),
    id           bigint         GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    updated_at   timestamptz(6),
    position     varchar(40)    NOT NULL,
    client_id    varchar(100)   NOT NULL,
    content_type varchar(100),
    title        varchar(200),
    file_name    varchar(300),
    data         bytea,
    CONSTRAINT song_book_ad_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_song_book_ad_client ON song_book_ad (client_id);

-- ── Failed-login protection (2026-08-16) ─────────────────────────────────────
-- Brute-force / credential-stuffing controls for every authentication entry
-- point. See LOGIN_SECURITY.md for the threat model and tuning guide.
--
-- Two append-only tables. login_attempt_log is the security audit trail AND the
-- source of every rate-limit counter; login_block holds TEMPORARY blocks, each
-- with a hard blocked_until. There is deliberately no "locked" flag anywhere:
-- a block expires on its own, so knowing a username can never be enough to deny
-- that person access permanently.
--
-- Keeping the counters in PostgreSQL (rather than in JVM memory) is what makes
-- the limits hold across every application instance behind the load balancer.
--
-- NOTE: neither table stores passwords, password hashes, or password lengths.


CREATE SEQUENCE IF NOT EXISTS login_attempt_log_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS login_attempt_log (
    attempted_at   timestamp(6) NOT NULL,
    id             bigint       NOT NULL,
    outcome        varchar(20)  NOT NULL,
    failure_reason varchar(40),
    user_role      varchar(60),
    device_hash    varchar(64),
    session_hash   varchar(64),
    country        varchar(80),
    client_id      varchar(100),
    ip_address     varchar(100),
    city           varchar(120),
    endpoint       varchar(120),
    region         varchar(120),
    church_name    varchar(200),
    username       varchar(200),
    CONSTRAINT login_attempt_log_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_lal_attempted_at ON login_attempt_log USING btree (attempted_at);
CREATE INDEX IF NOT EXISTS idx_lal_client_outcome_time ON login_attempt_log USING btree (client_id, outcome, attempted_at);
CREATE INDEX IF NOT EXISTS idx_lal_ip_time ON login_attempt_log USING btree (ip_address, attempted_at);
CREATE INDEX IF NOT EXISTS idx_lal_user_ip_time ON login_attempt_log USING btree (username, ip_address, attempted_at);
CREATE INDEX IF NOT EXISTS idx_lal_user_time ON login_attempt_log USING btree (username, attempted_at);

CREATE SEQUENCE IF NOT EXISTS login_block_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS login_block (
    block_seconds   integer      NOT NULL,
    escalation_step integer      NOT NULL,
    failure_count   integer      NOT NULL,
    blocked_at      timestamp(6) NOT NULL,
    blocked_until   timestamp(6) NOT NULL,
    id              bigint       NOT NULL,
    scope           varchar(20)  NOT NULL,
    client_id       varchar(100),
    ip_address      varchar(100),
    username        varchar(200),
    scope_key       varchar(255) NOT NULL,
    CONSTRAINT login_block_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_lb_blocked_until ON login_block USING btree (blocked_until);
CREATE INDEX IF NOT EXISTS idx_lb_key_until ON login_block USING btree (scope_key, blocked_until);
CREATE INDEX IF NOT EXISTS idx_lb_username ON login_block USING btree (username);

-- Every failed login runs three counting queries against login_attempt_log, and
-- every login attempt reads login_block once. These indexes are what keep the
-- limiter fast under exactly the load it exists to handle.
-- (DatabaseIndexInitializer also creates them at startup; repeated here so the
-- production database is correct the moment the JAR is deployed.)

-- password_reset_token: issue timestamp, so repeat reset emails for the same
-- account can be suppressed by a cooldown. Backfilled from expiry_time (tokens
-- are valid for 10 minutes) so pre-existing rows behave sensibly.
ALTER TABLE password_reset_token
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

UPDATE password_reset_token
   SET created_at = expiry_time - INTERVAL '10 minutes'
 WHERE created_at IS NULL;

-- ── Security audit trail: login/logout detail + geolocation (2026-08-17) ─────
-- Extends login_attempt_log so the durable record of a sign-in carries the same
-- fields as the security-YYYY-MM-DD.log line: church, role, and the approximate
-- location of the IP address. See LOGGING.md.
--
-- Why the database and not only the log files: on Azure App Service the log files
-- live on the persistent /home share, which survives restarts and redeployments —
-- but a database row survives things the share does not (the App Service being
-- recreated, someone clearing the share, retention deleting the file at 30 days).
-- The table is also the only form that can answer "show this church every sign-in
-- last month" without grepping thirty files.

ALTER TABLE login_attempt_log
    ADD COLUMN IF NOT EXISTS church_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS user_role   VARCHAR(60),
    ADD COLUMN IF NOT EXISTS city        VARCHAR(120),
    ADD COLUMN IF NOT EXISTS region      VARCHAR(120),   -- state in the US, province/county elsewhere
    ADD COLUMN IF NOT EXISTS country     VARCHAR(80);

-- session_id -> session_hash. The raw session id is a bearer credential: anyone who
-- reads one out of this table (or a log file) can replay it and impersonate that user
-- until the session expires. A SHA-256 still pairs a LOGIN with its matching LOGOUT,
-- which is the only thing the audit trail needed it for.
ALTER TABLE login_attempt_log
    ADD COLUMN IF NOT EXISTS session_hash VARCHAR(64);

-- Deliberately NOT migrating the old values across: they are raw session identifiers,
-- and the point of this change is that they should never have been stored. Drop the
-- column outright, which discards them.
ALTER TABLE login_attempt_log
    DROP COLUMN IF EXISTS session_id;

-- The outcome column now also carries 'LOGOUT' (audit only — moves no rate-limit
-- counter, exactly like 'DENIED'). No constraint change is needed; it is a free-text
-- VARCHAR(20), and every counting query filters on outcome = 'FAILURE'.

-- Per-tenant security reporting: "show this church its sign-ins for last month".

-- ── Backup retention period (2026-08-18) ─────────────────────────────────────
-- Snapshots (<table>_bkp_YYYYMMDD) are now dropped automatically once they are
-- older than this many months. 0 = keep for ever. Default 12 (one year).
--
-- The most recent snapshot is never dropped, whatever its age: retention and
-- backup interval are set independently, and a short retention with a long
-- interval would otherwise delete the only backup and leave none at all.

ALTER TABLE backup_config
    ADD COLUMN IF NOT EXISTS retention_months INTEGER NOT NULL DEFAULT 12;


-- ── Phone numbers → E.164 (2026-08-20) ───────────────────────────────────────
-- Twilio requires the recipient in E.164 (+<country><number>) and rejects anything
-- else with error 21211. Event registration collected phone numbers through a
-- free-text field and stored them exactly as typed, so most rows hold shapes the
-- provider will not accept: "9135550100", "19135550100", "(913) 555-0100".
--
-- The application now normalises on the way out, so this backfill is not required
-- for correctness — it is here so the stored data matches what is actually sent,
-- which makes the opt-in lookup (an exact string match on the E.164 value) and any
-- reporting on these columns agree with delivery.
--
-- Rules, identical to util/PhoneNumbers.toE164:
--   * a value already starting with '+' is left alone (it may be international)
--   * 11 digits beginning with 1  → +1 and the last 10
--   * 10 digits                   → +1 and the digits
--   * area code and exchange must both start 2-9 (the NANP dial plan)
--   * anything else is LEFT UNCHANGED — see the report at the end
--
-- Nothing is guessed. A 9-digit entry is not padded and a 12-digit one is not
-- truncated: either would produce a real number belonging to somebody else, and we
-- would text a stranger another person's event details.

DO $$
DECLARE
    updated_count INTEGER;
BEGIN
    -- Idempotent: rows already in E.164 start with '+' and are excluded by the WHERE.
    WITH candidates AS (
        SELECT id,
               regexp_replace(phone, '\D', '', 'g') AS digits
        FROM   event_registration
        WHERE  phone IS NOT NULL
          AND  btrim(phone) <> ''
          AND  left(btrim(phone), 1) <> '+'
    ),
    national AS (
        SELECT id,
               CASE WHEN length(digits) = 11 AND left(digits, 1) = '1'
                    THEN right(digits, 10)
                    ELSE digits
               END AS ten
        FROM   candidates
    )
    UPDATE event_registration r
    SET    phone = '+1' || n.ten
    FROM   national n
    WHERE  r.id = n.id
      AND  length(n.ten) = 10
      AND  substr(n.ten, 1, 1) BETWEEN '2' AND '9'      -- area code
      AND  substr(n.ten, 4, 1) BETWEEN '2' AND '9';     -- exchange

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'event_registration: % phone number(s) normalised to E.164', updated_count;
END $$;

-- Same treatment for the opt-in table. These SHOULD already be E.164 — the opt-in
-- form has always normalised — but the column is the key the STOP webhook matches
-- on, so any row that is not in E.164 is a person whose opt-out would not register.
DO $$
DECLARE
    updated_count INTEGER;
BEGIN
    WITH candidates AS (
        SELECT id, regexp_replace(phone_number, '\D', '', 'g') AS digits
        FROM   sms_opt_in
        WHERE  phone_number IS NOT NULL
          AND  left(btrim(phone_number), 1) <> '+'
    ),
    national AS (
        SELECT id,
               CASE WHEN length(digits) = 11 AND left(digits, 1) = '1'
                    THEN right(digits, 10) ELSE digits END AS ten
        FROM   candidates
    )
    UPDATE sms_opt_in s
    SET    phone_number = '+1' || n.ten
    FROM   national n
    WHERE  s.id = n.id
      AND  length(n.ten) = 10
      AND  substr(n.ten, 1, 1) BETWEEN '2' AND '9'
      AND  substr(n.ten, 4, 1) BETWEEN '2' AND '9';

    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RAISE NOTICE 'sms_opt_in: % phone number(s) normalised to E.164', updated_count;
END $$;

-- Report: registrations whose phone could NOT be normalised. These cannot receive a
-- text and the number has to be re-collected from the person — there is no safe
-- automatic repair. Run this after the migration and work the list.
--
--   SELECT r.id, r.event_id, r.first_name, r.last_name, r.email, r.phone
--   FROM   event_registration r
--   WHERE  r.phone IS NOT NULL
--     AND  btrim(r.phone) <> ''
--     AND  left(btrim(r.phone), 1) <> '+'
--   ORDER  BY r.event_id, r.id;
--
-- After this migration that query returns only the unfixable rows: everything the
-- rules above could resolve now begins with '+'.

DO $$
DECLARE
    stuck_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO stuck_count
    FROM   event_registration
    WHERE  phone IS NOT NULL
      AND  btrim(phone) <> ''
      AND  left(btrim(phone), 1) <> '+';

    IF stuck_count > 0 THEN
        RAISE NOTICE 'event_registration: % phone number(s) could NOT be normalised and '
                     'will not receive SMS — see the report query above.', stuck_count;
    END IF;
END $$;


-- ── Done ──────────────────────────────────────────────────────────────────────

-- =====================================================================
-- GuessIt: groups, group participants, and public participation
-- =====================================================================
-- Hibernate (ddl-auto=update) creates guess_it_group and
-- guess_it_group_participant, and adds the new nullable columns to
-- guess_it_game / guess_it_participant, on its own. The one thing it will
-- not do is relax an existing NOT NULL constraint, so that is done here.
--
-- Why it has to change: a public participant has no family_member row, so
-- guess_it_participant.member_id must be allowed to be NULL for them. They
-- are identified by group_participant_id instead. PostgreSQL treats NULLs
-- as distinct in a unique index, so the existing
-- (game_id, member_id) uniqueness still holds for logged-in members while
-- any number of public participants can share a game.
--
-- Idempotent: safe to run repeatedly.

DO $$
DECLARE
    v_nullable text;
    v_rows     bigint;
BEGIN
    SELECT is_nullable INTO v_nullable
      FROM information_schema.columns
     WHERE table_name = 'guess_it_participant'
       AND column_name = 'member_id';

    IF v_nullable IS NULL THEN
        RAISE NOTICE 'guess_it_participant.member_id not present yet - nothing to do.';
    ELSIF v_nullable = 'YES' THEN
        RAISE NOTICE 'guess_it_participant.member_id already nullable - no change.';
    ELSE
        ALTER TABLE guess_it_participant ALTER COLUMN member_id DROP NOT NULL;
        RAISE NOTICE 'guess_it_participant.member_id is now nullable (public participants).';
    END IF;

    -- One play row per person per grouped game. Created here rather than left
    -- to Hibernate so it also lands on databases that already hold the table.
    IF to_regclass('public.guess_it_participant') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'guess_it_participant'
                      AND column_name = 'group_participant_id')
       AND NOT EXISTS (SELECT 1 FROM pg_indexes
                        WHERE tablename = 'guess_it_participant'
                          AND indexname = 'uq_guess_it_participant_game_group_participant')
    THEN
        SELECT COUNT(*) INTO v_rows FROM (
            SELECT game_id, group_participant_id
              FROM guess_it_participant
             WHERE group_participant_id IS NOT NULL
             GROUP BY game_id, group_participant_id
            HAVING COUNT(*) > 1
        ) dupes;

        IF v_rows > 0 THEN
            RAISE NOTICE 'Skipping unique index: % duplicate (game_id, group_participant_id) pair(s) present.', v_rows;
        ELSE
            CREATE UNIQUE INDEX uq_guess_it_participant_game_group_participant
                ON guess_it_participant (game_id, group_participant_id);
            RAISE NOTICE 'Created uq_guess_it_participant_game_group_participant.';
        END IF;
    END IF;
END $$;

-- Report anything that would block the change above.
SELECT game_id, group_participant_id, COUNT(*) AS duplicate_rows
  FROM guess_it_participant
 WHERE group_participant_id IS NOT NULL
 GROUP BY game_id, group_participant_id
HAVING COUNT(*) > 1;


-- =====================================================================
-- GuessIt: per-group clue duration (guess_it_group.timer_secs)
-- =====================================================================
-- The game host can now change a group's per-clue duration after the group
-- has been created, up to a ceiling of three minutes. The chosen value lives
-- on the group so newly added rounds inherit it, and is pushed onto every
-- game in the group that has not finished yet.
--
-- The entity carries @Column(columnDefinition = "integer default 60"), so
-- ddl-auto can add this NOT NULL column to a table that already holds rows.
-- This block is the companion for databases where the ALTER is run by hand,
-- and it backfills with the same 60 the entity uses so the two paths agree.
-- (See CLAUDE.md: a nullable=false column added without a default is logged
-- by Hibernate as a WARN and silently skipped — the app then starts looking
-- healthy while every query touching the column fails.)
--
-- Idempotent: safe to run repeatedly.

DO $$
BEGIN
    IF to_regclass('public.guess_it_group') IS NULL THEN
        RAISE NOTICE 'guess_it_group not present yet - nothing to do.';
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'guess_it_group'
                     AND column_name = 'timer_secs') THEN
        RAISE NOTICE 'guess_it_group.timer_secs already present - no change.';
    ELSE
        ALTER TABLE guess_it_group
            ADD COLUMN timer_secs INTEGER NOT NULL DEFAULT 60;
        RAISE NOTICE 'Added guess_it_group.timer_secs (default 60 seconds).';
    END IF;
END $$;

-- Trim anything already stored above the three-minute ceiling, so the value
-- the host sees in the dropdown is always a value the dropdown can express.
-- Guarded by to_regclass because this file also runs against databases that
-- predate the Guess It tables entirely.
DO $$
BEGIN
    IF to_regclass('public.guess_it_group') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'guess_it_group' AND column_name = 'timer_secs') THEN
        UPDATE guess_it_group SET timer_secs = 180 WHERE timer_secs > 180;
    END IF;
    IF to_regclass('public.guess_it_game') IS NOT NULL
       AND EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'guess_it_game' AND column_name = 'timer_secs') THEN
        UPDATE guess_it_game SET timer_secs = 180 WHERE timer_secs > 180;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Trial-account agreement: when a demo login accepted the trial terms.
--
-- Nullable on purpose. NULL means "not yet accepted", which is the correct
-- reading for every row that already exists, so the popup shows once for each
-- current demo login on its next sign-in. Hibernate's ddl-auto adds this
-- column too; the statement is here so a production deploy is not waiting on
-- ddl-auto for a column the new code reads on the login path.
-- Guarded by to_regclass because this file also runs against databases that
-- predate the demo-access tables entirely.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regclass('public.demo_role_access') IS NULL THEN
        RAISE NOTICE 'demo_role_access not present - skipping agreement column.';
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'demo_role_access'
                     AND column_name = 'agreement_accepted_at') THEN
        RAISE NOTICE 'demo_role_access.agreement_accepted_at already present - no change.';
    ELSE
        ALTER TABLE demo_role_access
            ADD COLUMN agreement_accepted_at TIMESTAMP NULL;
        RAISE NOTICE 'Added demo_role_access.agreement_accepted_at (NULL = not yet accepted).';
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Plaid environment stamp on each bank connection.
--
-- Trial subscriptions are confined to the Plaid sandbox, so the application now
-- talks to two environments. A Plaid access token is only valid in the one that
-- issued it, so every item records which that was; later calls use the stamp
-- rather than the tenant's current plan.
--
-- Backfill uses the environment these rows were actually created against — this
-- deployment's configured PLAID_ENV. EDIT THE DEFAULT BELOW to 'sandbox' if this
-- database's app runs with PLAID_ENV=sandbox. A wrong value here does not expose
-- anything (the credentials simply will not match and the call fails), but it
-- does break sync for existing connections until corrected.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regclass('public.plaid_item') IS NULL THEN
        RAISE NOTICE 'plaid_item not present - skipping plaid_env column.';
    ELSIF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'plaid_item' AND column_name = 'plaid_env') THEN
        RAISE NOTICE 'plaid_item.plaid_env already present - no change.';
    ELSE
        ALTER TABLE plaid_item ADD COLUMN plaid_env VARCHAR(20);
        UPDATE plaid_item SET plaid_env = 'production' WHERE plaid_env IS NULL;
        RAISE NOTICE 'Added plaid_item.plaid_env and backfilled existing rows to production.';
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Trial Registration invitation links.
--
-- The self-service trial form used to be a public URL, so anyone who found it
-- could provision a tenant. It is now reachable only with a token issued here by
-- a Service Admin: unguessable (32 random bytes), single-use, and expiring after
-- seven days by default.
--
-- Hibernate's ddl-auto creates this too; the statement is here so a production
-- deploy is not waiting on ddl-auto for a table the page filter reads on every
-- request to the registration page.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS trial_registration_link (
    id             integer      GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    revoked        boolean      NOT NULL DEFAULT false,
    created_at     timestamp(6),
    deleted_at     timestamp(6),
    expires_at     timestamp(6),
    revoked_at     timestamp(6),
    used_at        timestamp(6),
    token          varchar(100) NOT NULL,
    used_client_id varchar(100),
    created_by     varchar(150),
    deleted_by     varchar(150),
    prospect_email varchar(200),
    prospect_name  varchar(200),
    note           varchar(500),
    CONSTRAINT trial_registration_link_pkey PRIMARY KEY (id),
    CONSTRAINT uk_trial_link_token UNIQUE (token)
);
CREATE INDEX IF NOT EXISTS ix_trial_link_token ON trial_registration_link USING btree (token);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                    WHERE tablename = 'trial_registration_link'
                      AND indexname = 'uk_trial_link_token') THEN
        CREATE UNIQUE INDEX uk_trial_link_token ON trial_registration_link (token);
        RAISE NOTICE 'Created unique index uk_trial_link_token.';
    ELSE
        RAISE NOTICE 'uk_trial_link_token already present - no change.';
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- Activity Corner is off for Trial subscriptions.
--
-- Feature flags live in subscription_plan.features_json as {"key":boolean}, and
-- a MISSING key means enabled (SubscriptionService.isFeatureEnabled fails open,
-- so newly introduced features default on). Adding "activityCorner" to the
-- catalog therefore changes nothing for Free, Standard, Pro or any other plan —
-- only an explicit false disables it, and only the TRIAL plan gets one here.
--
-- Idempotent: re-running sets the same value. Safe on a database whose TRIAL
-- plan has no features_json yet (COALESCE seeds an empty object first).
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF to_regclass('public.subscription_plan') IS NULL THEN
        RAISE NOTICE 'subscription_plan not present - skipping Activity Corner flag.';
    ELSIF NOT EXISTS (SELECT 1 FROM subscription_plan WHERE UPPER(plan_code) = 'TRIAL') THEN
        RAISE NOTICE 'No TRIAL plan configured - skipping Activity Corner flag. '
                     'Set it from Subscription Plans once the TRIAL plan exists.';
    ELSE
        UPDATE subscription_plan
           SET features_json = (
                   COALESCE(NULLIF(features_json, '')::jsonb, '{}'::jsonb)
                   || '{"activityCorner": false}'::jsonb
               )::text
         WHERE UPPER(plan_code) = 'TRIAL';
        RAISE NOTICE 'Activity Corner disabled for the TRIAL plan.';
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 security remediation — public links are random stored tokens.
-- (ddl-auto=update creates these; listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- Event registration URL token (/event-register/{token}); assigned lazily for old rows.
ALTER TABLE church_event   ADD COLUMN IF NOT EXISTS public_token       VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_church_event_public_token ON church_event (public_token);

-- Parent pickup link token (/kidsPickup?t=…); minted on the first alert, dead after checkout.
ALTER TABLE km_checkin     ADD COLUMN IF NOT EXISTS pickup_token       VARCHAR(64);

-- Church-owner registration link token; minted at every approve / re-approve.
ALTER TABLE service_client ADD COLUMN IF NOT EXISTS registration_token VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_service_client_registration_token ON service_client (registration_token);

-- Public Screens links minted before this change hold AES tokens. They still resolve
-- (a token is only ever looked up), but the URLs those churches were shown carried a
-- different value and no longer work. No church has links in use; if that changes,
-- have them regenerate from Public Screens rather than migrating the old rows.

-- Staff invitation link token (single-use; cleared when the signup completes).
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS invite_token VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_app_user_invite_token ON app_user (invite_token);

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Trial data deletion (Service Admin → Trial Registration Links / Trials)
-- (ddl-auto=update creates these; listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- Soft delete for a registration link. Distinct from `revoked`: revoking is a
-- message to the prospect (the link stops working and says so); soft-deleting
-- removes the row from the Service Admin list and is reversible.
ALTER TABLE trial_registration_link ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE trial_registration_link ADD COLUMN IF NOT EXISTS deleted_by VARCHAR(150);
CREATE INDEX IF NOT EXISTS ix_trial_link_deleted_at ON trial_registration_link (deleted_at);

-- Selectable trial length (30/60/90/… days) chosen when the link is issued.
-- Nullable: NULL means the standard 30 days, so links issued earlier are unchanged.
ALTER TABLE trial_registration_link ADD COLUMN IF NOT EXISTS trial_days INTEGER;

-- Soft-deleting a trial ACCOUNT and an individual ROLE needs no new columns:
--   account → service_client.delete_flag + church_registration.delete_flag
--   role    → signup.deleted (+ app_user.delete_flag for a staff role)
-- all of which already exist and are already honoured by the login queries.

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Editable Financial Report letter text (Accounting → Year-End Tax Report)
-- (ddl-auto=update creates these; listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- One row per church holding the wording above and below the contribution table
-- on a giving statement. NO BACK-FILL IS NEEDED and none should be attempted: a
-- church with no row here is served the built-in default wording — the text the
-- letter has always carried — by FinancialReportLetterService. Seeding a row per
-- church would only freeze today's words and make the default impossible to
-- improve. Both columns hold rich text already reduced to a safe subset by
-- RichTextSanitizer, and may contain the {ChurchName} and {Year} placeholders.
CREATE SEQUENCE IF NOT EXISTS financial_report_letter_id_seq;
CREATE SEQUENCE IF NOT EXISTS financial_report_letter_id_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS financial_report_letter (
    delete_flag   boolean      NOT NULL,
    id            integer      NOT NULL,
    created_date  timestamp(6) NOT NULL,
    updated_date  timestamp(6),
    app_client_id varchar(100) NOT NULL,
    updated_by    varchar(120),
    closing_html  text,
    intro_html    text,
    CONSTRAINT financial_report_letter_pkey PRIMARY KEY (id)
);

-- Every read is "this church's live row", which is the only access path there is.
CREATE INDEX IF NOT EXISTS ix_financial_report_letter_client
    ON financial_report_letter (app_client_id, delete_flag);

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Daily Verse Setup + Trial/Demo product tour
-- (ddl-auto=update creates these; listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- When this login finished or skipped the first-run product tour. NULL means
-- "not shown yet", which is what every existing row means, so NO BACK-FILL is
-- needed — and nullable on purpose, because ddl-auto adds a nullable column to a
-- populated table without a default. Cleared by a Service Admin Reset, alongside
-- agreement_accepted_at: a reissued credential is a new person.
ALTER TABLE demo_role_access ADD COLUMN IF NOT EXISTS product_tour_at TIMESTAMP;

-- Daily Verse needs NO schema change: the year loader writes ordinary
-- promise_verse rows (client_id + day_number 1..366). Day 366 is new only in the
-- sense that nothing used to write it — the column has always been an integer.

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Bank Import / Plaid duplicate-import prevention (Financial audit H8)
-- (ddl-auto=update creates these; listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- Fingerprint of the source transaction for an imported income/expense row (a
-- bank-statement line, or "plaid:<plaidTransactionId>"). NULL for every manual
-- entry — nullable on purpose, ddl-auto adds it without a default and no
-- back-fill is needed or wanted: existing rows were never "imported" in this
-- sense, and leaving them NULL is the correct, permanent value for them.
ALTER TABLE income  ADD COLUMN IF NOT EXISTS import_ref VARCHAR(160);
ALTER TABLE expense ADD COLUMN IF NOT EXISTS import_ref VARCHAR(160);

-- The actual duplicate-import guarantee: at most one ACTIVE row per church may
-- carry a given import_ref. Partial — NULL (every manual entry) and
-- soft-deleted rows are excluded, so deleting a bad import frees its
-- import_ref for re-import, and this never constrains manual entries at all.
-- Also created at startup by DatabaseIndexInitializer so it cannot go missing
-- just because this manual migration step was skipped; verified on Postgres 16
-- (hard duplicate rejected, cross-tenant unaffected, freed by soft-delete,
-- idempotent to re-run).
CREATE UNIQUE INDEX IF NOT EXISTS ux_income_import_ref  ON income  (app_client_id, import_ref)
    WHERE delete_flag = false AND import_ref IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_expense_import_ref ON expense (app_client_id, import_ref)
    WHERE delete_flag = false AND import_ref IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Payroll: duplicate paystubs, stale run totals, unclamped net pay
-- (Financial audit H1-H4; ddl-auto=update creates these; listed here so a
-- manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- H1 (same-day YTD reset) and H2 (voided-run FICA leakage) are pure logic
-- fixes in PayrollService#computePriorYtd — no schema change.

-- H4: the shortfall carried separately when a period's taxes/deductions exceed
-- its gross and net pay clamps at zero instead of going negative. NULL for
-- every paystub computed before this fix — nullable on purpose, ddl-auto adds
-- it without a default, and no back-fill is needed or wanted: those historical
-- stubs were never re-computed, so their netPay is whatever it always was, and
-- their arrears is simply not tracked for them (the PDF and every API response
-- treat a NULL the same as zero, via PaystubPdfService's nz() guard).
ALTER TABLE payroll_paystub ADD COLUMN IF NOT EXISTS arrears_amount NUMERIC(15,2);

-- H3: the real, unbypassable half of the duplicate-paystub guard
-- (PayrollService#processEmployee's existsByRunIdAndEmployeeIdAndVoidedFalse
-- check is the advisory, fail-fast half) — at most one non-voided paystub per
-- (run, employee). run_id already identifies a single tenant's payroll_run
-- row, so no app_client_id column is needed for tenant isolation here, unlike
-- the import_ref indexes above. Partial on voided=false so voiding a whole run
-- (every stub in it) never blocks that run from being corrected and
-- re-processed. Also created at startup by DatabaseIndexInitializer so it
-- cannot go missing just because this manual migration step was skipped.
CREATE UNIQUE INDEX IF NOT EXISTS ux_paystub_run_employee ON payroll_paystub (run_id, employee_id)
    WHERE voided = false;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Tax statements: non-gift income mislabeled as deductible, online
-- donations invisible to every report (Financial audit H9; ddl-auto=update
-- creates the column below, but not the two find-or-create'd category rows —
-- see the note under it — so both are listed here for a manual migration.)
-- ═══════════════════════════════════════════════════════════════════════════

-- Whether a sub-source's income counts as a tax-deductible gift on the
-- Year-End Tax Report and a member's own giving statement. NOT NULL DEFAULT
-- TRUE so every existing category keeps appearing on those statements exactly
-- as it always has; a bookkeeper turns specific non-gift categories (facility
-- rental, bookstore sales, event fees...) off via PUT
-- /api/sub-sources/{id}/tax-deductible. @ColumnDefault on the entity makes
-- ddl-auto=update add this safely to a populated table; listed here too so a
-- manual migration is complete.
ALTER TABLE sub_source ADD COLUMN IF NOT EXISTS tax_deductible BOOLEAN NOT NULL DEFAULT TRUE;

-- Online donations (Stripe giving, MidRegMeet event giving) are now posted
-- into income at save time — see DonationIncomePostingService — filed under
-- each church's own "Online Giving" -> "Online Donations" category, which
-- that service creates the first time a church actually receives one (no
-- schema/manual step needed; nothing to run here for that part). It reuses
-- income.import_ref (the H8 fingerprint column/unique index already created
-- above) with the Stripe PaymentIntent id, so a re-post can never double a
-- gift.
--
-- This does NOT back-fill donation rows that already existed before this
-- fix shipped. That is a separate, one-time decision an operator should make
-- deliberately (it would retroactively change past tax statements, and could
-- double-count a donation a bookkeeper already re-entered by hand while
-- donations were invisible to every report) rather than something this
-- migration should do silently. A one-off backfill, if wanted, would insert
-- one income row per donation not already matched by import_ref, using the
-- same member-matching and category rules as DonationIncomePostingService.

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 "Cover the fee" gross-up recorded as the gift itself (Financial
-- audit M3; ddl-auto=update creates both columns below with no manual step
-- needed — listed here so a manual migration is complete.)
-- ═══════════════════════════════════════════════════════════════════════════

-- The donor's intended gift, separate from the amount actually charged
-- (donation.amount). Only the Midwest Region Meet "cover the transaction
-- fee" flow can ever make these differ; the regular donation page has no fee
-- option, so intended_amount == amount there. Nullable and NOT back-filled:
-- a donation recorded before this existed has no way to know, after the
-- fact, what portion (if any) of its charge was a covered fee, so it is left
-- NULL rather than guessed at — Donation#getIntendedAmountOrCharge() falls
-- back to the full charge for exactly these rows, matching what every
-- report and receipt already showed them before this fix. Every donation
-- saved from now on (either giving path) sets this explicitly.
ALTER TABLE donation ADD COLUMN IF NOT EXISTS intended_amount NUMERIC(12,2);

-- The processing-fee portion of donation.amount that the donor chose to
-- cover on top of their intended gift. NOT NULL DEFAULT 0 — correct
-- immediately for every historical row (nothing tracked a separate covered
-- fee before this existed, so zero is simply true for all of them, not a
-- guess) as well as every non-fee-cover donation going forward.
ALTER TABLE donation ADD COLUMN IF NOT EXISTS fee_covered NUMERIC(12,2) NOT NULL DEFAULT 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Plaid external ids are not tenant-unique (Financial audit M7)
-- ═══════════════════════════════════════════════════════════════════════════

-- Plaid's own identifiers (item_id, account_id, transaction_id) are not
-- guaranteed unique across tenants -- most concretely, Trial/Demo signups
-- routinely connect the same shared Plaid sandbox test institution, whose
-- ids are deterministic per institution rather than randomized per
-- connection. The three finder methods that used these ids alone (with no
-- clientId filter) to decide what row to write have been fixed in code
-- (PlaidSyncService, PlaidLinkService) to look up scoped by clientId
-- instead -- but the three columns below are also still GLOBALLY unique at
-- the database level, which ddl-auto=update will not reliably drop or
-- rewrite once the @Table annotation's constraint definition changes. That
-- has to be done explicitly here.
--
-- Safe with no data cleanup: global uniqueness is strictly stronger than
-- per-tenant uniqueness, so every row already on production today already
-- satisfies the new, looser (client_id, <id>) constraint -- there is no
-- possible existing duplicate for the new constraint to reject.
--
-- plaid_item -- the most severe of the three: this table also carries each
-- item's encrypted Plaid access token, so an item_id collision here was the
-- one case where an unscoped lookup could have pointed one church's own
-- sync at a different church's actual bank data (PlaidLinkService's re-link
-- lookup, previously itemRepo.findByItemId(itemId) with no clientId).
--
-- plaid_account -- an account_id collision could have resolved a synced
-- transaction onto another tenant's account row.
--
-- plaid_transaction_staging -- a transaction_id collision could have let one
-- tenant's sync silently overwrite another tenant's already-reviewed
-- staging row (PlaidSyncService.upsertTransaction/markRemoved).
--
-- Database audit H7 (Sept 2026): rewritten as one idempotent block. The six
-- plain ALTERs this replaced were never run against production (verified
-- 16 Sep 2026: all three tables still carried BOTH the old global key and the
-- new per-tenant one), and could not be re-run anywhere the current build had
-- already started -- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS, and
-- Hibernate had already created the composite keys from the entities, so each
-- ADD failed. This block drops every single-column unique key on the external
-- id column -- whatever it is called, constraint or bare index -- and adds the
-- per-tenant key only when it is absent. SchemaFixService performs the same
-- drop at every application start, so either path repairs a database.
DO $$
DECLARE
    r RECORD;
    k RECORD;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            ('plaid_item',                'item_id',              'uq_plaid_item_client_item_id',    'client_id, item_id'),
            ('plaid_account',             'account_id',           'uq_plaid_account_client_account', 'client_id, account_id'),
            ('plaid_transaction_staging', 'plaid_transaction_id', 'uq_plaid_txn_client_txn_id',      'client_id, plaid_transaction_id')
        ) AS v(tbl, col, new_key, new_cols)
    LOOP
        IF to_regclass('public.' || r.tbl) IS NULL THEN
            RAISE NOTICE 'M7: % not present - skipped (Hibernate creates it with the per-tenant key).', r.tbl;
            CONTINUE;
        END IF;

        -- Every non-partial UNIQUE key covering exactly (col), primary key excepted.
        FOR k IN
            SELECT i.relname AS index_name, c.conname AS constraint_name
              FROM pg_index x
              JOIN pg_class t          ON t.oid = x.indrelid
              JOIN pg_namespace n      ON n.oid = t.relnamespace
              JOIN pg_class i          ON i.oid = x.indexrelid
              JOIN pg_attribute a      ON a.attrelid = t.oid AND a.attnum = x.indkey[0]
              LEFT JOIN pg_constraint c ON c.conindid = i.oid AND c.contype IN ('u', 'p')
             WHERE n.nspname = 'public' AND t.relname = r.tbl
               AND x.indisunique AND NOT x.indisprimary
               AND x.indnatts = 1 AND x.indpred IS NULL
               AND a.attname = r.col
             ORDER BY i.relname
        LOOP
            IF k.constraint_name IS NOT NULL THEN
                EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I', r.tbl, k.constraint_name);
                RAISE NOTICE 'M7: dropped global unique constraint % on %.%.', k.constraint_name, r.tbl, r.col;
            ELSE
                EXECUTE format('DROP INDEX IF EXISTS %I', k.index_name);
                RAISE NOTICE 'M7: dropped global unique index % on %.%.', k.index_name, r.tbl, r.col;
            END IF;
        END LOOP;

        -- The per-tenant key: already present wherever the current build has started.
        IF EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = r.new_key AND conrelid = to_regclass('public.' || r.tbl))
           OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relname = r.new_key AND c.relkind = 'i') THEN
            RAISE NOTICE 'M7: % already present on % - no change.', r.new_key, r.tbl;
        ELSE
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (%s)', r.tbl, r.new_key, r.new_cols);
            RAISE NOTICE 'M7: added per-tenant unique key % on % (%).', r.new_key, r.tbl, r.new_cols;
        END IF;
    END LOOP;
END $$;

-- PlaidWebhookService's own item_id lookup (webhook payloads carry only an
-- item_id, with no tenant context at all -- discovering the tenant that way
-- IS the mechanism, the same as a login-by-email lookup) is deliberately
-- left global and is NOT affected by this migration; only the write-path
-- lookups above -- which already had a trusted, known clientId in hand --
-- were unscoped by mistake.

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Database audit P1 — public membership form: legacy membership_family
-- columns (ddl-auto can neither drop a column nor relax its NOT NULL).
-- ═══════════════════════════════════════════════════════════════════════════

-- membership_family once carried the family's name and address. Those moved to
-- the Head membership_family_member row and the entity no longer maps them, but
-- family_name stayed NOT NULL with no default in the database. Hibernate's INSERT
-- never mentions a column it does not map, so PostgreSQL rejected every row and
-- the public membership form (POST /api/membership-form/submit) could not store
-- a single submission — membership_family had 0 rows on 2026-09-16.
--
-- SchemaFixService now drops the NOT NULL at startup as well; this block does the
-- same, then removes the seven dead columns — but only when none of them holds
-- data (all were empty on production 2026-09-16). Idempotent: safe to re-run.
DO $$
DECLARE
    v_nullable  text;
    v_cond      text;
    v_populated bigint;
BEGIN
    IF to_regclass('public.membership_family') IS NULL THEN
        RAISE NOTICE 'membership_family not present - nothing to do.';
        RETURN;
    END IF;

    SELECT is_nullable INTO v_nullable
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'membership_family'
       AND column_name = 'family_name';

    IF v_nullable = 'NO' THEN
        ALTER TABLE membership_family ALTER COLUMN family_name DROP NOT NULL;
        RAISE NOTICE 'membership_family.family_name: NOT NULL dropped - the public membership form can store submissions again.';
    ELSIF v_nullable = 'YES' THEN
        RAISE NOTICE 'membership_family.family_name already nullable - no change.';
    ELSE
        RAISE NOTICE 'membership_family.family_name already removed - no change.';
    END IF;

    -- Which of the seven legacy columns are still present? Build the emptiness
    -- test from the catalogue so a partially-migrated table is handled too.
    SELECT string_agg(format('(%I IS NOT NULL AND %I <> %L)', column_name, column_name, ''), ' OR ')
      INTO v_cond
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'membership_family'
       AND column_name IN ('family_name', 'family_address1', 'family_address2',
                           'family_city', 'family_state', 'family_country', 'family_pin_code');

    IF v_cond IS NULL THEN
        RAISE NOTICE 'membership_family: legacy name/address columns already dropped - no change.';
        RETURN;
    END IF;

    EXECUTE 'SELECT count(*) FROM membership_family WHERE ' || v_cond INTO v_populated;

    IF v_populated > 0 THEN
        RAISE NOTICE 'membership_family: % row(s) still carry legacy name/address values - columns kept. '
                     'Move those values onto the Head membership_family_member row, then re-run.', v_populated;
    ELSE
        ALTER TABLE membership_family
            DROP COLUMN IF EXISTS family_name,
            DROP COLUMN IF EXISTS family_address1,
            DROP COLUMN IF EXISTS family_address2,
            DROP COLUMN IF EXISTS family_city,
            DROP COLUMN IF EXISTS family_state,
            DROP COLUMN IF EXISTS family_country,
            DROP COLUMN IF EXISTS family_pin_code;
        RAISE NOTICE 'membership_family: seven empty legacy name/address columns dropped.';
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Database audit C1 — payroll rate columns were numeric(38,2)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Every payroll BigDecimal column was created by ddl-auto without an explicit
-- precision, and Hibernate's PostgreSQL default for that is numeric(38,2). For
-- amounts that is harmless; for RATES it is not: PostgreSQL rounds on insert,
-- silently, so the 2026 FICA seed (0.062 / 0.0145 / 0.009) was stored as
-- 0.06 / 0.01 / 0.01 (verified in production 16 Sep 2026), and every paystub
-- computed since withheld Social Security at 6.00 % instead of 6.20 % and
-- Medicare at 1.00 % instead of 1.45 %. A state flat rate such as 0.0525 would
-- become 0.05, a 3.5 % percentage deduction 4 %.
--
-- The entities now declare @Column(precision, scale) on every rate column
-- (numeric(9,6); numeric(15,6) for the two columns that hold either a flat
-- amount or a rate). ddl-auto never changes an existing column's type, so the
-- columns are widened here (and by SchemaFixService at every application
-- start), and the FICA row is repaired where it still carries exactly the
-- rounded seed. Widening never loses data: every stored value fits the new
-- type. Both blocks are no-ops on re-run.
--
-- What this script cannot do: recover a state rate or a percentage-based
-- deduction that was ENTERED while the columns were numeric(38,2) -- the
-- extra decimals were discarded on insert. It reports how many such rows
-- exist so they can be re-entered from their source. (Production had none on
-- 16 Sep 2026: no state configuration, no percentage-based deductions.)
-- Paystubs already computed at the rounded rates are not recomputed here;
-- the affected ones are listed by the read-only query in
-- ChurchGeniusPro-Database-Consistency-Audit.md (C1) for a void-and-re-run
-- decision with the bookkeeper.

-- 1. Widen the rate columns -----------------------------------------------
DO $$
DECLARE
    r        RECORD;
    v_scale  INTEGER;
    v_widened INTEGER := 0;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            ('payroll_fica_rate',            'social_security_rate',     'numeric(9,6)'),
            ('payroll_fica_rate',            'medicare_rate',            'numeric(9,6)'),
            ('payroll_fica_rate',            'additional_medicare_rate', 'numeric(9,6)'),
            ('payroll_federal_bracket',      'rate',                     'numeric(9,6)'),
            ('payroll_state_bracket',        'rate',                     'numeric(9,6)'),
            ('payroll_state_tax_config',     'flat_rate',                'numeric(9,6)'),
            ('payroll_state_tax_config',     'local_tax_rate',           'numeric(9,6)'),
            ('payroll_employee_deduction',   'amount_or_rate',           'numeric(15,6)'),
            ('payroll_deduction_definition', 'default_amount',           'numeric(15,6)')
        ) AS v(tbl, col, typ)
    LOOP
        SELECT numeric_scale INTO v_scale
          FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = r.tbl AND column_name = r.col
           AND data_type = 'numeric';
        IF NOT FOUND THEN
            RAISE NOTICE 'C1: %.% not present - skipped (a newer build creates it with the right type).', r.tbl, r.col;
        ELSIF v_scale >= 6 THEN
            RAISE NOTICE 'C1: %.% already has scale % - no change.', r.tbl, r.col, v_scale;
        ELSE
            EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE %s', r.tbl, r.col, r.typ);
            v_widened := v_widened + 1;
            RAISE NOTICE 'C1: %.% widened from scale % to %.', r.tbl, r.col, v_scale, r.typ;
        END IF;
    END LOOP;
    RAISE NOTICE 'C1: % rate column(s) widened.', v_widened;
END $$;

-- 2. Repair the rounded 2026 FICA seed and report what cannot be repaired ----
DO $$
DECLARE
    v_scale     INTEGER;
    v_repaired  INTEGER := 0;
    v_row       RECORD;
    v_state     INTEGER;
    v_deduction INTEGER;
BEGIN
    IF to_regclass('public.payroll_fica_rate') IS NULL THEN
        RAISE NOTICE 'C1: payroll_fica_rate not present - nothing to repair (seeded on the first payroll run).';
        RETURN;
    END IF;

    SELECT numeric_scale INTO v_scale
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'payroll_fica_rate'
       AND column_name = 'social_security_rate';
    IF v_scale IS NULL OR v_scale < 3 THEN
        RAISE WARNING 'C1: payroll_fica_rate.social_security_rate still has scale % - the widening above did not take effect, so the seed cannot be repaired.', v_scale;
        RETURN;
    END IF;

    -- Only the exact damage numeric(38,2) did to the seed is touched:
    -- 0.062 -> 0.06, 0.0145 -> 0.01, 0.009 -> 0.01. A row that differs in any
    -- other way is reported, not changed.
    UPDATE payroll_fica_rate
       SET social_security_rate     = 0.062,
           medicare_rate            = 0.0145,
           additional_medicare_rate = 0.009
     WHERE effective_year = 2026
       AND social_security_rate     = 0.06
       AND medicare_rate            = 0.01
       AND additional_medicare_rate = 0.01;
    GET DIAGNOSTICS v_repaired = ROW_COUNT;
    IF v_repaired > 0 THEN
        RAISE NOTICE 'C1: 2026 FICA rates restored to 0.062 / 0.0145 / 0.009 (were 0.06 / 0.01 / 0.01). Paystubs computed before this used the rounded rates - review them.';
    END IF;

    FOR v_row IN SELECT effective_year, social_security_rate, medicare_rate, additional_medicare_rate,
                        social_security_wage_base, additional_medicare_threshold
                   FROM payroll_fica_rate ORDER BY effective_year
    LOOP
        IF v_row.effective_year = 2026
           AND (v_row.social_security_rate <> 0.062 OR v_row.medicare_rate <> 0.0145
                OR v_row.additional_medicare_rate <> 0.009
                OR v_row.social_security_wage_base <> 184500 OR v_row.additional_medicare_threshold <> 200000) THEN
            RAISE WARNING 'C1: payroll_fica_rate 2026 = % / % / % (wage base %, threshold %) does not match the statutory 0.062 / 0.0145 / 0.009 / 184500 / 200000 - payroll will refuse to run until it is corrected.',
                v_row.social_security_rate, v_row.medicare_rate, v_row.additional_medicare_rate,
                v_row.social_security_wage_base, v_row.additional_medicare_threshold;
        ELSE
            RAISE NOTICE 'C1: payroll_fica_rate % = % / % / % (wage base %, threshold %).',
                v_row.effective_year, v_row.social_security_rate, v_row.medicare_rate,
                v_row.additional_medicare_rate, v_row.social_security_wage_base, v_row.additional_medicare_threshold;
        END IF;
    END LOOP;

    -- Rates entered while the columns were numeric(38,2) cannot be recovered here.
    v_state := 0;
    IF to_regclass('public.payroll_state_tax_config') IS NOT NULL THEN
        SELECT count(*) INTO v_state FROM payroll_state_tax_config WHERE flat_rate IS NOT NULL OR local_tax_rate <> 0;
    END IF;
    IF to_regclass('public.payroll_state_bracket') IS NOT NULL THEN
        SELECT v_state + count(*) INTO v_state FROM payroll_state_bracket;
    END IF;
    v_deduction := 0;
    IF to_regclass('public.payroll_deduction_definition') IS NOT NULL THEN
        SELECT count(*) INTO v_deduction FROM payroll_deduction_definition WHERE percentage_based;
    END IF;
    IF v_state > 0 OR v_deduction > 0 THEN
        RAISE WARNING 'C1: % state rate row(s) and % percentage-based deduction definition(s) were entered while rates were rounded to two decimals - re-enter their rates from source (the discarded decimals cannot be recovered).', v_state, v_deduction;
    ELSE
        RAISE NOTICE 'C1: no state rates and no percentage-based deductions exist - nothing to re-enter.';
    END IF;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Database audit P3 — legacy columns left behind by entity refactors
-- ═══════════════════════════════════════════════════════════════════════════
--
-- ddl-auto never drops a column, so every field an entity lost is still a
-- column in production: family.member_renewal_date, family_member.renewal_date
-- (renewal moved to the membership tables) and subscription_plan.extra_sms_count
-- (extra SMS credits are granted per client on service_client, not per plan).
-- The seven membership_family.family_* columns of the same kind are handled in
-- the P1 section above, which also has to relax the NOT NULL on family_name.
--
-- Verified 16 Sep 2026 (supplement S.6): none of the nine holds a value —
-- 0 of 61 families, 0 of 125 members, 0 of 4 plans. Each column is dropped
-- only if that is still true when this runs; a column that has acquired data
-- is kept and reported, never emptied. SchemaFixService performs the same
-- guarded drop at every application start. No-op on re-run.
DO $$
DECLARE
    r           RECORD;
    v_type      text;
    v_pred      text;
    v_populated bigint;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            ('family',            'member_renewal_date'),
            ('family_member',     'renewal_date'),
            ('subscription_plan', 'extra_sms_count')
        ) AS v(tbl, col)
    LOOP
        SELECT data_type INTO v_type
          FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = r.tbl AND column_name = r.col;
        IF NOT FOUND THEN
            RAISE NOTICE 'P3: %.% already removed.', r.tbl, r.col;
            CONTINUE;
        END IF;
        -- NULL, the empty string and zero all count as "no value": that is what an
        -- unmapped column with a default accumulates.
        IF v_type IN ('character varying', 'character', 'text') THEN
            v_pred := format('%I IS NOT NULL AND %I <> %L', r.col, r.col, '');
        ELSIF v_type IN ('integer', 'bigint', 'smallint', 'numeric', 'double precision', 'real') THEN
            v_pred := format('%I IS NOT NULL AND %I <> 0', r.col, r.col);
        ELSE
            v_pred := format('%I IS NOT NULL', r.col);
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE %s', r.tbl, v_pred) INTO v_populated;
        IF v_populated > 0 THEN
            RAISE WARNING 'P3: %.% still holds a value in % row(s) - kept; no entity maps it, decide what to do with that data before dropping it by hand.',
                r.tbl, r.col, v_populated;
        ELSE
            EXECUTE format('ALTER TABLE %I DROP COLUMN IF EXISTS %I', r.tbl, r.col);
            RAISE NOTICE 'P3: %.% dropped (unmapped by any entity, held no data).', r.tbl, r.col;
        END IF;
    END LOOP;
END $$;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2026-09 Database audit P6 — historical startup migrations, retired from code
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Until 16 Sep 2026 SchemaFixService performed three data migrations at every
-- application start: income.method and expense.method (the transaction type's
-- id stored as text) were copied into transaction_type_id and the column
-- dropped; family_member.signup_ref (an integer FK) was resolved to
-- signup.client_id in member_ref and dropped. Production verification P6
-- confirmed all three have long since run (none of the columns exists), so
-- the code was retired here, where an older database — a restored backup, a
-- forgotten environment — can still be brought forward.
--
-- Two things are different from the code that ran in production (audit M5):
-- the backfill no longer skips soft-deleted rows or rows whose transaction
-- type is inactive, and a column is dropped only when NO row would lose a
-- value with it — anything that could not be mapped is reported and the
-- column stays. No-op on re-run and on any database where the columns are gone.
DO $$
DECLARE
    r         RECORD;
    v_updated bigint;
    v_left    bigint;
BEGIN
    FOR r IN SELECT * FROM (VALUES ('income'), ('expense')) AS v(tbl)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = r.tbl AND column_name = 'method') THEN
            RAISE NOTICE 'P6: %.method already removed.', r.tbl;
            CONTINUE;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = r.tbl AND column_name = 'transaction_type_id') THEN
            RAISE NOTICE 'P6: %.transaction_type_id not present yet (deploy the application first) - skipped.', r.tbl;
            CONTINUE;
        END IF;
        -- method held the transaction_type primary key as a numeric string, e.g. '5'.
        EXECUTE format(
            'UPDATE %I t SET transaction_type_id = tt.id FROM transaction_type tt '
            || 'WHERE t.method ~ %L AND t.method::integer = tt.id AND t.transaction_type_id IS NULL',
            r.tbl, '^[0-9]+$');
        GET DIAGNOSTICS v_updated = ROW_COUNT;
        EXECUTE format('SELECT count(*) FROM %I WHERE method IS NOT NULL AND method <> %L AND transaction_type_id IS NULL',
                       r.tbl, '') INTO v_left;
        IF v_left > 0 THEN
            RAISE WARNING 'P6: %.method - % row(s) backfilled, but % row(s) still carry a method no transaction_type matches; column kept, map those rows by hand before dropping it.',
                r.tbl, v_updated, v_left;
        ELSE
            EXECUTE format('ALTER TABLE %I DROP COLUMN method', r.tbl);
            RAISE NOTICE 'P6: %.method dropped after backfilling % row(s) into transaction_type_id.', r.tbl, v_updated;
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE
    k         RECORD;
    v_updated bigint;
    v_left    bigint;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'family_member' AND column_name = 'signup_ref') THEN
        RAISE NOTICE 'P6: family_member.signup_ref already removed.';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'family_member' AND column_name = 'member_ref') THEN
        RAISE NOTICE 'P6: family_member.member_ref not present yet (deploy the application first) - skipped.';
        RETURN;
    END IF;
    UPDATE family_member fm
       SET member_ref = s.client_id
      FROM signup s
     WHERE fm.signup_ref = s.id
       AND fm.member_ref IS NULL
       AND s.client_id IS NOT NULL
       AND s.client_id LIKE 'MBR%';
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    SELECT count(*) INTO v_left FROM family_member WHERE signup_ref IS NOT NULL AND member_ref IS NULL;
    IF v_left > 0 THEN
        RAISE WARNING 'P6: family_member.signup_ref - % row(s) backfilled, but % row(s) still have a signup_ref and no member_ref; column kept, resolve those rows by hand before dropping it.',
            v_updated, v_left;
        RETURN;
    END IF;
    FOR k IN SELECT c.conname
               FROM pg_constraint c
               JOIN pg_class t ON t.oid = c.conrelid
               JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
              WHERE t.relname = 'family_member' AND a.attname = 'signup_ref'
    LOOP
        EXECUTE format('ALTER TABLE family_member DROP CONSTRAINT IF EXISTS %I', k.conname);
    END LOOP;
    ALTER TABLE family_member DROP COLUMN signup_ref;
    RAISE NOTICE 'P6: family_member.signup_ref dropped after backfilling % row(s) into member_ref.', v_updated;
END $$;

-- ── Standard plan: Kids Portal limit = 3 (2026-09-28) ─────────────────────
-- The seeder only inserts missing plans, so existing databases keep the old
-- value (NULL = unlimited). Guarded on NULL so a limit a Service Admin has
-- deliberately set on the Subscription Plans screen is never overwritten.
UPDATE subscription_plan
   SET max_kids_portals = 3
 WHERE UPPER(plan_code) = 'STANDARD'
   AND max_kids_portals IS NULL;

-- ── Public-submission notifications (2026-09-28) ─────────────────────────
-- One row per church per submission (Prayer / Connect / Membership / Donation);
-- visibility is decided at read time by role, permissions, plan and active account.
CREATE TABLE IF NOT EXISTS public_submission_notification (
    id          BIGSERIAL PRIMARY KEY,
    client_id   VARCHAR(100) NOT NULL,
    type        VARCHAR(20)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    source_id   BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_psn_client_created ON public_submission_notification (client_id, created_at);

CREATE TABLE IF NOT EXISTS public_submission_notification_state (
    id               BIGSERIAL PRIMARY KEY,
    notification_id  BIGINT       NOT NULL,
    user_key         VARCHAR(128) NOT NULL,
    read_at          TIMESTAMP WITH TIME ZONE,
    dismissed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_psn_state_user UNIQUE (notification_id, user_key)
);
CREATE INDEX IF NOT EXISTS ix_psn_state_user ON public_submission_notification_state (user_key);

-- ── Standard plan: features + limits + church user limit (2026-09-28) ──────
ALTER TABLE subscription_plan ADD COLUMN IF NOT EXISTS max_staff_users INTEGER;   -- NULL = unlimited

UPDATE subscription_plan
   SET max_people           = 100,
       max_emails_per_month = 50,
       max_sms_per_month    = 50,
       max_member_portals   = 3,
       max_kids_portals     = 3,
       max_staff_users      = 10
 WHERE UPPER(plan_code) = 'STANDARD';

-- Bank Sync, Payroll, AI Voice, AI Converse and Scan Check are NOT on Standard.
-- Merges into the existing flags (other flags a Service Admin set are kept).
DO $$
BEGIN
  UPDATE subscription_plan
     SET features_json = (COALESCE(NULLIF(features_json, ''), '{}')::jsonb
                          || '{"bankSync":false,"payroll":false,"aiVoice":false,"aiConverse":false,"scanCheck":false}'::jsonb)::text
   WHERE UPPER(plan_code) = 'STANDARD';
EXCEPTION WHEN others THEN
  RAISE NOTICE 'Standard plan features_json could not be merged (%) — set the flags on the Subscription Plans screen.', SQLERRM;
END $$;

-- ── Midwest Region Meet retired (2026-09-28) ─────────────────────────────
-- Only the obsolete plan flag is removed from plan configuration. The meet's own
-- tables (mid_reg_meet, mid_reg_meet_rsvp), its donations (donation / income rows)
-- and any existing public_screen_link rows are deliberately NOT touched — see
-- docs/midregmeet-data-check.sql to inspect them before deciding anything.
DO $$
BEGIN
  UPDATE subscription_plan
     SET features_json = (features_json::jsonb - 'midRegMeet')::text
   WHERE features_json IS NOT NULL AND features_json <> ''
     AND features_json::jsonb ? 'midRegMeet';
EXCEPTION WHEN others THEN
  RAISE NOTICE 'midRegMeet flag cleanup skipped (%) — harmless; the key is ignored.', SQLERRM;
END $$;

-- ── Ticketing (2026-10-01) ────────────────────────────────────────────────
-- Support tickets a church raises with the ChurchGeniusPro support team.
-- Tenant-scoped; read-only for the church after submission; a Service Admin
-- flips status Open ⇄ Closed. Mirrors hibernate/SupportTicket (ddl-auto creates
-- the same table in dev).
CREATE TABLE IF NOT EXISTS support_ticket (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(20)  NOT NULL,
    client_id         VARCHAR(100) NOT NULL,
    church_name       VARCHAR(255),
    subject           VARCHAR(200) NOT NULL,
    submitter_name    VARCHAR(200) NOT NULL,
    submitter_email   VARCHAR(320) NOT NULL,
    description       TEXT         NOT NULL,
    urgency           VARCHAR(10)  NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'Open',
    client_package    VARCHAR(60),
    submitted_by      VARCHAR(200),
    submitted_role    VARCHAR(30),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP,
    status_changed_at TIMESTAMP,
    status_changed_by VARCHAR(200),
    CONSTRAINT uq_support_ticket_ref UNIQUE (reference)
);
CREATE INDEX IF NOT EXISTS ix_support_ticket_client ON support_ticket (client_id, created_at);
CREATE INDEX IF NOT EXISTS ix_support_ticket_status ON support_ticket (status, created_at);

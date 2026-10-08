-- ============================================================
-- TEST DATA SEED SCRIPT
-- client_id : CHRd3e3292f-d0a4-4e6d-8c04-dc787d017f6a
-- user_id   : USRbd88159d-5c17-47bf-b5a8-9dcdb7a9c9ad
-- Safe to run multiple times (uses DO $$ blocks with IF NOT EXISTS logic)
-- Does NOT touch any other client_id's data
-- ============================================================

DO $$
DECLARE
  v_client   VARCHAR := 'CHRd3e3292f-d0a4-4e6d-8c04-dc787d017f6a';
  v_user_id  VARCHAR := 'USRbd88159d-5c17-47bf-b5a8-9dcdb7a9c9ad';
  v_now      TIMESTAMP := NOW();

  -- lookup / inserted IDs
  v_ms1 INTEGER; v_ms2 INTEGER;
  v_ss1 INTEGER; v_ss2 INTEGER; v_ss3 INTEGER;
  v_pur1 INTEGER; v_pur2 INTEGER; v_pur3 INTEGER;
  v_tt_cash INTEGER; v_tt_check INTEGER; v_tt_zelle INTEGER;
  v_fam1 INTEGER; v_fam2 INTEGER; v_fam3 INTEGER; v_fam4 INTEGER; v_fam5 INTEGER;
  v_fm1 INTEGER; v_fm2 INTEGER; v_fm3 INTEGER; v_fm4 INTEGER; v_fm5 INTEGER;
  v_fm6 INTEGER; v_fm7 INTEGER; v_fm8 INTEGER; v_fm9 INTEGER; v_fm10 INTEGER;
  v_fm11 INTEGER; v_fm12 INTEGER;
  v_mt1 INTEGER;
  v_grp1 INTEGER; v_grp2 INTEGER;
  v_evt1 INTEGER; v_evt2 INTEGER;

BEGIN

-- ── 0. app_user (the test admin account) ────────────────────────────────────
-- id is a sequence-backed column — omit it and let the sequence provide it
INSERT INTO app_user (user_id, client_id, first_name, last_name, email, phone,
  address1, city, state, country, pin_code, role, enabled, delete_flag, created_date)
SELECT v_user_id, v_client, 'Demo', 'Admin', 'demo.admin@gracefellowship.org',
  '555-200-1000', '100 Grace Ave', 'Springfield', 'IL', 'USA', '62701',
  'SuperAdmin', true, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM app_user WHERE user_id = v_user_id);

-- ── 1. transaction_type ──────────────────────────────────────────────────────
INSERT INTO transaction_type (type_name, app_client_id, delete_flag, created_date)
SELECT 'Cash', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM transaction_type WHERE type_name='Cash' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO transaction_type (type_name, app_client_id, delete_flag, created_date)
SELECT 'Check', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM transaction_type WHERE type_name='Check' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO transaction_type (type_name, app_client_id, delete_flag, created_date)
SELECT 'Zelle', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM transaction_type WHERE type_name='Zelle' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_tt_cash  FROM transaction_type WHERE type_name='Cash'  AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_tt_check FROM transaction_type WHERE type_name='Check' AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_tt_zelle FROM transaction_type WHERE type_name='Zelle' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

-- ── 2. main_source (income fund categories) ──────────────────────────────────
INSERT INTO main_source (source_name, app_client_id, delete_flag, created_date)
SELECT 'General Fund', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM main_source WHERE source_name='General Fund' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO main_source (source_name, app_client_id, delete_flag, created_date)
SELECT 'Building Fund', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM main_source WHERE source_name='Building Fund' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_ms1 FROM main_source WHERE source_name='General Fund'  AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_ms2 FROM main_source WHERE source_name='Building Fund' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

-- ── 3. sub_source ────────────────────────────────────────────────────────────
INSERT INTO sub_source (source_name, main_source_id, app_client_id, delete_flag, created_date)
SELECT 'Sunday Tithe', v_ms1, v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM sub_source WHERE source_name='Sunday Tithe' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO sub_source (source_name, main_source_id, app_client_id, delete_flag, created_date)
SELECT 'Special Offering', v_ms1, v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM sub_source WHERE source_name='Special Offering' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO sub_source (source_name, main_source_id, app_client_id, delete_flag, created_date)
SELECT 'Building Contribution', v_ms2, v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM sub_source WHERE source_name='Building Contribution' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_ss1 FROM sub_source WHERE source_name='Sunday Tithe'         AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_ss2 FROM sub_source WHERE source_name='Special Offering'     AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_ss3 FROM sub_source WHERE source_name='Building Contribution' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

-- ── 4. purpose (expense categories) ─────────────────────────────────────────
INSERT INTO purpose (purpose_name, app_client_id, delete_flag, created_date)
SELECT 'Utilities', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM purpose WHERE purpose_name='Utilities' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO purpose (purpose_name, app_client_id, delete_flag, created_date)
SELECT 'Pastoral Salary', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM purpose WHERE purpose_name='Pastoral Salary' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO purpose (purpose_name, app_client_id, delete_flag, created_date)
SELECT 'Ministry Supplies', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM purpose WHERE purpose_name='Ministry Supplies' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_pur1 FROM purpose WHERE purpose_name='Utilities'       AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_pur2 FROM purpose WHERE purpose_name='Pastoral Salary' AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_pur3 FROM purpose WHERE purpose_name='Ministry Supplies' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

-- ── 5. families ──────────────────────────────────────────────────────────────
INSERT INTO family (inactive, app_client_id, delete_flag, created_date) VALUES (false, v_client, false, v_now) RETURNING id INTO v_fam1;
INSERT INTO family (inactive, app_client_id, delete_flag, created_date) VALUES (false, v_client, false, v_now) RETURNING id INTO v_fam2;
INSERT INTO family (inactive, app_client_id, delete_flag, created_date) VALUES (false, v_client, false, v_now) RETURNING id INTO v_fam3;
INSERT INTO family (inactive, app_client_id, delete_flag, created_date) VALUES (false, v_client, false, v_now) RETURNING id INTO v_fam4;
INSERT INTO family (inactive, app_client_id, delete_flag, created_date) VALUES (false, v_client, false, v_now) RETURNING id INTO v_fam5;

-- ── 6. family_member ─────────────────────────────────────────────────────────

-- Family 1: Anderson
INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  anniversary_month, anniversary_day, anniversary_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam1, 'Head', 'James', 'Anderson', '555-101-2001', 'james.anderson@gracefellowship.org',
  'Member', 'Male', 4, 12, 1975,
  6, 18, 2002,
  '214 Oak Street', 'Springfield', 'IL', 'USA', '62702', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm1;

INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam1, 'Wife', 'Linda', 'Anderson', '555-101-2002', 'linda.anderson@email.com',
  'Member', 'Female', 9, 3, 1977,
  '214 Oak Street', 'Springfield', 'IL', 'USA', '62702', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm2;

INSERT INTO family_member (family_id, role, first_name, last_name,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  same_as_family_address, inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam1, 'Son', 'Ethan', 'Anderson',
  'Member', 'Male', 2, 20, 2005,
  true, false, false, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm3;

-- Family 2: Martinez
INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  anniversary_month, anniversary_day, anniversary_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam2, 'Head', 'Carlos', 'Martinez', '555-202-3001', 'carlos.martinez@email.com',
  'Member', 'Male', 7, 5, 1980,
  11, 14, 2006,
  '87 Maple Avenue', 'Decatur', 'IL', 'USA', '62521', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm4;

INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam2, 'Wife', 'Sofia', 'Martinez', '555-202-3002', 'sofia.martinez@email.com',
  'Member', 'Female', 3, 28, 1983,
  '87 Maple Avenue', 'Decatur', 'IL', 'USA', '62521', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm5;

-- Family 3: Johnson
INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam3, 'Head', 'Robert', 'Johnson', '555-303-4001', 'robert.johnson@email.com',
  'Member', 'Male', 1, 17, 1968,
  '321 Elm Drive', 'Bloomington', 'IL', 'USA', '61701', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm6;

INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  same_as_family_address, inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam3, 'Wife', 'Margaret', 'Johnson', '555-303-4002', 'margaret.johnson@email.com',
  'Member', 'Female', 5, 9, 1971,
  true, false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm7;

INSERT INTO family_member (family_id, role, first_name, last_name,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  same_as_family_address, inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam3, 'Daughter', 'Grace', 'Johnson',
  'Member', 'Female', 8, 14, 2008,
  true, false, false, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm8;

-- Family 4: Williams (single member)
INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam4, 'Head', 'Patricia', 'Williams', '555-404-5001', 'patricia.williams@email.com',
  'Member', 'Female', 10, 22, 1955,
  '45 Pine Lane', 'Springfield', 'IL', 'USA', '62704', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm9;

-- Family 5: Thompson
INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  anniversary_month, anniversary_day, anniversary_year,
  address1, city, state, country, pin_code, same_as_family_address,
  inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam5, 'Head', 'David', 'Thompson', '555-505-6001', 'david.thompson@email.com',
  'Member', 'Male', 12, 1, 1972,
  8, 25, 1999,
  '900 Cedar Court', 'Champaign', 'IL', 'USA', '61820', true,
  false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm10;

INSERT INTO family_member (family_id, role, first_name, last_name, phone, email,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  same_as_family_address, inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam5, 'Wife', 'Susan', 'Thompson', '555-505-6002', 'susan.thompson@email.com',
  'Member', 'Female', 6, 30, 1975,
  true, false, true, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm11;

INSERT INTO family_member (family_id, role, first_name, last_name,
  member_type, gender, birthday_month, birthday_day, birthday_year,
  same_as_family_address, inactive, include_contributions, app_client_id, delete_flag,
  member_ref, created_date)
VALUES (v_fam5, 'Son', 'Noah', 'Thompson',
  'Member', 'Male', 3, 7, 2010,
  true, false, false, v_client, false,
  'MBR' || gen_random_uuid(), v_now)
RETURNING id INTO v_fm12;

-- ── 7. income ────────────────────────────────────────────────────────────────
-- Sunday tithes
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss1, '2026-01-05', v_tt_check, 500.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm4, v_ss1, '2026-01-05', v_tt_cash,  350.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm6, v_ss1, '2026-01-05', v_tt_zelle, 600.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm10,v_ss1, '2026-01-05', v_tt_check, 425.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm9, v_ss1, '2026-01-05', v_tt_cash,  200.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');

INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss1, '2026-02-02', v_tt_check, 500.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm4, v_ss1, '2026-02-02', v_tt_cash,  350.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm6, v_ss1, '2026-02-02', v_tt_zelle, 600.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm10,v_ss1, '2026-02-02', v_tt_check, 425.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');

INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss1, '2026-03-02', v_tt_check, 500.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm4, v_ss1, '2026-03-02', v_tt_cash,  350.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm6, v_ss1, '2026-03-02', v_tt_zelle, 600.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm10,v_ss1, '2026-03-02', v_tt_check, 450.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');

-- Special offerings
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm2, v_ss2, '2026-01-19', v_tt_cash,  150.00, 'Easter offering', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm7, v_ss2, '2026-01-19', v_tt_check, 250.00, 'Easter offering', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm11,v_ss2, '2026-02-16', v_tt_zelle, 100.00, 'Valentine''s mission offering', false, v_client, false, v_now, 'demo.admin');
-- Anonymous guest offering
INSERT INTO income (sub_source_id, income_date, transaction_type_id, amount, guest_name, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_ss2, '2026-03-15', v_tt_cash, 75.00, 'Anonymous Guest', 'Walk-in offering', false, v_client, false, v_now, 'demo.admin');

-- Building fund contributions
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, ref_no, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss3, '2026-01-12', v_tt_check, 'CHK-1021', 1000.00, 'Building fund pledge Q1', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, ref_no, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm4, v_ss3, '2026-01-12', v_tt_check, 'CHK-1022', 750.00,  'Building fund pledge Q1', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm6, v_ss3, '2026-02-09', v_tt_zelle, 500.00, 'Building fund February', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm9, v_ss3, '2026-03-09', v_tt_cash,  200.00, 'Building fund March',    false, v_client, false, v_now, 'demo.admin');

-- April & May tithes (recent months)
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss1, '2026-04-06', v_tt_check, 500.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm4, v_ss1, '2026-04-06', v_tt_cash,  375.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm6, v_ss1, '2026-04-06', v_tt_zelle, 600.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm1, v_ss1, '2026-05-04', v_tt_check, 500.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');
INSERT INTO income (member_id, sub_source_id, income_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_fm10,v_ss1, '2026-05-04', v_tt_check, 425.00, 'Weekly tithe', false, v_client, false, v_now, 'demo.admin');

-- ── 8. expense ───────────────────────────────────────────────────────────────
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur1, v_ms1, '2026-01-15', v_tt_check, 320.00, 'January electricity bill',  false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur1, v_ms1, '2026-01-15', v_tt_check, 95.00,  'January water bill',        false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur2, v_ms1, '2026-01-31', v_tt_check, 3500.00,'Pastor salary – January',   false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur3, v_ms1, '2026-01-20', v_tt_cash,  185.00, 'Sunday school supplies',    false, v_client, false, v_now, 'demo.admin');

INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur1, v_ms1, '2026-02-15', v_tt_check, 310.00, 'February electricity bill', false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur2, v_ms1, '2026-02-28', v_tt_check, 3500.00,'Pastor salary – February',  false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur3, v_ms1, '2026-02-10', v_tt_cash,  220.00, 'Worship team supplies',     false, v_client, false, v_now, 'demo.admin');

INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur1, v_ms1, '2026-03-15', v_tt_check, 298.00, 'March electricity bill',    false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur2, v_ms1, '2026-03-31', v_tt_check, 3500.00,'Pastor salary – March',     false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, ref_no, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur1, v_ms2, '2026-03-20', v_tt_check, 'CHK-2010', 1200.00,'Building maintenance – roof repair', false, v_client, false, v_now, 'demo.admin');

INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur2, v_ms1, '2026-04-30', v_tt_check, 3500.00,'Pastor salary – April',     false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur3, v_ms1, '2026-04-14', v_tt_cash,  310.00, 'Easter event supplies',     false, v_client, false, v_now, 'demo.admin');
INSERT INTO expense (purpose_id, main_source_id, expense_date, transaction_type_id, amount, note, quick_add, app_client_id, delete_flag, created_date, created_by)
VALUES (v_pur2, v_ms1, '2026-05-31', v_tt_check, 3500.00,'Pastor salary – May',       false, v_client, false, v_now, 'demo.admin');

-- ── 9. meeting_type & meeting ────────────────────────────────────────────────
INSERT INTO meeting_type (type_name, app_client_id, delete_flag, created_date)
SELECT 'Sunday Service', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM meeting_type WHERE type_name='Sunday Service' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_mt1 FROM meeting_type WHERE type_name='Sunday Service' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

INSERT INTO meeting (meeting_type_id, meeting_date, start_time, end_time,
  address1, city, state, country, pin_code,
  note, occurrence, app_client_id, delete_flag, do_not_auto_delete, created_date)
VALUES (v_mt1, '2026-01-05', '09:00', '11:00',
  '100 Grace Ave', 'Springfield', '13', 'USA', '62701',
  'Regular Sunday morning service', 'Weekly', v_client, false, true, v_now);

INSERT INTO meeting (meeting_type_id, meeting_date, start_time, end_time,
  address1, city, state, country, pin_code,
  note, occurrence, app_client_id, delete_flag, do_not_auto_delete, created_date)
VALUES (v_mt1, '2026-03-29', '10:00', '12:30',
  '100 Grace Ave', 'Springfield', '13', 'USA', '62701',
  'Easter Sunday special service', 'One-time', v_client, false, true, v_now);

-- ── 10. app_group & group_member ─────────────────────────────────────────────
INSERT INTO app_group (group_name, app_client_id, delete_flag, created_date)
SELECT 'Worship Team', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM app_group WHERE group_name='Worship Team' AND app_client_id=v_client AND delete_flag=false);

INSERT INTO app_group (group_name, app_client_id, delete_flag, created_date)
SELECT 'Prayer Warriors', v_client, false, v_now
WHERE NOT EXISTS (
  SELECT 1 FROM app_group WHERE group_name='Prayer Warriors' AND app_client_id=v_client AND delete_flag=false);

SELECT id INTO v_grp1 FROM app_group WHERE group_name='Worship Team'    AND app_client_id=v_client AND delete_flag=false LIMIT 1;
SELECT id INTO v_grp2 FROM app_group WHERE group_name='Prayer Warriors' AND app_client_id=v_client AND delete_flag=false LIMIT 1;

INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp1, 'James',    'Anderson', 'james.anderson@gracefellowship.org', v_client, false, v_now);
INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp1, 'Sofia',    'Martinez', 'sofia.martinez@email.com', v_client, false, v_now);
INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp1, 'Susan',    'Thompson', 'susan.thompson@email.com', v_client, false, v_now);
INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp2, 'Margaret', 'Johnson',  'margaret.johnson@email.com', v_client, false, v_now);
INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp2, 'Patricia', 'Williams', 'patricia.williams@email.com', v_client, false, v_now);
INSERT INTO group_member (group_id, first_name, last_name, email, app_client_id, delete_flag, created_date)
VALUES (v_grp2, 'Linda',    'Anderson', 'linda.anderson@email.com', v_client, false, v_now);

-- ── 11. church_event ─────────────────────────────────────────────────────────
INSERT INTO church_event (event_name, event_code, event_type, event_date, start_time, end_time,
  registration_end_date, fee, max_capacity, show_registrants, allow_maybe_rsvp,
  generate_qr_code, self_checkin_enabled, address1, city, state, country, pin_code,
  host_name, host_phone, host_email,
  note, food_available, accommodation_available,
  app_client_id, delete_flag, created_date, created_by)
VALUES ('Spring Family Picnic', 'EVT-PICNIC-2026', 'One Day', '2026-05-30', '11:00', '16:00',
  '2026-05-25', 'Free', 200, true, true,
  true, true, 'Centennial Park', 'Springfield', 'IL', 'USA', '62701',
  'James Anderson', '555-101-2001', 'james.anderson@gracefellowship.org',
  'Annual spring family gathering with games, food, and fellowship.',
  true, false,
  v_client, false, v_now, 'demo.admin')
RETURNING id INTO v_evt1;

INSERT INTO church_event (event_name, event_code, event_type, event_date, start_time, end_time,
  registration_end_date, fee, max_capacity, show_registrants, allow_maybe_rsvp,
  generate_qr_code, self_checkin_enabled, address1, city, state, country, pin_code,
  host_name, host_phone, host_email,
  note, food_available, accommodation_available,
  app_client_id, delete_flag, created_date, created_by)
VALUES ('Vacation Bible School 2026', 'EVT-VBS-2026', 'Multiple Days', '2026-07-13', '09:00', '12:00',
  '2026-07-10', 'Free', 100, true, false,
  true, true, '100 Grace Ave', 'Springfield', 'IL', 'USA', '62701',
  'Linda Anderson', '555-101-2002', 'linda.anderson@email.com',
  'Week-long Vacation Bible School for kids ages 4–12. Theme: Roar!',
  true, false,
  v_client, false, v_now, 'demo.admin')
RETURNING id INTO v_evt2;

-- ── 12. note ─────────────────────────────────────────────────────────────────
INSERT INTO note (title, body, created_by_name, starred, app_client_id, created_date, delete_flag)
VALUES ('Welcome to Grace Fellowship',
  'Grace Fellowship Church was founded in 1987 with a mission to love God, love people, and serve the community. We meet every Sunday at 9:00 AM.',
  'Demo Admin', true, v_client, v_now, false);

INSERT INTO note (title, body, created_by_name, starred, app_client_id, created_date, delete_flag)
VALUES ('Pastoral Team Contact Info',
  'Senior Pastor: Rev. John Richards – pastor@gracefellowship.org – 555-100-0001. Associate Pastor: Rev. Mary Collins – 555-100-0002.',
  'Demo Admin', false, v_client, v_now, false);

INSERT INTO note (title, body, created_by_name, starred, app_client_id, created_date, delete_flag)
VALUES ('Building Fund Goal 2026',
  'Target: $50,000 for sanctuary roof replacement and parking lot resurfacing. Current progress as of May 2026: $12,450 raised.',
  'Demo Admin', true, v_client, v_now, false);

RAISE NOTICE 'Test data seeded successfully for client_id=%', v_client;

END $$;

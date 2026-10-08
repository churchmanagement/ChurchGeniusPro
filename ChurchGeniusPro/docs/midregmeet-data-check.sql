-- Midwest Region Meet (retired 2026-09-28) — READ-ONLY checks.
-- Run against production BEFORE deciding whether any of this data can be archived.
-- Nothing in the application reads or writes these rows any more; nothing was deleted.

-- 1. Configured meets, per church
SELECT client_id, COUNT(*) AS meets FROM mid_reg_meet GROUP BY client_id ORDER BY meets DESC;

-- 2. RSVPs, per church
SELECT client_id, COUNT(*) AS rsvps, MIN(created_at) AS first_rsvp, MAX(created_at) AS last_rsvp
  FROM mid_reg_meet_rsvp GROUP BY client_id ORDER BY rsvps DESC;

-- 3. Published public RSVP links (they now refuse to open; rows kept)
SELECT app_client_id, COUNT(*) AS links, SUM(CASE WHEN revoked THEN 0 ELSE 1 END) AS not_revoked
  FROM public_screen_link WHERE page_url LIKE '/midRegMeetRsvp%' GROUP BY app_client_id;

-- 4. Plans still carrying the old flag (migrate_production.sql removes it)
SELECT plan_code, features_json FROM subscription_plan WHERE features_json LIKE '%midRegMeet%';

-- 5. Donations taken through the meet's giving page. They live in the shared "donation"
--    table (note starts with 'Midwest Region Meet') and were posted to "income" like every
--    other online gift. They are FINANCIAL RECORDS and must be kept.
SELECT client_id, COUNT(*) AS gifts, SUM(amount) AS total
  FROM donation WHERE note LIKE 'Midwest Region Meet%' GROUP BY client_id;

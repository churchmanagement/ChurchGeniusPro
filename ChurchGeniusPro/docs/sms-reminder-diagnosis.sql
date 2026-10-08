-- =====================================================================
-- Why did the event-reminder SMS on 2026-08-19 reach only two numbers?
-- =====================================================================
-- Run these against PRODUCTION. They are all read-only.
--
-- The code path could not answer this question at the time, because it
-- recorded nothing per recipient. These queries reconstruct as much as the
-- existing data allows, and each one corresponds to one of the four causes
-- that produce exactly this symptom. From the next send onward the answer is
-- in event_registration_reminder_log directly (query 6) and on the
-- Event Reminders page under "Delivery".
--
--   +13463705928 and +19134569296 are the two that arrived.
-- ---------------------------------------------------------------------


-- 1. WHICH REMINDER FIRED, AND FOR WHICH EVENT
--    reference_key looks like '<eventId>_SAME_DAY' / '_BEFORE_3' / '_AFTER_1'.
--    If this returns nothing for the 19th, the job never ran or never matched a
--    date — a different problem from a partial send.
SELECT r.id, r.app_client_id, r.reminder_type, r.reference_key, r.sent_date,
       e.id AS event_id, e.event_name, e.event_date
  FROM reminder_sent_log r
  LEFT JOIN church_event e
         ON e.id = split_part(r.reference_key, '_', 1)::int
 WHERE r.reminder_type = 'EVENT'
   AND r.sent_date BETWEEN DATE '2026-08-18' AND DATE '2026-08-20'
 ORDER BY r.sent_date, r.reference_key;


-- 2. HOW MANY PEOPLE SHOULD HAVE RECEIVED IT
--    This is the exact set the scheduler iterates: RSVP Yes (true) or Maybe
--    (null), never No (false). Substitute the event id from query 1.
--    If "with_phone" is 2, the recipient list itself was the problem and no
--    amount of send-side fixing would have helped.
SELECT COUNT(*)                                            AS remindable,
       COUNT(*) FILTER (WHERE phone IS NOT NULL
                          AND btrim(phone) <> '')          AS with_phone,
       COUNT(*) FILTER (WHERE attending IS TRUE)           AS rsvp_yes,
       COUNT(*) FILTER (WHERE attending IS NULL)           AS rsvp_maybe
  FROM event_registration
 WHERE event_id = :event_id
   AND (attending IS NULL OR attending = TRUE);


-- 3. CAUSE A — PHONE NUMBERS THE NORMALISER REFUSES
--    These were dropped with no log line at any level. Anything listed here
--    never reached Twilio at all. A number must be 10 NANP digits (optionally
--    with a leading 1), or start with '+' and a country code.
SELECT id, first_name, last_name, phone, attending
  FROM event_registration
 WHERE event_id = :event_id
   AND (attending IS NULL OR attending = TRUE)
   AND phone IS NOT NULL AND btrim(phone) <> ''
   -- crude equivalent of PhoneNumbers.toE164 refusing the value
   AND NOT (
         regexp_replace(phone, '\D', '', 'g') ~ '^1?[2-9]\d{2}[2-9]\d{6}$'
      OR (btrim(phone) LIKE '+%'
          AND length(regexp_replace(phone, '\D', '', 'g')) BETWEEN 8 AND 15)
   )
 ORDER BY id;


-- 4. CAUSE B — MONTHLY SMS ALLOWANCE EXHAUSTED
--    If sms_sent had already reached the plan limit, every send after the
--    first couple was refused before it ever reached Twilio.
--    Look at the August row for the church that owns the event.
SELECT u.client_id, u.usage_month, u.sms_sent,
       p.name AS plan, p.max_sms_per_month,
       (u.sms_sent >= p.max_sms_per_month) AS was_exhausted
  FROM subscription_usage u
  LEFT JOIN service_client sc ON sc.client_id = u.client_id
  LEFT JOIN subscription_plan p ON p.id = sc.plan_id
 WHERE u.usage_month IN ('2026-08', '2026-07')
 ORDER BY u.client_id, u.usage_month;
--    NOTE: column/table names for the plan join vary; if this errors, run
--    just the subscription_usage part and read max_sms_per_month separately.


-- 5. CAUSE C — RECIPIENTS THE CARRIER BLOCKS (replied STOP)
--    A number that opted out is refused by Twilio with error 21610. The app
--    never consulted its own opt-in table on the reminder path, so it kept
--    trying and kept failing invisibly.
SELECT o.phone_number, o.app_client_id, o.status, o.confirmed_date
  FROM sms_opt_in o
 WHERE o.phone_number IN (
         SELECT '+1' || right(regexp_replace(phone, '\D', '', 'g'), 10)
           FROM event_registration
          WHERE event_id = :event_id
            AND (attending IS NULL OR attending = TRUE)
            AND phone IS NOT NULL
       )
 ORDER BY o.status, o.phone_number;
--    Cross-check the definitive answer in the Twilio console:
--      Monitor > Logs > Messaging, filtered to 2026-08-19.
--    Error 30034 on most rows means the A2P 10DLC campaign is unregistered,
--    which blocks nearly all US traffic and looks exactly like this incident.


-- 6. FROM NOW ON — THE DIRECT ANSWER
--    Every attempt is recorded here, with the reason it did not arrive.
--    message_type is SAME_DAY / BEFORE / AFTER for event reminders
--    (REMINDER / INVITE belong to the separate registration-reminder feature).
SELECT created_date, message_type, channel, recipient, status, reason
  FROM event_registration_reminder_log
 WHERE event_id = :event_id
   AND message_type IN ('SAME_DAY', 'BEFORE', 'AFTER')
 ORDER BY created_date DESC;

--    And the summary the scheduler now writes to the application log:
--      grep "Event reminders:" churchgeniuspro.log
--    e.g. "event 42 [SAME_DAY] SMS — 2 sent, 1 skipped, 37 failed (of 40 registrant(s))"

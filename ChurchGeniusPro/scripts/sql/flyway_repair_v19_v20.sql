-- ═══════════════════════════════════════════════════════════════════════════
-- One-time Flyway checksum update for V19 / V20 (2026-10-16)
-- ═══════════════════════════════════════════════════════════════════════════
--
-- V19__etl_source_identity.sql and V20__etl_import_link.sql gained to_regclass guards so
-- a brand-new database no longer fails at V19 (Flyway runs before Hibernate creates the
-- tables). Their effect on an existing database is unchanged, but their checksums changed,
-- so a database that ALREADY applied the old versions refuses to start with
--   "Migration checksum mismatch for migration version 19"
-- Run this once on such a database (this is exactly what `flyway repair` would record).
--
-- Safe to run anywhere, any number of times:
--   • only rows that still carry the OLD checksum are touched;
--   • a database that never applied V19/V20 (e.g. production before the ETL release)
--     has no such rows → 0 rows updated, nothing to do;
--   • no schema object is created, changed or dropped.
--
-- Production: NOT needed for the ETL release if V19/V20 were never applied there
-- (check: SELECT version, checksum FROM flyway_schema_history WHERE version IN ('19','20');).
-- Never run against production without approval.

UPDATE flyway_schema_history SET checksum = -726679248
 WHERE version = '19' AND checksum = -1360049728;

UPDATE flyway_schema_history SET checksum = 1456191994
 WHERE version = '20' AND checksum = 833216381;

SELECT version, description, checksum, success
  FROM flyway_schema_history
 WHERE version IN ('19', '20')
 ORDER BY installed_rank;

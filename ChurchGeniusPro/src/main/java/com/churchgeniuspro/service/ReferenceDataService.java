package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deleting, restoring and permanently deleting the accounting reference lists (2026-11-16):
 * main fund categories, their sub-categories, purposes (expense categories) and transaction types.
 *
 * <p><b>Rules.</b>
 * <ul>
 *   <li><b>Soft delete</b> hides an entry from every list used to enter NEW transactions. Nothing that refers to
 *       it is touched: income, expenses, campaigns and reports keep their reference and its name. Deleting a main
 *       category also deletes its active sub-categories and marks them {@code deleted_with_parent}.</li>
 *   <li><b>Restore</b> makes the entry usable again. Restoring a main category brings back the sub-categories
 *       that were deleted with it — not those deleted on their own earlier.</li>
 *   <li><b>Permanent delete</b> removes the row, and is allowed ONLY when nothing refers to it (income and
 *       expense rows count even when they are themselves deleted — they are history). Otherwise it is refused
 *       with the list of what still uses it. No transaction is ever changed or removed by this class.</li>
 * </ul>
 *
 * <p>Every statement carries the church id; another church's id behaves exactly like an unknown id.
 */
@Service
public class ReferenceDataService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataService.class);

    public static final String MAIN = "main-source", SUB = "sub-source", PURPOSE = "purpose", TYPE = "transaction-type";

    /** Thrown when a permanent delete is refused because records still refer to the entry. */
    public static class InUseException extends RuntimeException {
        private final List<Map<String, Object>> uses;
        public InUseException(String message, List<Map<String, Object>> uses) { super(message); this.uses = uses; }
        public List<Map<String, Object>> getUses() { return uses; }
    }

    private record Kind(String table, String nameCol, String noun) {}

    private static Kind kind(String k) {
        return switch (k == null ? "" : k) {
            case MAIN    -> new Kind("main_source", "source_name", "main category");
            case SUB     -> new Kind("sub_source", "source_name", "sub-category");
            case PURPOSE -> new Kind("purpose", "purpose_name", "purpose");
            case TYPE    -> new Kind("transaction_type", "type_name", "transaction type");
            default      -> throw new IllegalArgumentException("Unknown kind.");
        };
    }

    private final JdbcTemplate jdbc;

    public ReferenceDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ── Soft delete ───────────────────────────────────────────────────────

    /** Soft-deletes an active entry. @return how many sub-categories were deleted with a main category (else 0). */
    @Transactional
    public int softDelete(String k, Integer id, String clientId, String actor) {
        Kind kd = kind(k);
        requireTenant(clientId);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String who = trim(actor);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + kd.table + " WHERE id = ? AND app_client_id = ? AND delete_flag = false",
                Integer.class, id, clientId) == 0) {
            throw new IllegalArgumentException(cap(kd.noun) + " not found.");
        }
        int subs = 0;
        if (MAIN.equals(k)) {
            subs = jdbc.update("UPDATE sub_source SET delete_flag = true, deleted_with_parent = true, deleted_at = ?, deleted_by = ? "
                    + "WHERE main_source_id = ? AND app_client_id = ? AND delete_flag = false", now, who, id, clientId);
        }
        jdbc.update("UPDATE " + kd.table + " SET delete_flag = true, deleted_at = ?, deleted_by = ?"
                + (SUB.equals(k) ? ", deleted_with_parent = false" : "")
                + " WHERE id = ? AND app_client_id = ? AND delete_flag = false", now, who, id, clientId);
        log.info("AUDIT reference-data soft-delete kind={} id={} church={} by={} subCategories={}", k, id, clientId, who, subs);
        return subs;
    }

    // ── Restore ───────────────────────────────────────────────────────────

    /** Restores a deleted entry. @return {@code name}, {@code restoredSubCategories}. */
    @Transactional
    public Map<String, Object> restore(String k, Integer id, String clientId, String actor) {
        Kind kd = kind(k);
        requireTenant(clientId);
        Map<String, Object> row = one("SELECT " + kd.nameCol + " AS name, delete_flag, deleted_at"
                + (SUB.equals(k) ? ", main_source_id" : "") + " FROM " + kd.table + " WHERE id = ? AND app_client_id = ?", id, clientId);
        if (row == null) throw new IllegalArgumentException(cap(kd.noun) + " not found.");
        String name = String.valueOf(row.get("name"));
        if (!Boolean.TRUE.equals(row.get("delete_flag"))) throw new IllegalArgumentException("“" + name + "” is not deleted.");

        if (SUB.equals(k)) {
            Integer mainId = ((Number) row.get("main_source_id")).intValue();
            Map<String, Object> main = one("SELECT source_name AS name, delete_flag FROM main_source WHERE id = ? AND app_client_id = ?", mainId, clientId);
            if (main == null || Boolean.TRUE.equals(main.get("delete_flag"))) {
                throw new IllegalArgumentException("Its main category “" + (main == null ? "?" : main.get("name"))
                        + "” is deleted. Restore the main category first.");
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sub_source WHERE main_source_id = ? AND app_client_id = ? AND delete_flag = false "
                    + "AND LOWER(source_name) = LOWER(?) AND id <> ?", Integer.class, mainId, clientId, name, id) > 0) {
                throw new IllegalArgumentException("An active sub-category named “" + name + "” already exists in this main category. Rename it first.");
            }
        } else if (jdbc.queryForObject("SELECT COUNT(*) FROM " + kd.table + " WHERE app_client_id = ? AND delete_flag = false AND LOWER("
                + kd.nameCol + ") = LOWER(?) AND id <> ?", Integer.class, clientId, name, id) > 0) {
            throw new IllegalArgumentException("An active " + kd.noun + " named “" + name + "” already exists. Rename it first.");
        }

        int subs = 0;
        if (MAIN.equals(k)) {
            // Deleted before 2026-11-16 (no deleted_at): it is not recorded which sub-categories went with it, so
            // all of its deleted sub-categories come back; any that should stay deleted can be deleted again.
            boolean legacy = row.get("deleted_at") == null;
            subs = jdbc.update("UPDATE sub_source SET delete_flag = false, deleted_with_parent = NULL, deleted_at = NULL, deleted_by = NULL "
                    + "WHERE main_source_id = ? AND app_client_id = ? AND delete_flag = true AND "
                    + (legacy ? "deleted_at IS NULL" : "deleted_with_parent = true"), id, clientId);
        }
        jdbc.update("UPDATE " + kd.table + " SET delete_flag = false, deleted_at = NULL, deleted_by = NULL"
                + (SUB.equals(k) ? ", deleted_with_parent = NULL" : "") + " WHERE id = ? AND app_client_id = ?", id, clientId);
        log.info("AUDIT reference-data restore kind={} id={} church={} by={} subCategories={}", k, id, clientId, trim(actor), subs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("restoredSubCategories", subs);
        return out;
    }

    // ── What still uses an entry ──────────────────────────────────────────

    /**
     * Everything that refers to the entry: {@code [{label, count}]}, only counts above zero. For a main category
     * this includes what refers to any of its sub-categories.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> usage(String k, Integer id, String clientId) {
        Kind kd = kind(k);
        requireTenant(clientId);
        if (one("SELECT id FROM " + kd.table + " WHERE id = ? AND app_client_id = ?", id, clientId) == null) {
            throw new IllegalArgumentException(cap(kd.noun) + " not found.");
        }
        List<Map<String, Object>> uses = new ArrayList<>();
        switch (k) {
            case SUB -> subUsage(uses, "= ?", id, clientId, "");
            case MAIN -> {
                add(uses, "expense entries paid from this fund", count("expense", "main_source_id = ? AND app_client_id = ?", id, clientId));
                add(uses, "Donation Settings (fee expense fund)", count("donation_settings", "expense_main_source_id = ? AND client_id = ?", id, clientId));
                add(uses, "Bank Sync transactions", count("plaid_transaction_staging", "mapped_main_source_id = ? AND client_id = ?", id, clientId));
                add(uses, "data import records", count("import_link", "entity = 'ref:main_source' AND target_id = ? AND client_id = ?", id, clientId));
                subUsage(uses, "IN (SELECT id FROM sub_source WHERE main_source_id = ? AND app_client_id = ?)", id, clientId, " (in its sub-categories)");
            }
            case PURPOSE -> {
                add(uses, "expense entries", count("expense", "purpose_id = ? AND app_client_id = ?", id, clientId));
                add(uses, "Donation Settings (fee expense category)", count("donation_settings", "expense_purpose_id = ? AND client_id = ?", id, clientId));
                add(uses, "Bank Sync transactions", count("plaid_transaction_staging", "mapped_purpose_id = ? AND client_id = ?", id, clientId));
                add(uses, "data import records", count("import_link", "entity = 'ref:purpose' AND target_id = ? AND client_id = ?", id, clientId));
            }
            default -> {
                add(uses, "income entries", count("income", "transaction_type_id = ? AND app_client_id = ?", id, clientId));
                add(uses, "expense entries", count("expense", "transaction_type_id = ? AND app_client_id = ?", id, clientId));
                add(uses, "Donation Settings (payment method)", count("donation_settings",
                        "(income_transaction_type_id = ? OR expense_transaction_type_id = ?) AND client_id = ?", id, id, clientId));
                add(uses, "Bank Sync transactions", count("plaid_transaction_staging", "mapped_transaction_type_id = ? AND client_id = ?", id, clientId));
                add(uses, "data import records", count("import_link", "entity = 'ref:transaction_type' AND target_id = ? AND client_id = ?", id, clientId));
            }
        }
        return uses;
    }

    private void subUsage(List<Map<String, Object>> uses, String match, Integer id, String clientId, String suffix) {
        boolean in = match.startsWith("IN");
        Object[] a = in ? new Object[] { id, clientId, clientId } : new Object[] { id, clientId };
        add(uses, "income entries" + suffix, count("income", "sub_source_id " + match + " AND app_client_id = ?", a));
        add(uses, "pledge campaigns" + suffix, count("pledge_campaign", "sub_source_id " + match + " AND client_id = ?", a));
        add(uses, "online donations" + suffix, count("donation", "sub_source_id " + match + " AND client_id = ?", a));
        add(uses, "Donation Settings (income fund)" + suffix, count("donation_settings", "income_sub_source_id " + match + " AND client_id = ?", a));
        add(uses, "Bank Sync transactions" + suffix, count("plaid_transaction_staging", "mapped_sub_source_id " + match + " AND client_id = ?", a));
        add(uses, "data import records" + suffix, count("import_link", "entity = 'ref:sub_source' AND target_id " + match + " AND client_id = ?", a));
    }

    // ── Permanent delete ──────────────────────────────────────────────────

    /**
     * Removes the entry for good — only when nothing refers to it. Never changes or removes a transaction.
     *
     * @throws InUseException when records still refer to it (nothing is deleted)
     */
    @Transactional
    public Map<String, Object> permanentDelete(String k, Integer id, String clientId, String actor) {
        Kind kd = kind(k);
        List<Map<String, Object>> uses = usage(k, id, clientId);
        String name = String.valueOf(one("SELECT " + kd.nameCol + " AS name FROM " + kd.table + " WHERE id = ? AND app_client_id = ?", id, clientId).get("name"));
        if (!uses.isEmpty()) {
            throw new InUseException("“" + name + "” cannot be permanently deleted because existing records still refer to it. "
                    + "Those records are kept exactly as they are. Use Delete (soft delete) instead: it hides the "
                    + kd.noun + " from new entries and keeps the history readable.", uses);
        }
        int subs = 0;
        if (MAIN.equals(k)) subs = jdbc.update("DELETE FROM sub_source WHERE main_source_id = ? AND app_client_id = ?", id, clientId);
        jdbc.update("DELETE FROM " + kd.table + " WHERE id = ? AND app_client_id = ?", id, clientId);
        log.info("AUDIT reference-data permanent-delete kind={} id={} church={} by={} subCategories={}", k, id, clientId, trim(actor), subs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("removedSubCategories", subs);
        return out;
    }

    // ── Deleted entries (for the settings pages and the report filters) ───

    /**
     * Deleted entries of one list. {@code fund}: deleted main categories each with its deleted sub-categories,
     * plus sub-categories deleted on their own under an active main category.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> deleted(String list, String clientId) {
        requireTenant(clientId);
        Map<String, Object> out = new LinkedHashMap<>();
        switch (list == null ? "" : list) {
            case "fund" -> {
                List<Map<String, Object>> mains = jdbc.queryForList("SELECT id, source_name AS name, deleted_at, deleted_by FROM main_source "
                        + "WHERE app_client_id = ? AND delete_flag = true ORDER BY LOWER(source_name), id", clientId);
                for (Map<String, Object> m : mains) {
                    m.put("subCategories", stamps(jdbc.queryForList("SELECT id, source_name AS name, deleted_with_parent FROM sub_source "
                            + "WHERE main_source_id = ? AND app_client_id = ? AND delete_flag = true ORDER BY LOWER(source_name), id", m.get("id"), clientId)));
                }
                out.put("mainCategories", stamps(mains));
                out.put("subCategories", stamps(jdbc.queryForList("SELECT s.id, s.source_name AS name, s.main_source_id, m.source_name AS main_name, "
                        + "s.deleted_at, s.deleted_by FROM sub_source s JOIN main_source m ON m.id = s.main_source_id "
                        + "WHERE s.app_client_id = ? AND s.delete_flag = true AND m.delete_flag = false ORDER BY LOWER(m.source_name), LOWER(s.source_name), s.id", clientId)));
            }
            case "purpose" -> out.put("items", stamps(jdbc.queryForList("SELECT id, purpose_name AS name, deleted_at, deleted_by FROM purpose "
                    + "WHERE app_client_id = ? AND delete_flag = true ORDER BY LOWER(purpose_name), id", clientId)));
            case "transaction-type" -> out.put("items", stamps(jdbc.queryForList("SELECT id, type_name AS name, deleted_at, deleted_by FROM transaction_type "
                    + "WHERE app_client_id = ? AND delete_flag = true ORDER BY LOWER(type_name), id", clientId)));
            default -> throw new IllegalArgumentException("Unknown list.");
        }
        return out;
    }

    /** Deleted sub-categories (their own flag or their main category's) — for the Income Report filter. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deletedSubCategories(String clientId) {
        requireTenant(clientId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT s.id, s.source_name, s.main_source_id, m.source_name AS main_name "
                + "FROM sub_source s JOIN main_source m ON m.id = s.main_source_id "
                + "WHERE s.app_client_id = ? AND (s.delete_flag = true OR m.delete_flag = true) ORDER BY LOWER(m.source_name), LOWER(s.source_name), s.id", clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("sourceName", r.get("source_name"));
            m.put("mainSourceId", r.get("main_source_id"));
            m.put("mainSourceName", r.get("main_name"));
            m.put("deleted", true);
            out.add(m);
        }
        return out;
    }

    /** Deleted purposes — for the Expense Report filter. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deletedPurposes(String clientId) {
        requireTenant(clientId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, purpose_name FROM purpose WHERE app_client_id = ? AND delete_flag = true "
                + "ORDER BY LOWER(purpose_name), id", clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("name", r.get("purpose_name"));
            m.put("deleted", true);
            out.add(m);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static List<Map<String, Object>> stamps(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((key, v) -> m.put(camel(key), v instanceof java.util.Date d ? new Timestamp(d.getTime()).toLocalDateTime().toString() : v));
            out.add(m);
        }
        return out;
    }
    private static String camel(String s) {
        if (s.indexOf('_') < 0) return s;
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c); up = false;
        }
        return b.toString();
    }
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        rows.get(0).forEach((key, v) -> m.put(key.toLowerCase(java.util.Locale.ROOT), v));
        return m;
    }
    private long count(String table, String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class, args);
        return n == null ? 0 : n;
    }
    private static void add(List<Map<String, Object>> uses, String label, long n) {
        if (n <= 0) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label); m.put("count", n);
        uses.add(m);
    }
    private static void requireTenant(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Not found.");
    }
    private static String trim(String s) { return s == null ? null : (s.length() > 100 ? s.substring(0, 100) : s); }
    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}

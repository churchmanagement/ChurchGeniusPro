package com.churchgeniuspro.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inserts the smallest row a table accepts, derived from the catalogue: {@code id} and every
 * {@code *_id} column get {@code 1} (so one row per table satisfies every foreign key),
 * every other NOT NULL column a type-appropriate placeholder, nullable columns nothing.
 * Lets the PostgreSQL integration tests exercise the real schema — real foreign keys,
 * real sequences — without depending on any entity's field list.
 */
final class MinimalRows {

    private MinimalRows() {}

    static void insert(JdbcTemplate jdbc, String table, Map<String, String> overrides) {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position", table);
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            String name = (String) c.get("column_name");
            String type = (String) c.get("data_type");
            boolean nullable = "YES".equals(c.get("is_nullable"));
            String value;
            if (overrides.containsKey(name)) {
                value = overrides.get(name);
            } else if (name.equals("id") || name.endsWith("_id")) {
                value = type.startsWith("character") || type.equals("text") ? "'1'" : "1";
            } else if (nullable) {
                continue;
            } else {
                value = placeholder(type);
            }
            names.add("\"" + name + "\"");
            values.add(value);
        }
        jdbc.execute("INSERT INTO \"" + table + "\" (" + String.join(", ", names) + ") VALUES ("
                + String.join(", ", values) + ")");
    }

    static void insert(JdbcTemplate jdbc, String table) {
        insert(jdbc, table, Map.of());
    }

    private static String placeholder(String dataType) {
        return switch (dataType) {
            case "integer", "bigint", "smallint", "numeric", "double precision", "real", "oid" -> "1";
            case "boolean" -> "false";
            case "date" -> "current_date";
            case "timestamp without time zone", "timestamp with time zone" -> "now()";
            case "time without time zone", "time with time zone" -> "now()::time";
            case "bytea" -> "'\\x00'";
            case "uuid" -> "gen_random_uuid()";
            case "json", "jsonb" -> "'{}'";
            default -> "'x'";
        };
    }
}

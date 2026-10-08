package com.churchgeniuspro.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit H2: {@code W4_tenant_columns.sql} gives the tenant-column-less tables a real
 * {@code client_id}, backfilled from the parent. This proves the backfill against a real
 * PostgreSQL: two tenants' rows, inserted with a NULL {@code client_id} (as pre-W4 rows are),
 * each resolve to their own tenant through the parent — including the two-level worship chain
 * ({@code worship_group_member → worship_instrument → worship_group}) and the parents whose
 * tenant column is {@code app_client_id} ({@code church_event}, {@code family_member}). No row
 * is left NULL and no tenant bleeds into another. Requires Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantColumnBackfillIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("W4 backfills client_id on the column-less tables from the parent, per tenant, with no leakage")
    void backfillDerivesTenantFromParent() throws Exception {
        // Relax NOT NULL on the tables we hand-seed (test only), so we can insert minimal rows.
        jdbc.queryForList(
            "SELECT 'ALTER TABLE '||quote_ident(table_name)||' ALTER COLUMN '||quote_ident(column_name)||' DROP NOT NULL' " +
            "FROM information_schema.columns WHERE table_schema='public' AND is_nullable='NO' AND column_name <> 'id' " +
            "AND table_name IN ('worship_group','worship_instrument','worship_group_member','church_event'," +
            "'church_event_day','family_member','member_preference','app_user','user_permissions'," +
            "'guess_it_game','guess_it_participant')", String.class).forEach(jdbc::execute);

        // Parents (TA = id 1, TB = id 101). client_id parents and app_client_id parents both covered.
        jdbc.execute("INSERT INTO worship_group(id,client_id) VALUES (1,'TA'),(101,'TB')");
        jdbc.execute("INSERT INTO church_event(id,app_client_id) VALUES (1,'TA'),(101,'TB')");
        jdbc.execute("INSERT INTO family_member(id,app_client_id) VALUES (1,'TA'),(101,'TB')");
        jdbc.execute("INSERT INTO app_user(id,client_id) VALUES (1,'TA'),(101,'TB')");
        jdbc.execute("INSERT INTO guess_it_game(id,client_id) VALUES (1,'TA'),(101,'TB')");
        // Children inserted WITHOUT client_id (as pre-W4 rows are) — the column exists (Hibernate),
        // so NULL it out to be certain we are testing the backfill, not the insert.
        jdbc.execute("INSERT INTO worship_instrument(id,group_id) VALUES (1,1),(101,101)");
        jdbc.execute("INSERT INTO worship_group_member(id,instrument_id) VALUES (1,1),(101,101)");
        jdbc.execute("INSERT INTO church_event_day(id,event_id) VALUES (1,1),(101,101)");
        jdbc.execute("INSERT INTO member_preference(id,member_id) VALUES (1,1),(101,101)");
        jdbc.execute("INSERT INTO user_permissions(id,app_user_id) VALUES (1,1),(101,101)");
        jdbc.execute("INSERT INTO guess_it_participant(id,game_id) VALUES (1,1),(101,101)");
        for (String t : new String[]{"worship_instrument","worship_group_member","church_event_day",
                "member_preference","user_permissions","guess_it_participant"}) {
            jdbc.execute("UPDATE " + t + " SET client_id = NULL");
        }

        // Run the staged backfill.
        String w4 = new String(new ClassPathResource("db/window/W4_tenant_columns.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(w4);

        // Every child resolves to its own tenant; nothing left NULL; no cross-tenant.
        for (String t : new String[]{"worship_instrument","worship_group_member","church_event_day",
                "member_preference","user_permissions","guess_it_participant"}) {
            assertThat(tenantOf(t, 1)).as("%s id=1 -> TA", t).isEqualTo("TA");
            assertThat(tenantOf(t, 101)).as("%s id=101 -> TB", t).isEqualTo("TB");
            assertThat(nulls(t)).as("%s has no NULL client_id", t).isZero();
        }
    }

    private String tenantOf(String table, int id) {
        return jdbc.queryForObject("SELECT client_id FROM " + table + " WHERE id = ?", String.class, id);
    }

    private long nulls(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE client_id IS NULL", Long.class);
        return n == null ? -1 : n;
    }
}

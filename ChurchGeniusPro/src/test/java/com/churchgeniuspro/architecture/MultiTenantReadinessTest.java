package com.churchgeniuspro.architecture;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tenant readiness check. Every JPA entity should be scoped to a tenant
 * (a {@code clientId} / {@code appClientId} column) unless it is a deliberately
 * global reference/lookup table. This guards against a new entity shipping
 * without tenancy and leaking data across churches.
 *
 * <p>It does not hard-fail on every untenanted entity (some are legitimately
 * global); instead it enforces a coverage floor and prints the offenders so the
 * list can be reviewed and the floor raised over time.
 */
class MultiTenantReadinessTest {

    private static final String BASE = "com.churchgeniuspro.hibernate";
    private static final Set<String> TENANT_FIELDS = Set.of("clientId", "appClientId", "tenantId");
    private static final double COVERAGE_FLOOR = 0.80;

    @Test
    void entities_areTenantScoped() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        int total = 0, tenanted = 0;
        List<String> offenders = new ArrayList<>();

        for (var bd : scanner.findCandidateComponents(BASE)) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            total++;
            if (hasTenantField(c)) tenanted++;
            else offenders.add(c.getSimpleName());
        }

        assertTrue(total > 0, "expected to find JPA entities to scan");
        double coverage = (double) tenanted / total;

        Collections.sort(offenders);
        System.out.printf("[multi-tenant] %d/%d entities are tenant-scoped (%.1f%%)%n", tenanted, total, coverage * 100);
        if (!offenders.isEmpty()) {
            System.out.println("[multi-tenant] review these untenanted entities (global tables are OK): " + offenders);
        }

        assertTrue(coverage >= COVERAGE_FLOOR,
            String.format("Multi-tenant coverage %.1f%% is below the %.0f%% floor. Untenanted: %s",
                coverage * 100, COVERAGE_FLOOR * 100, offenders));
    }

    private boolean hasTenantField(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (TENANT_FIELDS.contains(f.getName())) return true;
            }
        }
        return false;
    }
}

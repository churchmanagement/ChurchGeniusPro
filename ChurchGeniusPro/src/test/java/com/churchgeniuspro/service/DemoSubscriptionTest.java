package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Subscription plan and expiry handling for Test/Demo Data loads.
 *
 * <p>The theme running through all of these is that a demo tenant is a real tenant: it uses
 * the same {@code subscription_plan} rows, the same {@code service_client} row and the same
 * expiry rule as a paying church. So the things worth pinning down are the places where a
 * shortcut would have been tempting — a hardcoded plan name, a second subscription record
 * for the demo, an expiry the admin screen reports differently from the login query.
 */
class DemoSubscriptionTest {

    private SubscriptionPlanRepository planRepo;
    private ServiceClientRepository    serviceClientRepo;
    private SubscriptionService        subscriptionService;
    private TestDataService            svc;

    @BeforeEach
    void setUp() throws Exception {
        planRepo            = mock(SubscriptionPlanRepository.class);
        serviceClientRepo   = mock(ServiceClientRepository.class);
        subscriptionService = mock(SubscriptionService.class);
        svc = newService();
        when(serviceClientRepo.save(any(ServiceClient.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Plans come from the database, never from this code ───────────────────

    @Nested
    @DisplayName("Plan selection")
    class PlanSelection {

        @Test
        @DisplayName("any plan configured in Subscription Plans can be chosen — nothing is hardcoded")
        void plansAreWhateverTheDatabaseSays() {
            // Deliberately not FREE/STANDARD/PRO. If the code carried a hardcoded list, a
            // church that renamed its plans would find the demo loader rejecting all of them.
            givenPlans(plan("PARISH_SMALL", "Parish (small)", true, 1),
                       plan("PARISH_LARGE", "Parish (large)", true, 2));

            assertEquals("PARISH_LARGE", svc.resolvePlanCode("PARISH_LARGE"));
            assertEquals("PARISH_SMALL", svc.resolvePlanCode("PARISH_SMALL"));
        }

        @Test
        @DisplayName("no plan requested falls back to the first active plan by sort order")
        void blankFallsBackToFirstActive() {
            givenPlans(plan("PRO",      "Pro",      true, 3),
                       plan("FREE",     "Free",     true, 1),
                       plan("STANDARD", "Standard", true, 2));
            // The repository method is ordered by sortOrder, so "first" is whatever it returns
            // first — the service must not re-sort or pick a favourite of its own.
            assertEquals("PRO", svc.resolvePlanCode(null));
            assertEquals("PRO", svc.resolvePlanCode("   "));
        }

        @Test
        @DisplayName("matching is case-insensitive but the stored code keeps the database's casing")
        void canonicalCasingWins() {
            givenPlans(plan("Pro", "Pro", true, 1));
            // Storing "pro" would break SubscriptionService.toPlanCode's lookup later.
            assertEquals("Pro", svc.resolvePlanCode("PRO"));
            assertEquals("Pro", svc.resolvePlanCode("pro"));
            assertEquals("Pro", svc.resolvePlanCode("  Pro  "));
        }

        @Test
        @DisplayName("an inactive plan cannot be used for a new demo load")
        void inactivePlansAreNotSelectable() {
            givenPlans(plan("FREE", "Free", true, 1),
                       plan("LEGACY", "Retired plan", false, 2));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.resolvePlanCode("LEGACY"));
            assertTrue(e.getMessage().contains("LEGACY"), e.getMessage());
            assertTrue(e.getMessage().contains("FREE"), "the message should list what IS available");
        }

        @Test
        @DisplayName("an unknown plan is refused, and the message names the ones that exist")
        void unknownPlanIsRefused() {
            givenPlans(plan("FREE", "Free", true, 1), plan("PRO", "Pro", true, 2));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.resolvePlanCode("ENTERPRISE"));
            assertTrue(e.getMessage().contains("FREE") && e.getMessage().contains("PRO"),
                    "admin needs to know the options: " + e.getMessage());
        }

        @Test
        @DisplayName("with no active plans configured, the error says what to do about it")
        void noPlansConfigured() {
            givenPlans(plan("LEGACY", "Retired", false, 1));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.resolvePlanCode(null));
            assertTrue(e.getMessage().toLowerCase().contains("subscription plans"), e.getMessage());
        }
    }

    // ── Expiry: one definition, shared by the badge and the login gate ────────

    @Nested
    @DisplayName("The expiry boundary")
    class ExpiryBoundary {

        @Test
        @DisplayName("expiry today means expired — the login SQL uses end_date > current_date")
        void todayIsAlreadyExpired() {
            LocalDate today = LocalDate.of(2026, 8, 20);
            // This is the one that bites: an admin sets "expires 20 Aug", and on the 20th the
            // user cannot log in. The badge has to say Expired that same day or the admin is
            // debugging a login failure against a screen that says everything is fine.
            assertTrue(SubscriptionService.isExpired(LocalDate.of(2026, 8, 20), today));
            assertTrue(SubscriptionService.isExpired(LocalDate.of(2026, 8, 19), today));
            assertFalse(SubscriptionService.isExpired(LocalDate.of(2026, 8, 21), today));
        }

        @Test
        @DisplayName("no end date is not an expiry — it matches SQL's NULL comparison")
        void nullEndDateIsNotExpired() {
            assertFalse(SubscriptionService.isExpired(null, LocalDate.of(2026, 8, 20)));
        }

        @Test
        @DisplayName("the admin badge agrees with the login rule on the boundary day")
        void badgeMatchesLoginRule() {
            givenPlans(plan("PRO", "Pro", true, 1));

            ServiceClient expiringToday = client("DEMO-1", "PRO", com.churchgeniuspro.util.AppClock.today().minusDays(30), com.churchgeniuspro.util.AppClock.today());
            assertEquals("Expired", svc.describeSubscription(expiringToday).get("status"));

            ServiceClient expiringTomorrow = client("DEMO-1", "PRO", com.churchgeniuspro.util.AppClock.today(), com.churchgeniuspro.util.AppClock.today().plusDays(1));
            assertEquals("Active", svc.describeSubscription(expiringTomorrow).get("status"));
        }

        @Test
        @DisplayName("a valid date does not make an inactive subscription look Active")
        void statusStillCounts() {
            givenPlans(plan("PRO", "Pro", true, 1));
            ServiceClient sc = client("DEMO-1", "PRO", com.churchgeniuspro.util.AppClock.today(), com.churchgeniuspro.util.AppClock.today().plusDays(30));
            sc.setStatus("Suspended");
            assertEquals("Expired", svc.describeSubscription(sc).get("status"),
                    "the login queries require status = 'Active' too");
        }

        @Test
        @DisplayName("the description reports the plan's display name, not the raw column value")
        void describeResolvesPlanName() {
            givenPlans(plan("PRO", "Pro", true, 1));
            when(planRepo.findByPlanCodeIgnoreCase("PRO"))
                    .thenReturn(Optional.of(plan("PRO", "Pro", true, 1)));

            // "FULL" is the legacy stored value for the PRO plan.
            Map<String, Object> d = svc.describeSubscription(
                    client("DEMO-1", "FULL", com.churchgeniuspro.util.AppClock.today(), com.churchgeniuspro.util.AppClock.today().plusDays(10)));

            assertEquals("PRO", d.get("planCode"), "legacy alias must resolve through toPlanCode");
            assertEquals("Pro", d.get("planName"));
            assertEquals(10L,   d.get("daysRemaining"));
        }
    }

    // ── Editing an existing demo subscription ────────────────────────────────

    @Nested
    @DisplayName("Edit and extend")
    class EditAndExtend {

        @BeforeEach
        void plans() {
            givenPlans(plan("FREE", "Free", true, 1), plan("PRO", "Pro", true, 2));
        }

        // ADDED 2026-10-05 (Phase 2, spec section 4) — no existing case moved a TRIAL- tenant.
        @Test
        @DisplayName("a sample-data trial (TRIAL-) cannot be moved to a paid plan; nothing is written")
        void sampleDataTrialNotConvertible() {
            ServiceClient trial = client("TRIAL-200", "TRIAL", com.churchgeniuspro.util.AppClock.today().minusDays(5), com.churchgeniuspro.util.AppClock.today().plusDays(25));
            when(serviceClientRepo.findByClientId("TRIAL-200")).thenReturn(Optional.of(trial));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.updateDemoSubscription("TRIAL-200", "PRO", null));
            assertTrue(e.getMessage().contains("cannot be converted"), e.getMessage());
            assertEquals("TRIAL", trial.getSubscriptionType());
            verify(serviceClientRepo, never()).save(any());
        }

        @Test
        @DisplayName("a sample-data trial can still be extended on the Trial plan")
        void sampleDataTrialExtendable() {
            givenPlans(plan("TRIAL", "Trial", true, 0), plan("PRO", "Pro", true, 2));
            ServiceClient trial = client("TRIAL-201", "TRIAL", com.churchgeniuspro.util.AppClock.today().minusDays(40), com.churchgeniuspro.util.AppClock.today().minusDays(1));
            when(serviceClientRepo.findByClientId("TRIAL-201")).thenReturn(Optional.of(trial));
            svc.updateDemoSubscription("TRIAL-201", "TRIAL", com.churchgeniuspro.util.AppClock.today().plusDays(30));
            assertEquals(com.churchgeniuspro.util.AppClock.today().plusDays(30), trial.getEndDate());
        }

        @Test
        @DisplayName("extending updates the tenant's existing row — it never creates a second one")
        void updatesInPlace() {
            ServiceClient existing = client("DEMO-100", "FREE",
                    com.churchgeniuspro.util.AppClock.today().minusDays(10), com.churchgeniuspro.util.AppClock.today().plusDays(5));
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(existing));

            svc.updateDemoSubscription("DEMO-100", null, com.churchgeniuspro.util.AppClock.today().plusDays(60));

            // The saved object must be the row we loaded. Anything else means a duplicate
            // subscription for the same tenant, which is exactly what the requirement forbids.
            verify(serviceClientRepo, times(1)).save(same(existing));
            assertEquals(com.churchgeniuspro.util.AppClock.today().plusDays(60), existing.getEndDate());
        }

        @Test
        @DisplayName("extending an expired demo past today makes it usable again")
        void extendingReactivates() {
            ServiceClient expired = client("DEMO-100", "FREE",
                    com.churchgeniuspro.util.AppClock.today().minusDays(90), com.churchgeniuspro.util.AppClock.today().minusDays(1));
            expired.setStatus("Expired");
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(expired));

            Map<String, Object> result =
                    svc.updateDemoSubscription("DEMO-100", null, com.churchgeniuspro.util.AppClock.today().plusDays(30));

            assertEquals("Active", expired.getStatus(),
                    "a date extension alone should not leave the admin needing a second edit");
            assertEquals("Active", result.get("status"));
        }

        @Test
        @DisplayName("extending backwards into the past does not silently reactivate")
        void shorteningDoesNotReactivate() {
            ServiceClient sc = client("DEMO-100", "FREE",
                    com.churchgeniuspro.util.AppClock.today().minusDays(90), com.churchgeniuspro.util.AppClock.today().minusDays(1));
            sc.setStatus("Expired");
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(sc));

            Map<String, Object> result =
                    svc.updateDemoSubscription("DEMO-100", null, com.churchgeniuspro.util.AppClock.today().minusDays(5));

            assertEquals("Expired", result.get("status"));
        }

        @Test
        @DisplayName("changing only the plan leaves the expiry alone, and vice versa")
        void partialUpdates() {
            LocalDate end = com.churchgeniuspro.util.AppClock.today().plusDays(20);
            ServiceClient sc = client("DEMO-100", "FREE", com.churchgeniuspro.util.AppClock.today().minusDays(5), end);
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(sc));

            svc.updateDemoSubscription("DEMO-100", "PRO", null);
            assertEquals("PRO", sc.getSubscriptionType());
            assertEquals(end, sc.getEndDate(), "expiry must not move when only the plan changed");

            svc.updateDemoSubscription("DEMO-100", null, end.plusDays(10));
            assertEquals("PRO", sc.getSubscriptionType(), "plan must not move when only the date changed");
            assertEquals(end.plusDays(10), sc.getEndDate());
        }

        @Test
        @DisplayName("the plan cache is cleared, so the new limits apply immediately")
        void cacheIsCleared() {
            ServiceClient sc = client("DEMO-100", "FREE", com.churchgeniuspro.util.AppClock.today(), com.churchgeniuspro.util.AppClock.today().plusDays(20));
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(sc));

            svc.updateDemoSubscription("DEMO-100", "PRO", null);

            // SubscriptionService caches the resolved plan. Without this the admin changes the
            // plan, sees "Pro" on screen, and the app keeps enforcing Free limits for minutes.
            verify(subscriptionService).clearCache();
        }

        @Test
        @DisplayName("an unknown plan is refused before anything is written")
        void badPlanWritesNothing() {
            ServiceClient sc = client("DEMO-100", "FREE", com.churchgeniuspro.util.AppClock.today(), com.churchgeniuspro.util.AppClock.today().plusDays(20));
            when(serviceClientRepo.findByClientId("DEMO-100")).thenReturn(Optional.of(sc));

            assertThrows(IllegalArgumentException.class,
                    () -> svc.updateDemoSubscription("DEMO-100", "NOT_A_PLAN", null));

            verify(serviceClientRepo, never()).save(any());
            assertEquals("FREE", sc.getSubscriptionType());
        }

        @Test
        @DisplayName("a missing demo tenant is a 'not found', not a silent no-op")
        void missingTenant() {
            when(serviceClientRepo.findByClientId("DEMO-404")).thenReturn(Optional.empty());
            assertThrows(IllegalArgumentException.class,
                    () -> svc.updateDemoSubscription("DEMO-404", "PRO", null));
        }
    }

    // ── The guard that keeps this away from real customers ───────────────────

    @Nested
    @DisplayName("Blast radius")
    class BlastRadius {

        @Test
        @DisplayName("a real church's subscription cannot be edited through the demo endpoint")
        void refusesNonDemoTenants() {
            // This screen sits next to a "Clear Selected" button that hard-deletes a tenant's
            // data. A clientId typed or pasted into the wrong field must not be able to cancel
            // a paying customer's subscription.
            for (String realClientId : new String[]{"CHR-000123", "demo-lowercase", "", "USR-9"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> svc.updateDemoSubscription(realClientId, "PRO", com.churchgeniuspro.util.AppClock.today().plusDays(1)),
                        "should have refused " + realClientId);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> svc.updateDemoSubscription(null, "PRO", null));

            verifyNoInteractions(serviceClientRepo);
        }

        @Test
        @DisplayName("the refusal happens before the tenant is even looked up")
        void guardIsFirst() {
            assertThrows(IllegalArgumentException.class,
                    () -> svc.updateDemoSubscription("CHR-000123", "PRO", null));
            verify(serviceClientRepo, never()).findByClientId(any());
        }
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Load-time validation")
    class LoadValidation {

        @Test
        @DisplayName("a demo that expires today or earlier is refused rather than created")
        void refusesUnusableExpiry() {
            givenPlans(plan("FREE", "Free", true, 1));

            // Creating it would "succeed" and hand the admin credentials that cannot sign in.
            IllegalArgumentException today = assertThrows(IllegalArgumentException.class,
                    () -> svc.loadSmallDemo("FREE", com.churchgeniuspro.util.AppClock.today()));
            assertTrue(today.getMessage().toLowerCase().contains("future"), today.getMessage());

            assertThrows(IllegalArgumentException.class,
                    () -> svc.loadSmallDemo("FREE", com.churchgeniuspro.util.AppClock.today().minusDays(1)));
        }

        @Test
        @DisplayName("the plan is validated before any rows are written")
        void planValidatedFirst() {
            givenPlans(plan("FREE", "Free", true, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> svc.loadSmallDemo("NOPE", com.churchgeniuspro.util.AppClock.today().plusDays(30)));
            verifyNoInteractions(serviceClientRepo);
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void givenPlans(SubscriptionPlan... plans) {
        when(planRepo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(plans));
    }

    private static SubscriptionPlan plan(String code, String name, boolean active, int sortOrder) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        p.setPlanName(name);
        p.setActive(active);
        p.setSortOrder(sortOrder);
        p.setMaxPeople(100);
        return p;
    }

    private static ServiceClient client(String clientId, String type, LocalDate start, LocalDate end) {
        ServiceClient sc = new ServiceClient();
        sc.setClientId(clientId);
        sc.setSubscriptionType(type);
        sc.setStartDate(start);
        sc.setEndDate(end);
        sc.setStatus("Active");
        sc.setDeleteFlag(false);
        return sc;
    }

    /**
     * Builds a TestDataService with every collaborator mocked.
     *
     * <p>The constructor takes thirty repositories because the loader touches every module.
     * Listing them by hand here would mean editing this test every time an unrelated seeder
     * is added, so the parameters are filled reflectively and only the three this test
     * actually cares about are pinned to the fields above.
     */
    private TestDataService newService() throws Exception {
        Constructor<?> ctor = TestDataService.class.getDeclaredConstructors()[0];
        Parameter[] params = ctor.getParameters();
        List<Object> args = new ArrayList<>(params.length);
        for (Parameter p : params) {
            Class<?> t = p.getType();
            if (t == SubscriptionPlanRepository.class)   args.add(planRepo);
            else if (t == ServiceClientRepository.class) args.add(serviceClientRepo);
            else if (t == SubscriptionService.class)     args.add(subscriptionService);
            else                                         args.add(mock(t));
        }
        return (TestDataService) ctor.newInstance(args.toArray());
    }
}

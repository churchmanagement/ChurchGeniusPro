package com.churchgeniuspro.subscription;

import com.churchgeniuspro.config.SubscriptionPlanSeeder;
import com.churchgeniuspro.controller.SubscriptionPlanAdminController;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI features are enabled by default ONLY on PRO and TRIAL (2026-11-05, user decision). Every other plan — the
 * seeded FREE / STANDARD and any plan a Service Admin created — carries the AI roadmap keys as {@code false}
 * unless the Service Admin ticks them; the older AI keys keep whatever each plan already has.
 */
@DisplayName("Subscription — AI features default to Pro/Trial only")
class AiPlanDefaultsTest {

    static final ObjectMapper M = new ObjectMapper();

    static SubscriptionPlan plan(String code, String json) { SubscriptionPlan p = new SubscriptionPlan(); p.setPlanCode(code); p.setPlanName(code); p.setFeaturesJson(json); return p; }
    @SuppressWarnings("unchecked") static Map<String, Object> json(SubscriptionPlan p) throws Exception { return M.readValue(p.getFeaturesJson(), LinkedHashMap.class); }

    @Test @DisplayName("seeder: every plan but PRO/TRIAL gains the four roadmap keys as false once; saved values and the older AI keys are untouched; idempotent")
    void seeder() throws Exception {
        SubscriptionPlan free = plan("FREE", "{\"accounting\":false,\"aiSearch\":false,\"aiVoice\":false,\"aiConverse\":false,\"scanCheck\":false}");
        SubscriptionPlan standard = plan("STANDARD", "{\"aiVoice\":false,\"aiConverse\":false,\"scanCheck\":false,\"payroll\":false}");
        SubscriptionPlan pro = plan("PRO", "{}");
        SubscriptionPlan trial = plan("trial", "{}");
        SubscriptionPlan enterprise = plan("ENTERPRISE", "{}");                      // a plan a Service Admin created before the keys existed
        SubscriptionPlan basic = plan("BASIC", "{\"aiWrite\":true,\"groups\":false}"); // an explicit yes already saved
        SubscriptionPlan odd = plan("ODD", "not json");
        List<SubscriptionPlan> all = List.of(free, standard, pro, trial, enterprise, basic, odd);
        SubscriptionPlanRepository repo = mock(SubscriptionPlanRepository.class);
        when(repo.findAll()).thenReturn(all);
        when(repo.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> all.stream().filter(p -> p.getPlanCode().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        int added = SubscriptionPlanSeeder.denyAiFeaturesOutsideProAndTrial(repo);
        assertThat(added).isEqualTo(4 + 4 + 4 + 3);   // FREE, STANDARD, ENTERPRISE all four; BASIC three (aiWrite already saved)
        for (SubscriptionPlan p : List.of(free, standard, enterprise))
            for (String k : SubscriptionFeatureCatalog.AI_PRO_ONLY_FEATURES) assertThat(json(p)).as(p.getPlanCode() + " " + k).containsEntry(k, false);
        assertThat(json(basic)).containsEntry("aiWrite", true).containsEntry("aiActions", false).containsEntry("mcp", false).containsEntry("workflows", false).containsEntry("groups", false);
        assertThat(pro.getFeaturesJson()).isEqualTo("{}");
        assertThat(trial.getFeaturesJson()).isEqualTo("{}");
        assertThat(odd.getFeaturesJson()).isEqualTo("not json");
        // the older AI keys are exactly what each plan had: STANDARD keeps aiSearch (absent = on), FREE keeps aiSearch:false
        assertThat(json(standard)).doesNotContainKey("aiSearch").containsEntry("aiVoice", false).containsEntry("payroll", false);
        assertThat(json(free)).containsEntry("aiSearch", false).containsEntry("accounting", false);
        // second start adds nothing
        assertThat(SubscriptionPlanSeeder.denyAiFeaturesOutsideProAndTrial(repo)).isZero();
        assertThat(SubscriptionFeatureCatalog.AI_PRO_ONLY_FEATURES).containsExactly("aiWrite", "aiActions", "mcp", "workflows");
        assertThat(SubscriptionFeatureCatalog.aiFeatureKeys()).containsExactlyInAnyOrder("aiSearch", "aiVoice", "aiConverse", "aiWrite", "aiActions", "mcp", "workflows", "scanCheck");
    }

    @Test @DisplayName("Service Admin creates a plan: outside PRO/TRIAL every AI-group key is false unless the editor sent true; PRO/TRIAL and non-AI keys untouched")
    void createDefaults() throws Exception {
        Method m = SubscriptionPlanAdminController.class.getDeclaredMethod("aiOffByDefault", String.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> custom = M.readValue((String) m.invoke(null, "ENTERPRISE", "{\"payroll\":false}"), LinkedHashMap.class);
        for (String k : SubscriptionFeatureCatalog.aiFeatureKeys()) assertThat(custom).containsEntry(k, false);
        assertThat(custom).containsEntry("payroll", false).doesNotContainKey("groups");
        @SuppressWarnings("unchecked") Map<String, Object> ticked = M.readValue((String) m.invoke(null, "basic", "{\"aiSearch\":true,\"aiWrite\":true}"), LinkedHashMap.class);
        assertThat(ticked).containsEntry("aiSearch", true).containsEntry("aiWrite", true).containsEntry("aiActions", false).containsEntry("mcp", false).containsEntry("workflows", false).containsEntry("scanCheck", false);
        assertThat((String) m.invoke(null, "PRO", "{}")).isEqualTo("{}");
        assertThat((String) m.invoke(null, "Trial", "{\"x\":false}")).isEqualTo("{\"x\":false}");
        @SuppressWarnings("unchecked") Map<String, Object> empty = M.readValue((String) m.invoke(null, "X", null), LinkedHashMap.class);
        assertThat(empty).containsEntry("aiSearch", false);
        assertThat((String) m.invoke(null, "X", "not json")).isEqualTo("not json");
    }

    @Test @DisplayName("Service Admin editor: a new plan starts with the AI group unticked and ticked AI boxes are saved as explicit true; existing plans show their saved state")
    void editor() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/serviceadminhome.html"));
        assertThat(page).contains("const enabled = p ? !(p.features && p.features[f.key] === false) : f.group !== 'AI';");
        assertThat(page).contains("else if (AI_KEYS.includes(cb.dataset.key)) features[cb.dataset.key] = true;");
        assertThat(page).contains("off by default on every plan except Pro and Trial");
        // the seeder still seeds FREE and STANDARD with every AI roadmap key off, and the controller applies the default on create
        String seeder = Files.readString(Path.of("src/main/java/com/churchgeniuspro/config/SubscriptionPlanSeeder.java"));
        assertThat(seeder).contains("denyAiFeaturesOutsideProAndTrial(repo);");
        assertThat(seeder.split("\\\\\"workflows\\\\\":false").length).isGreaterThanOrEqualTo(3);
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/SubscriptionPlanAdminController.java"));
        assertThat(ctl).contains("p.setFeaturesJson(aiOffByDefault(code, p.getFeaturesJson()));");
        assertThat(ctl.indexOf("aiOffByDefault(code")).isLessThan(ctl.indexOf("// ── Update"));   // create only — update stores exactly what was ticked
    }
}

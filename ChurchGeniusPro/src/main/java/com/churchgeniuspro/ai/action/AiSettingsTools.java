package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.PurposeService;
import com.churchgeniuspro.service.SourceService;
import com.churchgeniuspro.service.TransactionTypeService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2026-11-08 — Account Settings entries from the guided AI Search conversation: a transaction type (payment
 * method), a purpose (expense category), a main fund and a sub-fund. All four are {@link AiTool#guidedOnly()
 * guided-only}; the user's explicit Save runs {@code AiActionService.confirm} (one-time token, permission re-check,
 * re-resolution) and the SAME service method the Account Settings page uses — {@link TransactionTypeService#create},
 * {@link PurposeService#create}, {@link SourceService#createMainSource}, {@link SourceService#createSubSource}
 * (+ {@link SourceService#setTaxDeductible} when the user said a sub-fund is not tax-deductible).
 *
 * <p>Permission = the Account Settings pages' own rule: SuperAdmin / Admin / Accountant ({@code requireAccountantOrAdmin})
 * AND the page permission {@code accounting.settings} (Fund, Purpose and Transaction Type pages). Duplicate names are
 * refused here, before the card and again at Save — with the pages' own scope (per church; a sub-fund per main fund).
 */
public final class AiSettingsTools {

    private AiSettingsTools() {}

    public static final int MAX_NAME = 100;

    /** The Fund / Purpose / Transaction Type pages' rule. */
    public static boolean mayManageSettings(HttpServletRequest req) {
        return RoleGuard.requireAccountantOrAdmin(req) == null
            && RoleGuard.requirePagePermission(req, "accounting.settings") == null;
    }

    /** A usable entry name: trimmed, quotes removed, 1–100 characters with a letter or digit; null otherwise. */
    public static String cleanName(Object raw) {
        if (raw == null) return null;
        String n = String.valueOf(raw).replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        // Only what wraps the name is removed: a sentence-ending full stop / "!" and a MATCHING pair of quotes around the
        // whole name. Everything inside — including a trailing apostrophe ("Mens'", "Saints' Fund") — is kept as typed.
        for (int i = 0; i < 3; i++) {
            String before = n;
            n = n.replaceAll("[.!]+$", "").trim();
            if (n.length() >= 2) {
                char a = n.charAt(0), b = n.charAt(n.length() - 1);
                if ((a == '"' && b == '"') || (a == '\u201c' && b == '\u201d') || (a == '\u2018' && b == '\u2019') || (a == '\'' && b == '\''))
                    n = n.substring(1, n.length() - 1).trim();
            }
            if (n.equals(before)) break;
        }
        if (n.isEmpty() || n.length() > MAX_NAME || !n.matches(".*[\\p{L}\\p{N}].*")) return null;
        return n;
    }

    static Long l(Map<String, Object> m, String k) { Object v = m.get(k); if (v == null) return null; try { return Long.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; } }
    static boolean blank(String cid) { return cid == null || cid.isBlank(); }

    abstract static class Base implements AiTool {
        @Override public boolean guidedOnly() { return true; }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return mayManageSettings(req); }
        public Map<String, String> parameters() { return Map.of("name", "the new entry's name"); }
    }

    /* ─────────────────────────── create_transaction_type ─────────────────────────── */
    @Component
    public static class CreateTransactionType extends Base {
        private final TransactionTypeRepository repo; private final TransactionTypeService service;
        public CreateTransactionType(TransactionTypeRepository repo, TransactionTypeService service) { this.repo = repo; this.service = service; }
        public String name() { return "create_transaction_type"; }
        public String label() { return "Save transaction type"; }
        public String description() { return "Add a transaction type (payment method) under Account Settings. Guided conversation only."; }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            String n = cleanName(raw.get("name"));
            if (n == null || blank(cid)) return Resolution.refuse("The name is missing or not valid (1–" + MAX_NAME + " characters).");
            if (repo.existsByTypeNameIgnoreCaseAndAppClientIdAndDeleteFlagFalse(n, cid)) return Resolution.refuse("A transaction type called “" + n + "” already exists.");
            Map<String, Object> out = new LinkedHashMap<>(); out.put("name", n); return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { return "Add the transaction type “" + r.get("name") + "” under Account Settings."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            TransactionType t = service.create(String.valueOf(r.get("name")), cid);
            return Map.of("id", t.getId(), "link", Map.of("label", "Open Transaction Types", "url", "/transactiontype"));
        }
    }

    /* ─────────────────────────── create_purpose ─────────────────────────── */
    @Component
    public static class CreatePurpose extends Base {
        private final PurposeRepository repo; private final PurposeService service;
        public CreatePurpose(PurposeRepository repo, PurposeService service) { this.repo = repo; this.service = service; }
        public String name() { return "create_purpose"; }
        public String label() { return "Save purpose"; }
        public String description() { return "Add a purpose (expense category) under Account Settings. Guided conversation only."; }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            String n = cleanName(raw.get("name"));
            if (n == null || blank(cid)) return Resolution.refuse("The name is missing or not valid (1–" + MAX_NAME + " characters).");
            if (repo.existsByPurposeNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(n, cid)) return Resolution.refuse("A purpose called “" + n + "” already exists.");
            Map<String, Object> out = new LinkedHashMap<>(); out.put("name", n); return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { return "Add the purpose (expense category) “" + r.get("name") + "” under Account Settings."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            Purpose p = service.create(String.valueOf(r.get("name")), cid);
            return Map.of("id", p.getId(), "link", Map.of("label", "Open Purposes", "url", "/purpose"));
        }
    }

    /* ─────────────────────────── create_fund (main fund) ─────────────────────────── */
    @Component
    public static class CreateFund extends Base {
        private final MainSourceRepository repo; private final SourceService service;
        public CreateFund(MainSourceRepository repo, SourceService service) { this.repo = repo; this.service = service; }
        public String name() { return "create_fund"; }
        public String label() { return "Save fund"; }
        public String description() { return "Add a main fund (main category) under Account Settings → Fund. Guided conversation only."; }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            String n = cleanName(raw.get("name"));
            if (n == null || blank(cid)) return Resolution.refuse("The name is missing or not valid (1–" + MAX_NAME + " characters).");
            if (repo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndAppClientId(n, cid)) return Resolution.refuse("A main fund called “" + n + "” already exists.");
            Map<String, Object> out = new LinkedHashMap<>(); out.put("name", n); return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) { return "Add the main fund “" + r.get("name") + "” under Account Settings → Fund."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            MainSource m = service.createMainSource(String.valueOf(r.get("name")), cid);
            return Map.of("id", m.getId(), "link", Map.of("label", "Open Funds", "url", "/fund"));
        }
    }

    /* ─────────────────────────── create_sub_fund ─────────────────────────── */
    @Component
    public static class CreateSubFund extends Base {
        private final SubSourceRepository repo; private final AiFinanceLookup lookup; private final SourceService service;
        public CreateSubFund(SubSourceRepository repo, AiFinanceLookup lookup, SourceService service) { this.repo = repo; this.lookup = lookup; this.service = service; }
        public String name() { return "create_sub_fund"; }
        public String label() { return "Save sub-fund"; }
        public String description() { return "Add a sub-fund (sub category) under a main fund in Account Settings → Fund. Guided conversation only."; }
        @Override public Map<String, String> parameters() { return Map.of("name", "the new sub-fund's name", "parentId", "main fund id", "taxDeductible", "true / false (default true)"); }
        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            String n = cleanName(raw.get("name"));
            if (n == null || blank(cid)) return Resolution.refuse("The name is missing or not valid (1–" + MAX_NAME + " characters).");
            Long pid = l(raw, "parentId");
            Hit parent = pid == null ? null : lookup.expenseFundById(cid, pid);     // main funds (main_source) of THIS church
            if (parent == null) return Resolution.refuse("That main fund is not one of this church's funds.");
            if (repo.existsBySourceNameIgnoreCaseAndDeleteFlagFalseAndMainSource_Id(n, (int) parent.id()))
                return Resolution.refuse("A sub-fund called “" + n + "” already exists under " + parent.label() + ".");
            boolean tax = !"false".equalsIgnoreCase(String.valueOf(raw.getOrDefault("taxDeductible", "true")).trim());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", n); out.put("parentId", parent.id()); out.put("parentLabel", parent.label()); out.put("taxDeductible", tax);
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Add the sub-fund “" + r.get("name") + "” under " + r.get("parentLabel")
                 + (Boolean.FALSE.equals(r.get("taxDeductible")) ? " (not tax-deductible)" : "") + " in Account Settings → Fund.";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            SubSource s = service.createSubSource(l(r, "parentId").intValue(), String.valueOf(r.get("name")), cid);
            if (Boolean.FALSE.equals(r.get("taxDeductible"))) service.setTaxDeductible(s.getId(), false, cid);
            return Map.of("id", s.getId(), "link", Map.of("label", "Open Funds", "url", "/fund"));
        }
    }
}

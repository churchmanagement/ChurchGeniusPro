package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Promotion;
import com.churchgeniuspro.hibernate.PromotionHistory;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Promotional deals — phase 1: the catalogue the Service Admin maintains
 * (create, edit, deactivate / reactivate, history). All validation lives here.
 *
 * <p>Nothing in this phase gives a deal to a customer or changes a price: billing,
 * subscription and client code do not reference promotions yet. The one calculation
 * that later phases will reuse is {@link #promotionalPrice}.
 *
 * <p>Rules (approved design, 2026-11-14):
 * <ul>
 *   <li>A deal is Monthly or Yearly; the same code may exist once per frequency.</li>
 *   <li>Discount: percentage off, dollar amount off, or a fixed promotional price.</li>
 *   <li>Duration: monthly deals 1, 3 or 6 months or 1–5 years; yearly deals 1–5 years.</li>
 *   <li>Plans: active paid plans only (never Free or Trial) that have a price for the
 *       deal's frequency. The discounted price must be lower than the regular price;
 *       a fixed-price deal covers exactly one plan.</li>
 *   <li>The code never changes. A deal is never deleted, only deactivated.</li>
 *   <li>Every change writes an append-only history row with the Service Admin's name.</li>
 * </ul>
 */
@Service
public class PromotionService {

    static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,29}");
    static final Set<String> TYPES = Set.of(Promotion.PERCENT, Promotion.AMOUNT_OFF, Promotion.FIXED_PRICE);
    static final Set<Integer> MONTH_OPTIONS = Set.of(1, 3, 6);
    static final int MAX_YEARS = 5;
    static final int NOTES_MAX = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A request that cannot be saved because it clashes with what is stored (duplicate, stale copy). */
    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }

    /** The deal form as the page sends it; everything is validated here. */
    public record Form(String code, String name, String billingFrequency, String discountType,
                       String discountValue, Integer durationAmount, String durationUnit,
                       List<String> planCodes, String assignFrom, String assignUntil,
                       String notes, Long version) {}

    private final PromotionRepository deals;
    private final PromotionHistoryRepository history;
    private final SubscriptionPlanRepository plans;

    /**
     * Customer discounts (phase 2), to lock a deal's terms once a customer has it.
     * Setter-injected and null-checked so phase-1 construction sites are unchanged.
     */
    private com.churchgeniuspro.repository.ClientPromotionRepository assignments;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAssignments(com.churchgeniuspro.repository.ClientPromotionRepository r) { this.assignments = r; }

    /** True once any customer has been given this deal (by id, or by code while a trial waits). */
    public boolean inUse(Promotion p) {
        if (assignments == null || p == null || p.getId() == null) return false;
        return assignments.existsByPromotionId(p.getId()) || assignments.existsByPromotionCode(p.getCode());
    }

    /** What may never change once a customer has the deal. */
    private static Map<String, Object> lockedTerms(Map<String, Object> snapshot) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("billingFrequency", "discountType", "discountValue", "durationAmount", "durationUnit", "planCodes")) {
            m.put(k, snapshot.get(k));
        }
        return m;
    }

    public PromotionService(PromotionRepository deals, PromotionHistoryRepository history,
                            SubscriptionPlanRepository plans) {
        this.deals = deals;
        this.history = history;
        this.plans = plans;
    }

    // ── read ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> list() {
        Map<String, SubscriptionPlan> byCode = plansByCode();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Promotion p : deals.findAllByOrderByActiveDescCodeAscBillingFrequencyAsc()) out.add(view(p, byCode));
        return out;
    }

    /** Plans a deal may cover: active, paid, never Free or Trial. */
    public List<Map<String, Object>> eligiblePlans() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SubscriptionPlan p : plans.findAllByOrderBySortOrderAscIdAsc()) {
            if (!isPaidPlan(p)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("planCode", p.getPlanCode());
            m.put("planName", p.getPlanName());
            m.put("monthlyPrice", p.getMonthlyPrice());
            m.put("yearlyPrice", p.getYearlyPrice());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> historyOf(Long id) {
        find(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromotionHistory h : history.findByPromotionIdOrderByOccurredAtDescIdDesc(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("event", h.getEvent());
            m.put("actor", h.getActor());
            m.put("occurredAt", h.getOccurredAt() == null ? null : h.getOccurredAt().toString());
            m.put("notes", h.getNotes());
            m.put("before", parse(h.getBeforeJson()));
            m.put("after", parse(h.getAfterJson()));
            out.add(m);
        }
        return out;
    }

    // ── write ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> create(Form f, String actor) {
        if (f == null) throw new IllegalArgumentException("Enter the deal details.");
        String code = f.code() == null ? "" : f.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Code must be 3 to 30 characters: letters, digits and dashes, starting with a letter or digit.");
        }
        Promotion p = new Promotion();
        p.setCode(code);
        apply(p, f);
        LocalDateTime now = LocalDateTime.now();
        p.setActive(true);
        p.setCreatedAt(now);
        p.setCreatedBy(actor);
        p.setUpdatedAt(now);
        p.setUpdatedBy(actor);
        Promotion saved = deals.save(p);
        record(saved, PromotionHistory.DEAL_CREATED, actor, null, snapshot(saved));
        return view(saved, plansByCode());
    }

    @Transactional
    public Map<String, Object> update(Long id, Form f, String actor) {
        Promotion p = find(id);
        if (f == null) throw new IllegalArgumentException("Enter the deal details.");
        requireCurrent(p, f.version());
        if (f.code() != null && !f.code().isBlank() && !p.getCode().equals(f.code().trim().toUpperCase())) {
            throw new IllegalArgumentException("A deal's code cannot be changed. Deactivate this deal and create a new one instead.");
        }
        Map<String, Object> before = snapshot(p);
        // Validate on a scratch copy first, so a refused edit leaves the stored deal exactly as it was.
        Promotion probe = new Promotion();
        probe.setId(p.getId());
        probe.setCode(p.getCode());
        probe.setActive(p.isActive());
        apply(probe, f);
        if (before.equals(snapshot(probe))) return view(p, plansByCode());   // nothing changed: no save, no history row
        if (!lockedTerms(before).equals(lockedTerms(snapshot(probe))) && inUse(p)) {
            throw new IllegalArgumentException("This deal has already been given to a customer, so its billing frequency, "
                    + "discount, duration and plans can no longer be changed. You can still change its name, notes and "
                    + "assignable dates. To offer different terms, deactivate it and create a new deal.");
        }
        apply(p, f);
        Map<String, Object> after = snapshot(p);
        p.setUpdatedAt(LocalDateTime.now());
        p.setUpdatedBy(actor);
        Promotion saved = deals.save(p);
        record(saved, PromotionHistory.DEAL_EDITED, actor, before, after);
        return view(saved, plansByCode());
    }

    @Transactional
    public Map<String, Object> setActive(Long id, boolean active, Long version, String actor) {
        Promotion p = find(id);
        requireCurrent(p, version);
        if (p.isActive() == active) return view(p, plansByCode());
        Map<String, Object> before = snapshot(p);
        if (active) {
            // Reactivating must not bring back a deal that is no longer valid for today's plans.
            validateAgainstPlans(p.getBillingFrequency(), p.getDiscountType(), p.getDiscountValue(), planList(p));
        }
        p.setActive(active);
        p.setUpdatedAt(LocalDateTime.now());
        p.setUpdatedBy(actor);
        Promotion saved = deals.save(p);
        record(saved, active ? PromotionHistory.DEAL_REACTIVATED : PromotionHistory.DEAL_DEACTIVATED,
               actor, before, snapshot(saved));
        return view(saved, plansByCode());
    }

    // ── the price rule later phases reuse ──────────────────────────────────

    /**
     * The price a customer pays under a discount, to the cent, never below zero.
     * {@code regular} is the customer's regular price for the billing period.
     */
    public static BigDecimal promotionalPrice(String discountType, BigDecimal value, BigDecimal regular) {
        if (regular == null || value == null || discountType == null) return null;
        BigDecimal r = regular.setScale(2, RoundingMode.HALF_UP);
        BigDecimal price = switch (discountType) {
            case Promotion.PERCENT     -> r.subtract(r.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            case Promotion.AMOUNT_OFF  -> r.subtract(value);
            case Promotion.FIXED_PRICE -> value;
            default -> throw new IllegalArgumentException("Unknown discount type.");
        };
        return price.signum() < 0 ? BigDecimal.ZERO.setScale(2) : price.setScale(2, RoundingMode.HALF_UP);
    }

    // ── validation ─────────────────────────────────────────────────────────

    /** Copies the validated form onto the deal (everything except code, active and audit fields). */
    private void apply(Promotion p, Form f) {
        String name = f.name() == null ? "" : f.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Enter a name for the deal.");
        if (name.length() > 100) throw new IllegalArgumentException("The name can be at most 100 characters.");

        String freq = SubscriptionLifecycleService.normaliseFrequency(f.billingFrequency());
        if (freq == null) throw new IllegalArgumentException("Choose the billing frequency: Monthly or Yearly.");

        String type = f.discountType() == null ? "" : f.discountType().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Choose the discount type: percentage off, dollar amount off, or fixed promotional price.");
        }
        BigDecimal value = discountValue(type, f.discountValue());

        String unit = f.durationUnit() == null ? "" : f.durationUnit().trim().toUpperCase();
        Integer amount = f.durationAmount();
        boolean months = Promotion.MONTHS.equals(unit) && amount != null && MONTH_OPTIONS.contains(amount);
        boolean years  = Promotion.YEARS.equals(unit) && amount != null && amount >= 1 && amount <= MAX_YEARS;
        if (SubscriptionLifecycleService.YEARLY.equals(freq)) {
            if (!years) throw new IllegalArgumentException("A yearly deal lasts 1 to 5 years.");
        } else if (!months && !years) {
            throw new IllegalArgumentException("A monthly deal lasts 1, 3 or 6 months, or 1 to 5 years.");
        }

        List<String> codes = cleanPlanCodes(f.planCodes());
        validateAgainstPlans(freq, type, value, codes);

        LocalDate from = date(f.assignFrom(), "Assignable from");
        LocalDate until = date(f.assignUntil(), "Assignable until");
        if (from != null && until != null && until.isBefore(from)) {
            throw new IllegalArgumentException("Assignable until cannot be before Assignable from.");
        }
        String notes = f.notes() == null || f.notes().isBlank() ? null : f.notes().trim();
        if (notes != null && notes.length() > NOTES_MAX) {
            throw new IllegalArgumentException("Notes can be at most " + NOTES_MAX + " characters.");
        }

        // Checked before anything is written to the deal, so a clash is a clear message
        // and never a half-applied change.
        requireUnique(p.getCode(), freq, p.getId());

        p.setName(name);
        p.setBillingFrequency(freq);
        p.setDiscountType(type);
        p.setDiscountValue(value);
        p.setDurationAmount(amount);
        p.setDurationUnit(unit);
        p.setPlanCodes(String.join(",", codes));
        p.setAssignFrom(from);
        p.setAssignUntil(until);
        p.setNotes(notes);
    }

    static BigDecimal discountValue(String type, String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Enter the discount value.");
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim().replace("$", "").replace("%", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The discount value must be a number, for example 20 or 25.00.");
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("The discount value can have at most two decimal places.");
        }
        v = v.setScale(2, RoundingMode.UNNECESSARY);
        if (Promotion.PERCENT.equals(type)) {
            if (v.compareTo(BigDecimal.ONE) < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("A percentage discount must be between 1 and 100.");
            }
        } else if (v.signum() <= 0) {
            throw new IllegalArgumentException("The discount value must be more than $0.");
        } else if (v.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw new IllegalArgumentException("The discount value is too large.");
        }
        return v;
    }

    private static List<String> cleanPlanCodes(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String c : in) if (c != null && !c.isBlank()) out.add(c.trim().toUpperCase());
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Choose at least one plan the deal covers.");
        return new ArrayList<>(out);
    }

    /** Every covered plan must be a paid, active plan whose regular price the deal really lowers. */
    private void validateAgainstPlans(String freq, String type, BigDecimal value, List<String> codes) {
        if (Promotion.FIXED_PRICE.equals(type) && codes.size() != 1) {
            throw new IllegalArgumentException("A fixed promotional price covers exactly one plan. Create one deal per plan.");
        }
        Map<String, SubscriptionPlan> byCode = plansByCode();
        boolean yearly = SubscriptionLifecycleService.YEARLY.equals(freq);
        for (String code : codes) {
            SubscriptionPlan plan = byCode.get(code);
            if (plan == null) throw new IllegalArgumentException("Plan " + code + " does not exist.");
            if (!isPaidPlan(plan)) {
                throw new IllegalArgumentException("The " + plan.getPlanName() + " plan cannot have a promotional deal. "
                        + "Deals are for active paid plans; Free and Trial are never eligible.");
            }
            BigDecimal regular = regularPrice(plan, freq);
            if (regular == null || regular.signum() <= 0) {
                throw new IllegalArgumentException("The " + plan.getPlanName() + " plan has no "
                        + (yearly ? "yearly" : "monthly") + " price, so a " + (yearly ? "yearly" : "monthly")
                        + " deal cannot cover it.");
            }
            if (Promotion.AMOUNT_OFF.equals(type) && value.compareTo(regular) >= 0) {
                throw new IllegalArgumentException("$" + value + " off is not less than the " + plan.getPlanName()
                        + " plan's regular price of $" + regular + ".");
            }
            if (Promotion.FIXED_PRICE.equals(type) && value.compareTo(regular) >= 0) {
                throw new IllegalArgumentException("A promotional price of $" + value + " is not lower than the "
                        + plan.getPlanName() + " plan's regular price of $" + regular + ".");
            }
        }
    }

    private void requireUnique(String code, String freq, Long ownId) {
        deals.findByCodeAndBillingFrequency(code, freq).ifPresent(other -> {
            if (!Objects.equals(other.getId(), ownId)) {
                throw new Conflict("A " + frequencyWord(freq) + " deal with code " + code + " already exists.");
            }
        });
    }

    private static void requireCurrent(Promotion p, Long version) {
        if (version != null && !Objects.equals(version, p.getVersion())) {
            throw new Conflict("This deal was changed by someone else. Reopen it and try again.");
        }
    }

    private Promotion find(Long id) {
        if (id == null) throw new IllegalArgumentException("Deal not found.");
        return deals.findById(id).orElseThrow(() -> new IllegalArgumentException("Deal not found."));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static boolean isPaidPlan(SubscriptionPlan p) {
        if (p == null || !p.isActive() || p.getPlanCode() == null) return false;
        String code = p.getPlanCode().toUpperCase();
        return !"FREE".equals(code) && !TrialPolicy.TRIAL_PLAN_CODE.equals(code);
    }

    private static BigDecimal regularPrice(SubscriptionPlan plan, String freq) {
        BigDecimal price = SubscriptionLifecycleService.listPrice(plan, freq);
        return price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, SubscriptionPlan> plansByCode() {
        Map<String, SubscriptionPlan> m = new LinkedHashMap<>();
        for (SubscriptionPlan p : plans.findAllByOrderBySortOrderAscIdAsc()) {
            if (p.getPlanCode() != null) m.put(p.getPlanCode().toUpperCase(), p);
        }
        return m;
    }

    static List<String> planList(Promotion p) {
        List<String> out = new ArrayList<>();
        if (p.getPlanCodes() != null) {
            for (String c : p.getPlanCodes().split(",")) if (!c.isBlank()) out.add(c.trim());
        }
        return out;
    }

    private static LocalDate date(String v, String label) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException(label + " must be a date (YYYY-MM-DD)."); }
    }

    private static String frequencyWord(String freq) {
        return SubscriptionLifecycleService.YEARLY.equals(freq) ? "yearly" : "monthly";
    }

    /** The stored facts of a deal — what history keeps before and after a change. */
    private static Map<String, Object> snapshot(Promotion p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("billingFrequency", p.getBillingFrequency());
        m.put("discountType", p.getDiscountType());
        m.put("discountValue", p.getDiscountValue() == null ? null : p.getDiscountValue().toPlainString());
        m.put("durationAmount", p.getDurationAmount());
        m.put("durationUnit", p.getDurationUnit());
        m.put("planCodes", planList(p));
        m.put("assignFrom", p.getAssignFrom() == null ? null : p.getAssignFrom().toString());
        m.put("assignUntil", p.getAssignUntil() == null ? null : p.getAssignUntil().toString());
        m.put("notes", p.getNotes());
        m.put("active", p.isActive());
        return m;
    }

    private Map<String, Object> view(Promotion p, Map<String, SubscriptionPlan> byCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("version", p.getVersion());
        m.putAll(snapshot(p));
        m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
        m.put("createdBy", p.getCreatedBy());
        m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        m.put("updatedBy", p.getUpdatedBy());
        // What the deal means at today's plan prices (display only).
        List<Map<String, Object>> preview = new ArrayList<>();
        for (String code : planList(p)) {
            SubscriptionPlan plan = byCode.get(code.toUpperCase());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("planCode", code);
            row.put("planName", plan == null ? code : plan.getPlanName());
            BigDecimal regular = plan == null || !isPaidPlan(plan) ? null : regularPrice(plan, p.getBillingFrequency());
            row.put("regularPrice", regular);
            row.put("promotionalPrice", regular == null ? null
                    : promotionalPrice(p.getDiscountType(), p.getDiscountValue(), regular));
            preview.add(row);
        }
        m.put("preview", preview);
        m.put("inUse", inUse(p));
        return m;
    }

    private void record(Promotion p, String event, String actor, Map<String, Object> before, Map<String, Object> after) {
        PromotionHistory h = new PromotionHistory();
        h.setPromotionId(p.getId());
        h.setPromotionCode(p.getCode());
        h.setEvent(event);
        h.setActor(actor == null || actor.isBlank() ? "ServiceAdmin" : actor);
        h.setOccurredAt(LocalDateTime.now());
        h.setNotes(p.getNotes());
        h.setBeforeJson(json(before));
        h.setAfterJson(json(after));
        history.save(h);
    }

    private static String json(Map<String, Object> m) {
        if (m == null) return null;
        try { return JSON.writeValueAsString(m); }
        catch (Exception e) { throw new IllegalStateException("Could not record the promotion history."); }
    }

    private static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, Map.class); }
        catch (Exception e) { return null; }
    }
}

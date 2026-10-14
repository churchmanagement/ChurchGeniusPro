package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.DonationSettingsRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.util.AppClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Donation Settings — the per-church rules Donation Review posts under.
 *
 * <p>Validation is the whole point of this class and it runs in two places: on
 * {@link #save} (so a bad settings screen is refused), and again on every post via
 * {@link #requireComplete} (so a fund or method soft-deleted <em>after</em> the
 * settings were saved blocks posting with a clear message rather than posting into
 * a deleted fund). Every id is looked up with the church in the WHERE clause; an id
 * belonging to another church is reported as "not found", never used.
 *
 * <p>The fee rate is optional: it is only consulted when Stripe's own fee for a
 * donation is unavailable, and {@link DonationFeeService} refuses to estimate when
 * either part is missing.
 */
@Service
public class DonationSettingsService {

    /** The message the brief asks for, shown by the page and returned by the server. */
    public static final String INCOMPLETE_MESSAGE =
            "Please configure the mandatory fields in Donation Settings before saving donations.";

    private final DonationSettingsRepository settingsRepo;
    private final SubSourceRepository        subSourceRepo;
    private final MainSourceRepository       mainSourceRepo;
    private final PurposeRepository          purposeRepo;
    private final TransactionTypeRepository  transactionTypeRepo;

    public DonationSettingsService(DonationSettingsRepository settingsRepo,
                                   SubSourceRepository subSourceRepo,
                                   MainSourceRepository mainSourceRepo,
                                   PurposeRepository purposeRepo,
                                   TransactionTypeRepository transactionTypeRepo) {
        this.settingsRepo        = settingsRepo;
        this.subSourceRepo       = subSourceRepo;
        this.mainSourceRepo      = mainSourceRepo;
        this.purposeRepo         = purposeRepo;
        this.transactionTypeRepo = transactionTypeRepo;
    }

    /** Thrown when the settings cannot be used to post. Carries the missing fields for the UI. */
    public static class IncompleteSettingsException extends RuntimeException {
        private final List<String> missing;
        public IncompleteSettingsException(List<String> missing) {
            super(INCOMPLETE_MESSAGE);
            this.missing = List.copyOf(missing);
        }
        public List<String> getMissing() { return missing; }
    }

    /** Thrown when a submitted value is invalid (bad id, wrong church, negative rate…). */
    public static class InvalidSettingsException extends RuntimeException {
        public InvalidSettingsException(String message) { super(message); }
    }

    public Optional<DonationSettings> find(String clientId) {
        return settingsRepo.findByClientId(clientId);
    }

    /**
     * The settings, verified usable for posting right now. Every referenced fund,
     * method and purpose must still exist for this church.
     *
     * @throws IncompleteSettingsException naming what is missing or no longer valid
     */
    public DonationSettings requireComplete(String clientId) {
        DonationSettings s = settingsRepo.findByClientId(clientId).orElse(null);
        List<String> missing = missingFields(s, clientId);
        if (!missing.isEmpty()) throw new IncompleteSettingsException(missing);
        return s;
    }

    /** Field names that are absent or point at something this church no longer has. Empty = usable. */
    public List<String> missingFields(DonationSettings s, String clientId) {
        List<String> missing = new ArrayList<>();
        if (s == null) {
            missing.add("incomeSubSourceId"); missing.add("incomeTransactionTypeId");
            missing.add("expensePurposeId"); missing.add("expenseMainSourceId"); missing.add("expenseTransactionTypeId");
            return missing;
        }
        if (!DonationSettings.MODE_GROSS_WITH_FEE_EXPENSE.equals(s.getAccountingMode())) missing.add("accountingMode");
        if (s.getIncomeSubSourceId() == null
                || subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(s.getIncomeSubSourceId(), clientId).isEmpty())
            missing.add("incomeSubSourceId");
        if (s.getIncomeTransactionTypeId() == null
                || transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(s.getIncomeTransactionTypeId(), clientId).isEmpty())
            missing.add("incomeTransactionTypeId");
        // Expense side — mandatory in the only mode this phase supports.
        if (s.getExpensePurposeId() == null
                || purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(s.getExpensePurposeId(), clientId).isEmpty())
            missing.add("expensePurposeId");
        if (s.getExpenseMainSourceId() == null
                || mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(s.getExpenseMainSourceId(), clientId).isEmpty())
            missing.add("expenseMainSourceId");
        if (s.getExpenseTransactionTypeId() == null
                || transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(s.getExpenseTransactionTypeId(), clientId).isEmpty())
            missing.add("expenseTransactionTypeId");
        if (!DonationSettings.DATE_DONATION.equals(s.getDateMode()) && !DonationSettings.DATE_TODAY.equals(s.getDateMode()))
            missing.add("dateMode");
        return missing;
    }

    /**
     * Creates or updates the church's settings from the screen's values. Rejects any
     * id that is not this church's, any unknown mode or date rule, and a negative fee
     * rate. Note templates fall back to the defaults when blank.
     */
    @Transactional
    public DonationSettings save(String clientId, Map<String, Object> body, String username) {
        if (body == null) throw new InvalidSettingsException("Nothing to save.");
        DonationSettings s = settingsRepo.findByClientId(clientId).orElseGet(() -> {
            DonationSettings n = new DonationSettings();
            n.setClientId(clientId);
            return n;
        });

        String mode = str(body.get("accountingMode"));
        if (mode != null && !DonationSettings.MODE_GROSS_WITH_FEE_EXPENSE.equals(mode)) {
            throw new InvalidSettingsException("Only the 'full donation as Income + Stripe fee as Expense' mode is available.");
        }
        s.setAccountingMode(DonationSettings.MODE_GROSS_WITH_FEE_EXPENSE);

        s.setIncomeSubSourceId(requireTenantId(body, "incomeSubSourceId", "Income fund",
                id -> subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).map(SubSource::getId)));
        s.setIncomeTransactionTypeId(requireTenantId(body, "incomeTransactionTypeId", "Income method",
                id -> transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).map(TransactionType::getId)));
        s.setExpensePurposeId(requireTenantId(body, "expensePurposeId", "Expense type",
                id -> purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).map(Purpose::getId)));
        s.setExpenseMainSourceId(requireTenantId(body, "expenseMainSourceId", "Expense fund",
                id -> mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).map(MainSource::getId)));
        s.setExpenseTransactionTypeId(requireTenantId(body, "expenseTransactionTypeId", "Expense method",
                id -> transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).map(TransactionType::getId)));

        String dateMode = str(body.get("dateMode"));
        if (dateMode == null) dateMode = DonationSettings.DATE_DONATION;
        if (!DonationSettings.DATE_DONATION.equals(dateMode) && !DonationSettings.DATE_TODAY.equals(dateMode)) {
            throw new InvalidSettingsException("Transaction date must be the donation date or today's date.");
        }
        s.setDateMode(dateMode);

        String incNote = str(body.get("incomeNoteTemplate"));
        String expNote = str(body.get("expenseNoteTemplate"));
        s.setIncomeNoteTemplate(incNote != null ? clip(incNote, 2000) : DonationSettings.DEFAULT_INCOME_NOTE);
        s.setExpenseNoteTemplate(expNote != null ? clip(expNote, 2000) : DonationSettings.DEFAULT_EXPENSE_NOTE);

        s.setFeePercent(optionalRate(body.get("feePercent"), "Stripe fee %", 3, new BigDecimal("100")));
        s.setFeeFixed(optionalRate(body.get("feeFixed"), "Stripe fixed fee", 2, new BigDecimal("100")));
        if ((s.getFeePercent() == null) != (s.getFeeFixed() == null)) {
            throw new InvalidSettingsException("Enter both the Stripe fee % and the fixed fee, or leave both blank.");
        }

        s.setUpdatedBy(username != null && username.length() > 320 ? username.substring(0, 320) : username);
        s.setUpdatedDate(LocalDateTime.now(AppClock.ZONE));
        return settingsRepo.save(s);
    }

    /** The settings as the page shows them, plus whether they are usable and what is missing. */
    public Map<String, Object> describe(String clientId) {
        DonationSettings s = settingsRepo.findByClientId(clientId).orElse(null);
        List<String> missing = missingFields(s, clientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", s != null);
        m.put("complete", missing.isEmpty());
        m.put("missing", missing);
        m.put("incompleteMessage", INCOMPLETE_MESSAGE);
        m.put("accountingMode", s != null ? s.getAccountingMode() : DonationSettings.MODE_GROSS_WITH_FEE_EXPENSE);
        m.put("incomeSubSourceId",        s != null ? s.getIncomeSubSourceId() : null);
        m.put("incomeTransactionTypeId",  s != null ? s.getIncomeTransactionTypeId() : null);
        m.put("incomeNoteTemplate",       s != null && s.getIncomeNoteTemplate() != null ? s.getIncomeNoteTemplate() : DonationSettings.DEFAULT_INCOME_NOTE);
        m.put("expensePurposeId",         s != null ? s.getExpensePurposeId() : null);
        m.put("expenseMainSourceId",      s != null ? s.getExpenseMainSourceId() : null);
        m.put("expenseTransactionTypeId", s != null ? s.getExpenseTransactionTypeId() : null);
        m.put("expenseNoteTemplate",      s != null && s.getExpenseNoteTemplate() != null ? s.getExpenseNoteTemplate() : DonationSettings.DEFAULT_EXPENSE_NOTE);
        m.put("dateMode",                 s != null ? s.getDateMode() : DonationSettings.DATE_DONATION);
        m.put("feePercent",               s != null ? s.getFeePercent() : null);
        m.put("feeFixed",                 s != null ? s.getFeeFixed() : null);
        m.put("hasFeeRate",               s != null && s.hasFeeRate());
        m.put("updatedBy",                s != null ? s.getUpdatedBy() : null);
        m.put("updatedDate",              s != null && s.getUpdatedDate() != null ? s.getUpdatedDate().toString() : null);
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private interface TenantLookup { Optional<Integer> find(Integer id); }

    private static Integer requireTenantId(Map<String, Object> body, String key, String label, TenantLookup lookup) {
        Integer id = toInt(body.get(key));
        if (id == null) throw new InvalidSettingsException(label + " is required.");
        return lookup.find(id).orElseThrow(() ->
                new InvalidSettingsException(label + " was not found for this church."));
    }

    private static BigDecimal optionalRate(Object raw, String label, int scale, BigDecimal max) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        BigDecimal v;
        try { v = new BigDecimal(s); } catch (NumberFormatException e) {
            throw new InvalidSettingsException(label + " must be a number.");
        }
        if (v.signum() < 0) throw new InvalidSettingsException(label + " cannot be negative.");
        if (v.compareTo(max) > 0) throw new InvalidSettingsException(label + " is too large.");
        return v.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    static Integer toInt(Object o) {
        if (o instanceof Number n) {
            long v = n.longValue();
            return (v > 0 && v <= Integer.MAX_VALUE && v == n.doubleValue()) ? (int) v : null;
        }
        if (o instanceof String s && s.trim().matches("\\d{1,9}")) {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        }
        return null;
    }

    static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}

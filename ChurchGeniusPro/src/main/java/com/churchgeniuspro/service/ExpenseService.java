package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.repository.OffsetWindow;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service layer for {@link Expense} CRUD operations and related lookups.
 */
@Service
public class ExpenseService {

    private final ExpenseRepository         expenseRepo;
    private final PurposeRepository         purposeRepo;
    private final MainSourceRepository      mainSourceRepo;
    private final TransactionTypeRepository transactionTypeRepo;

    public ExpenseService(ExpenseRepository         expenseRepo,
                          PurposeRepository         purposeRepo,
                          MainSourceRepository      mainSourceRepo,
                          TransactionTypeRepository transactionTypeRepo) {
        this.expenseRepo         = expenseRepo;
        this.purposeRepo         = purposeRepo;
        this.mainSourceRepo      = mainSourceRepo;
        this.transactionTypeRepo = transactionTypeRepo;
    }

    // ── Purposes (Expense dropdown) ───────────────────────────────────────

    /** Returns all non-deleted purposes for the Expense dropdown, filtered by appUserId. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllPurposes(String appClientId) {
        return purposeRepo.findActiveByAppUser(appClientId)
                .stream()
                .map(this::purposeToMap)
                .collect(Collectors.toList());
    }

    // ── Main Sources (Fund dropdown) ──────────────────────────────────────

    /** Returns all non-deleted main sources (funds) for the Fund dropdown, filtered by appUserId. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllFunds(String appClientId) {
        return mainSourceRepo.findActiveByAppUser(appClientId)
                .stream()
                .map(this::mainSourceToMap)
                .collect(Collectors.toList());
    }

    // ── Last Expense for Purpose ──────────────────────────────────────────

    /**
     * Returns the most recent expense record for the given purpose, or an
     * empty map if none exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLastExpenseForPurpose(Integer purposeId, String appClientId) {
        List<Expense> records = expenseRepo.findByPurposeActiveByAppUser(purposeId, appClientId);
        if (records.isEmpty()) return Map.of();
        return expenseToMap(records.get(0));
    }

    // ── Expense – List ────────────────────────────────────────────────────

    /** Largest page a caller may request from {@link #getRecentExpenses}. */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * Returns one page of active expense records, most-recent first.
     * {@code page} is zero-based; {@code size} is clamped to 1..{@link #MAX_PAGE_SIZE}.
     * The repository is asked for one extra row so {@code hasMore} needs no count query.
     */
    @Transactional(readOnly = true)
    public PageSlice<Map<String, Object>> getRecentExpenses(String appClientId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable window = new OffsetWindow((long) safePage * safeSize, safeSize + 1);
        List<Map<String, Object>> rows = expenseRepo.findActivePageByAppUser(appClientId, window)
                .stream()
                .map(this::expenseToMap)
                .collect(Collectors.toList());
        return PageSlice.of(rows, safeSize);
    }

    // ── Quick Add – List ──────────────────────────────────────────────────

    /**
     * Returns the latest 10 expense records marked as quick-add templates,
     * for the Accountant dashboard Quick Add panel.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getQuickAddExpenses(String appClientId) {
        return expenseRepo.findQuickAddByAppUser(appClientId)
                .stream()
                .limit(10)
                .map(this::expenseToMap)
                .collect(Collectors.toList());
    }

    // ── Expense – Create ──────────────────────────────────────────────────

    @Transactional
    public Expense createExpense(Integer    purposeId,
                                 Integer    mainSourceId,
                                 LocalDate  expenseDate,
                                 Integer    transactionTypeId,
                                 String     refNo,
                                 BigDecimal amount,
                                 String     note,
                                 boolean    quickAdd,
                                 String     appClientId,
                                 String     createdBy) {
        return createExpense(purposeId, mainSourceId, expenseDate, transactionTypeId, refNo, amount, note,
                quickAdd, appClientId, createdBy, null, false);
    }

    /**
     * Same as the shorter overload above, but for an import pipeline (Bank Import,
     * Plaid) that can identify the same source transaction across re-imports.
     * Financial audit H8 — see {@code IncomeService.createIncome} for the full
     * explanation of {@code importRef} / {@code force}; the rules are identical.
     */
    @Transactional
    public Expense createExpense(Integer    purposeId,
                                 Integer    mainSourceId,
                                 LocalDate  expenseDate,
                                 Integer    transactionTypeId,
                                 String     refNo,
                                 BigDecimal amount,
                                 String     note,
                                 boolean    quickAdd,
                                 String     appClientId,
                                 String     createdBy,
                                 String     importRef,
                                 boolean    force) {
        String ref = (importRef != null && !importRef.isBlank()) ? importRef.trim() : null;
        if (ref != null) {
            checkImportDuplicate(appClientId, ref, expenseDate, amount, force);
        }

        Purpose         purpose         = findPurposeOrThrow(purposeId, appClientId);
        MainSource      mainSource      = findMainSourceOrThrow(mainSourceId, appClientId);
        TransactionType transactionType = findTransactionTypeOrThrow(transactionTypeId, appClientId);

        Expense expense = new Expense();
        expense.setPurpose(purpose);
        expense.setMainSource(mainSource);
        expense.setExpenseDate(expenseDate);
        expense.setTransactionType(transactionType);
        expense.setRefNo(refNo != null ? refNo.trim() : null);
        expense.setAmount(amount);
        expense.setNote(note != null ? note.trim() : null);
        expense.setQuickAdd(quickAdd);
        expense.setImportRef(ref);
        expense.setAppClientId(appClientId);
        expense.setCreatedBy(createdBy);
        expense.setUpdatedBy(createdBy);
        expense.setUpdatedDate(new Date());
        try {
            return expenseRepo.save(expense);
        } catch (DataIntegrityViolationException race) {
            // The per-tenant unique index caught a concurrent import of the same
            // statement line between our check above and this insert.
            throw duplicateFromRace("expense", appClientId, ref, expenseDate, amount);
        }
    }

    /** Financial audit H8 — see {@code IncomeService.createIncome}. */
    private void checkImportDuplicate(String appClientId, String importRef, LocalDate date, BigDecimal amount,
                                      boolean force) {
        Optional<Expense> hard = expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(appClientId, importRef);
        if (hard.isPresent()) {
            Expense existing = hard.get();
            throw new DuplicateImportException("This transaction was already imported.", "expense",
                    existing.getId(), str(existing.getExpenseDate()), existing.getAmount(), existing.getRefNo(), true);
        }
        if (!force) {
            List<Expense> possible = expenseRepo.findByAppClientIdAndExpenseDateAndAmountAndDeleteFlagFalse(
                    appClientId, date, amount);
            if (!possible.isEmpty()) {
                Expense existing = possible.get(0);
                throw new DuplicateImportException("A possible duplicate already exists for this date and amount.",
                        "expense", existing.getId(), str(existing.getExpenseDate()), existing.getAmount(),
                        existing.getRefNo(), false);
            }
        }
    }

    /** Financial audit H8 — see {@code IncomeService.createIncome}. */
    private DuplicateImportException duplicateFromRace(String type, String appClientId, String importRef,
                                                        LocalDate fallbackDate, BigDecimal fallbackAmount) {
        return expenseRepo.findFirstByAppClientIdAndImportRefAndDeleteFlagFalse(appClientId, importRef)
                .map(existing -> new DuplicateImportException("This transaction was already imported.", type,
                        existing.getId(), str(existing.getExpenseDate()), existing.getAmount(), existing.getRefNo(), true))
                .orElseGet(() -> new DuplicateImportException("This transaction was already imported.", type,
                        null, str(fallbackDate), fallbackAmount, null, true));
    }

    private static String str(LocalDate d) { return d == null ? null : d.toString(); }

    // ── Expense – Update ──────────────────────────────────────────────────

    @Transactional
    public Expense updateExpense(Integer    id,
                                 Integer    purposeId,
                                 Integer    mainSourceId,
                                 LocalDate  expenseDate,
                                 Integer    transactionTypeId,
                                 String     refNo,
                                 BigDecimal amount,
                                 String     note,
                                 boolean    quickAdd,
                                 String     appClientId,
                                 String     updatedBy) {
        Expense expense = findExpenseOrThrow(id, appClientId);
        expense.setPurpose(findPurposeOrThrow(purposeId, appClientId));
        expense.setMainSource(findMainSourceOrThrow(mainSourceId, appClientId));
        expense.setExpenseDate(expenseDate);
        expense.setTransactionType(findTransactionTypeOrThrow(transactionTypeId, appClientId));
        expense.setRefNo(refNo != null ? refNo.trim() : null);
        expense.setAmount(amount);
        expense.setNote(note != null ? note.trim() : null);
        expense.setQuickAdd(quickAdd);
        expense.setUpdatedBy(updatedBy);
        expense.setUpdatedDate(new Date());
        return expenseRepo.save(expense);
    }

    // ── Expense – Unstar (remove from Recurring panel) ───────────────────

    @Transactional
    public void unstarExpense(Integer id, String appClientId) {
        Expense expense = findExpenseOrThrow(id, appClientId);
        expense.setQuickAdd(false);
        expenseRepo.save(expense);
    }

    // ── Expense – Soft-Delete ─────────────────────────────────────────────

    @Transactional
    public void deleteExpense(Integer id, String appClientId) {
        Expense expense = findExpenseOrThrow(id, appClientId);
        expense.setDeleteFlag(true);
        expenseRepo.save(expense);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> purposeToMap(Purpose p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",          p.getId());
        map.put("purposeName", p.getPurposeName());
        return map;
    }

    private Map<String, Object> mainSourceToMap(MainSource ms) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",         ms.getId());
        map.put("sourceName", ms.getSourceName());
        return map;
    }

    private Map<String, Object> expenseToMap(Expense e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",             e.getId());
        map.put("purposeId",      e.getPurpose().getId());
        map.put("purposeName",    e.getPurpose().getPurposeName());
        map.put("mainSourceId",   e.getMainSource().getId());
        map.put("mainSourceName", e.getMainSource().getSourceName());
        map.put("expenseDate",        e.getExpenseDate() != null ? e.getExpenseDate().toString() : null);
        TransactionType tt = e.getTransactionType();
        map.put("transactionTypeId",  tt != null ? tt.getId()       : null);
        map.put("method",             tt != null ? tt.getTypeName() : "");
        map.put("refNo",              e.getRefNo());
        map.put("amount",         e.getAmount());
        map.put("note",           e.getNote());
        map.put("quickAdd",       e.isQuickAdd());
        map.put("createdBy",      e.getCreatedBy());
        map.put("updatedBy",      e.getUpdatedBy());
        map.put("updatedDate",    e.getUpdatedDate() != null ? e.getUpdatedDate().toString() : null);
        return map;
    }

    // All lookups are tenant-scoped: an id that exists in another church yields the
    // same "not found" as an unknown id, so the API cannot be used as an oracle.

    private Purpose findPurposeOrThrow(Integer id, String appClientId) {
        return purposeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Purpose not found: " + id));
    }

    private MainSource findMainSourceOrThrow(Integer id, String appClientId) {
        return mainSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Fund not found: " + id));
    }

    private Expense findExpenseOrThrow(Integer id, String appClientId) {
        return expenseRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Expense record not found: " + id));
    }

    private TransactionType findTransactionTypeOrThrow(Integer id, String appClientId) {
        if (id == null) throw new IllegalArgumentException("Transaction type is required.");
        return transactionTypeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction type not found: " + id));
    }
}

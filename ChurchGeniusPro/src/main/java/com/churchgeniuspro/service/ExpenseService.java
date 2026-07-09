package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Returns all active expense records ordered most-recent first, filtered by appUserId. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentExpenses(String appClientId) {
        return expenseRepo.findAllActiveByAppUser(appClientId)
                .stream()
                .map(this::expenseToMap)
                .collect(Collectors.toList());
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
        Purpose         purpose         = findPurposeOrThrow(purposeId);
        MainSource      mainSource      = findMainSourceOrThrow(mainSourceId);
        TransactionType transactionType = findTransactionTypeOrThrow(transactionTypeId);

        Expense expense = new Expense();
        expense.setPurpose(purpose);
        expense.setMainSource(mainSource);
        expense.setExpenseDate(expenseDate);
        expense.setTransactionType(transactionType);
        expense.setRefNo(refNo != null ? refNo.trim() : null);
        expense.setAmount(amount);
        expense.setNote(note != null ? note.trim() : null);
        expense.setQuickAdd(quickAdd);
        expense.setAppClientId(appClientId);
        expense.setCreatedBy(createdBy);
        expense.setUpdatedBy(createdBy);
        expense.setUpdatedDate(new Date());
        return expenseRepo.save(expense);
    }

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
                                 String     updatedBy) {
        Expense expense = findExpenseOrThrow(id);
        expense.setPurpose(findPurposeOrThrow(purposeId));
        expense.setMainSource(findMainSourceOrThrow(mainSourceId));
        expense.setExpenseDate(expenseDate);
        expense.setTransactionType(findTransactionTypeOrThrow(transactionTypeId));
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
    public void unstarExpense(Integer id) {
        Expense expense = findExpenseOrThrow(id);
        expense.setQuickAdd(false);
        expenseRepo.save(expense);
    }

    // ── Expense – Soft-Delete ─────────────────────────────────────────────

    @Transactional
    public void deleteExpense(Integer id) {
        Expense expense = findExpenseOrThrow(id);
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

    private Purpose findPurposeOrThrow(Integer id) {
        return purposeRepo.findById(id)
                .filter(p -> !p.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Purpose not found: " + id));
    }

    private MainSource findMainSourceOrThrow(Integer id) {
        return mainSourceRepo.findById(id)
                .filter(ms -> !ms.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Fund not found: " + id));
    }

    private Expense findExpenseOrThrow(Integer id) {
        return expenseRepo.findById(id)
                .filter(e -> !e.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Expense record not found: " + id));
    }

    private TransactionType findTransactionTypeOrThrow(Integer id) {
        if (id == null) throw new IllegalArgumentException("Transaction type is required.");
        return transactionTypeRepo.findById(id)
                .filter(tt -> !tt.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Transaction type not found: " + id));
    }
}

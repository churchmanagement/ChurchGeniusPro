package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
 * Service layer for {@link Income} CRUD operations and related lookups.
 */
@Service
public class IncomeService {

    private final IncomeRepository          incomeRepo;
    private final FamilyMemberRepository    memberRepo;
    private final SubSourceRepository       subSourceRepo;
    private final TransactionTypeRepository transactionTypeRepo;

    /**
     * Pledge module hook. @Lazy avoids any chance of a startup-time
     * circular reference (PledgeController constructs cleanly without
     * IncomeService, but @Lazy keeps the contract resilient if that
     * changes later). The auto-allocate calls are best-effort — failures
     * are swallowed so an unrelated pledge problem can't break Income.
     */
    private final PledgeController pledgeController;

    @Autowired
    public IncomeService(IncomeRepository          incomeRepo,
                         FamilyMemberRepository    memberRepo,
                         SubSourceRepository       subSourceRepo,
                         TransactionTypeRepository transactionTypeRepo,
                         @Lazy PledgeController    pledgeController) {
        this.incomeRepo          = incomeRepo;
        this.memberRepo          = memberRepo;
        this.subSourceRepo       = subSourceRepo;
        this.transactionTypeRepo = transactionTypeRepo;
        this.pledgeController    = pledgeController;
    }

    // ── Contributors ──────────────────────────────────────────────────────

    /**
     * Returns all non-deleted family members who have
     * {@code includeContributions = true}, for the Contributor dropdown.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getContributors(String appClientId) {
        return memberRepo.findContributorsByAppUser(appClientId)
                .stream()
                .map(this::memberToMap)
                .collect(Collectors.toList());
    }

    // ── Fund Sub-Sources ──────────────────────────────────────────────────

    /**
     * Returns all active sub-sources (across all main categories), for the
     * Fund dropdown.  Each entry includes the parent main-source name so the
     * frontend can display "Main / Sub" grouping if desired.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllSubSources(String appClientId) {
        return subSourceRepo.findAllActiveByAppUser(appClientId)
                .stream()
                .map(this::subSourceToMap)
                .collect(Collectors.toList());
    }

    // ── Last Income for Member ─────────────────────────────────────────────

    /**
     * Returns the most recent income record for the given member, or an empty
     * map if none exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLastIncomeForMember(Integer memberId, String appClientId) {
        List<Income> records = incomeRepo.findByMemberActiveByAppUser(memberId, appClientId);
        if (records.isEmpty()) return Map.of();
        return incomeToMap(records.get(0));
    }

    // ── Income – List ─────────────────────────────────────────────────────

    /**
     * Returns all active income records ordered most-recent first.
     * The caller slices to {@code limit} rows on the frontend.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRecentIncomes(String appClientId) {
        return incomeRepo.findAllActiveByAppUser(appClientId)
                .stream()
                .map(this::incomeToMap)
                .collect(Collectors.toList());
    }

    // ── Quick Add – List ──────────────────────────────────────────────────

    /**
     * Returns the latest 10 income records marked as quick-add templates,
     * for the Accountant dashboard Quick Add panel.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getQuickAddIncomes(String appClientId) {
        return incomeRepo.findQuickAddByAppUser(appClientId)
                .stream()
                .limit(10)
                .map(this::incomeToMap)
                .collect(Collectors.toList());
    }

    // ── Income – Create ───────────────────────────────────────────────────

    @Transactional
    public Income createIncome(Integer    memberId,
                               Integer    subSourceId,
                               LocalDate  incomeDate,
                               Integer    transactionTypeId,
                               String     refNo,
                               BigDecimal amount,
                               String     note,
                               String     guestName,
                               boolean    quickAdd,
                               String     appClientId,
                               String     createdBy) {
        FamilyMember    member          = (memberId != null) ? findMemberOrThrow(memberId) : null;
        SubSource       subSource       = findSubSourceOrThrow(subSourceId);
        TransactionType transactionType = findTransactionTypeOrThrow(transactionTypeId);

        Income income = new Income();
        income.setMember(member);
        income.setSubSource(subSource);
        income.setIncomeDate(incomeDate);
        income.setTransactionType(transactionType);
        income.setRefNo(refNo != null ? refNo.trim() : null);
        income.setAmount(amount);
        income.setNote(note != null ? note.trim() : null);
        income.setGuestName(member == null && guestName != null && !guestName.isBlank()
                ? guestName.trim() : null);
        income.setQuickAdd(quickAdd);
        income.setAppClientId(appClientId);
        income.setCreatedBy(createdBy);
        income.setUpdatedBy(createdBy);
        income.setUpdatedDate(new Date());
        Income saved = incomeRepo.save(income);
        // Auto-credit pledge balance when the contributor matches a member
        // with an active pledge against this fund. Silent on failure.
        try {
            if (member != null && saved.getSubSource() != null) {
                pledgeController.applyIncomeToPledge(appClientId,
                        saved.getSubSource().getId(), member.getId(), saved.getAmount());
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
        return saved;
    }

    // ── Income – Update ───────────────────────────────────────────────────

    @Transactional
    public Income updateIncome(Integer    id,
                               Integer    memberId,
                               Integer    subSourceId,
                               LocalDate  incomeDate,
                               Integer    transactionTypeId,
                               String     refNo,
                               BigDecimal amount,
                               String     note,
                               String     guestName,
                               boolean    quickAdd,
                               String     updatedBy) {
        Income income = findIncomeOrThrow(id);
        // Snapshot the original allocation so we can reverse it on the
        // pledge side before applying the new one. Pledge auto-allocate is
        // additive — without these snapshots an edit would double-count.
        Integer    origMemberId    = income.getMember()    != null ? income.getMember().getId()    : null;
        Integer    origSubSourceId = income.getSubSource() != null ? income.getSubSource().getId() : null;
        BigDecimal origAmount      = income.getAmount();
        String     origClientId    = income.getAppClientId();

        FamilyMember member = memberId != null ? findMemberOrThrow(memberId) : null;
        income.setMember(member);
        income.setSubSource(findSubSourceOrThrow(subSourceId));
        income.setIncomeDate(incomeDate);
        income.setTransactionType(findTransactionTypeOrThrow(transactionTypeId));
        income.setRefNo(refNo != null ? refNo.trim() : null);
        income.setAmount(amount);
        income.setNote(note != null ? note.trim() : null);
        income.setGuestName(member == null && guestName != null && !guestName.isBlank()
                ? guestName.trim() : null);
        income.setQuickAdd(quickAdd);
        income.setUpdatedBy(updatedBy);
        income.setUpdatedDate(new Date());
        Income saved = incomeRepo.save(income);
        // Pledge re-allocation: take back the old amount (subtract), apply
        // the new amount (add). Same-row edits where neither member nor
        // fund changed amount-only end up as a single delta.
        try {
            if (origMemberId != null && origSubSourceId != null && origAmount != null) {
                pledgeController.adjustPledgeByDelta(origClientId, origSubSourceId,
                        origMemberId, origAmount.negate());
            }
            if (saved.getMember() != null && saved.getSubSource() != null) {
                pledgeController.applyIncomeToPledge(saved.getAppClientId(),
                        saved.getSubSource().getId(), saved.getMember().getId(), saved.getAmount());
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
        return saved;
    }

    // ── Income – Unstar (remove from Recurring panel) ────────────────────

    @Transactional
    public void unstarIncome(Integer id) {
        Income income = findIncomeOrThrow(id);
        income.setQuickAdd(false);
        incomeRepo.save(income);
    }

    // ── Income – Soft-Delete ──────────────────────────────────────────────

    @Transactional
    public void deleteIncome(Integer id) {
        Income income = findIncomeOrThrow(id);
        // Capture original pledge-allocation inputs BEFORE marking deleted, so
        // we can credit back the pledge balance once the row is gone.
        String      origClientId    = income.getAppClientId();
        Integer     origSubSourceId = income.getSubSource() != null ? income.getSubSource().getId() : null;
        Integer     origMemberId    = income.getMember()    != null ? income.getMember().getId()    : null;
        java.math.BigDecimal origAmount = income.getAmount();

        income.setDeleteFlag(true);
        incomeRepo.save(income);

        // Best-effort: reverse the pledge credit. Pledge module is optional —
        // failures here must not break income deletion.
        try {
            if (origMemberId != null && origSubSourceId != null && origAmount != null) {
                pledgeController.adjustPledgeByDelta(origClientId,
                        origSubSourceId, origMemberId, origAmount.negate());
            }
        } catch (Exception ignored) { /* pledge module is optional */ }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> memberToMap(FamilyMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",        m.getId());
        map.put("firstName", m.getFirstName());
        map.put("lastName",  m.getLastName());
        map.put("nickname",  m.getOtherName());
        String fullName = com.churchgeniuspro.util.MemberNameUtil.display(
                m.getFirstName(), m.getLastName(), m.getOtherName());
        map.put("fullName", fullName);
        map.put("displayName", fullName);
        return map;
    }

    private Map<String, Object> subSourceToMap(SubSource ss) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",           ss.getId());
        map.put("sourceName",   ss.getSourceName());
        map.put("mainSourceId", ss.getMainSource().getId());
        map.put("mainSourceName", ss.getMainSource().getSourceName());
        return map;
    }

    private Map<String, Object> incomeToMap(Income i) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",         i.getId());
        FamilyMember m = i.getMember();
        map.put("memberId",   m != null ? m.getId() : null);
        String fullName = m != null
                ? com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName())
                : "";
        map.put("memberName", fullName);
        map.put("guestName",  i.getGuestName());
        map.put("subSourceId",    i.getSubSource().getId());
        map.put("subSourceName",  i.getSubSource().getSourceName());
        map.put("mainSourceName", i.getSubSource().getMainSource().getSourceName());
        map.put("incomeDate",         i.getIncomeDate() != null ? i.getIncomeDate().toString() : null);
        TransactionType tt = i.getTransactionType();
        map.put("transactionTypeId",  tt != null ? tt.getId()       : null);
        map.put("method",             tt != null ? tt.getTypeName() : "");
        map.put("refNo",              i.getRefNo());
        map.put("amount",         i.getAmount());
        map.put("note",           i.getNote());
        map.put("quickAdd",       i.isQuickAdd());
        map.put("createdBy",      i.getCreatedBy());
        map.put("updatedBy",      i.getUpdatedBy());
        map.put("updatedDate",    i.getUpdatedDate() != null ? i.getUpdatedDate().toString() : null);
        return map;
    }

    private FamilyMember findMemberOrThrow(Integer id) {
        return memberRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Family member not found: " + id));
    }

    private SubSource findSubSourceOrThrow(Integer id) {
        return subSourceRepo.findById(id)
                .filter(ss -> !ss.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Sub-source not found: " + id));
    }

    private Income findIncomeOrThrow(Integer id) {
        return incomeRepo.findById(id)
                .filter(i -> !i.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Income record not found: " + id));
    }

    private TransactionType findTransactionTypeOrThrow(Integer id) {
        if (id == null) throw new IllegalArgumentException("Transaction type is required.");
        return transactionTypeRepo.findById(id)
                .filter(tt -> !tt.isDeleteFlag())
                .orElseThrow(() -> new IllegalArgumentException("Transaction type not found: " + id));
    }
}

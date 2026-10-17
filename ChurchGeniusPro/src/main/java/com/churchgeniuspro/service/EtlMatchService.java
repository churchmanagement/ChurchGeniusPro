package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * ETL Migration Phase 2 — duplicate and conflict detection per data type.
 *
 * <p>Every staged row gets a <b>tier</b>:
 * <ul>
 *   <li>{@code EXACT} — the same record is already live (source id from the same
 *       source system; import reference for money) → default SKIP, never UPDATE silently;</li>
 *   <li>{@code STRONG} — a natural key matches (e-mail; first + last + phone; first +
 *       last + birth date; member + date + amount + fund …) → default SKIP, the admin
 *       may choose UPDATE / MERGE / import as new;</li>
 *   <li>{@code POSSIBLE} — only a weak key matches (first + last; date + amount) →
 *       row status REVIEW: approval is blocked until the admin decides;</li>
 *   <li>{@code IN_FILE} — the same key appeared earlier in the same upload → default
 *       SKIP with a pointer to the first row;</li>
 *   <li>{@code NONE} — new record → INSERT.</li>
 * </ul>
 * Financial rows never get MERGE, and UPDATE only from an EXACT or STRONG match
 * (enforced in {@link EtlLoadService}). All lookups are tenant-scoped; nothing is
 * written here — the validation step records the result on the staging row.
 */
@Service
public class EtlMatchService {

    public static final String EXACT = "EXACT", STRONG = "STRONG", POSSIBLE = "POSSIBLE", IN_FILE = "IN_FILE", NONE = "NONE";

    /** Result of matching one staged row. {@code liveId} null for IN_FILE/NONE; {@code inFileRow} the earlier staging row id. */
    public record Match(String tier, Long liveId, String how, Long inFileRow) {
        public static Match none() { return new Match(NONE, null, null, null); }
        public boolean isDuplicate() { return !NONE.equals(tier); }
    }

    private final FamilyMemberRepository memberRepo;
    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;

    public EtlMatchService(FamilyMemberRepository memberRepo, IncomeRepository incomeRepo, ExpenseRepository expenseRepo) {
        this.memberRepo = memberRepo;
        this.incomeRepo = incomeRepo;
        this.expenseRepo = expenseRepo;
    }

    // ───────────────────────── family / member ─────────────────────────

    /** Index over the tenant's live members plus the rows already seen in this file. Build once per validate. */
    public final class FamilyIndex {
        private final String sourceSystem;
        private final Map<String, Integer> bySource = new HashMap<>();       // lower(source id) → member id
        private final Map<String, Integer> byEmail = new HashMap<>();
        private final Map<String, Integer> byNamePhone = new HashMap<>();
        private final Map<String, Integer> byNameDob = new HashMap<>();
        private final Map<String, List<Integer>> byName = new HashMap<>();
        // same-file keys → first staging row id
        private final Map<String, Long> fileSource = new HashMap<>(), fileEmail = new HashMap<>(),
                                        fileNamePhone = new HashMap<>(), fileNameDob = new HashMap<>();

        /** @param sourceSystem preset code, or null when the file has no real source ids (row ordinals never match) */
        FamilyIndex(String tenant, String sourceSystem) {
            this.sourceSystem = sourceSystem;
            for (FamilyMember m : safe(memberRepo.findActiveByTenantForEtl(tenant))) {
                if (m.getId() == null) continue;
                if (sourceSystem != null && sourceSystem.equals(m.getSourceSystem()) && notBlank(m.getSourceId()))
                    bySource.putIfAbsent(m.getSourceId().trim().toLowerCase(Locale.ROOT), m.getId());
                String e = emailKey(m.getEmail());          if (e != null) byEmail.putIfAbsent(e, m.getId());
                String np = namePhoneKey(m.getFirstName(), m.getLastName(), m.getPhone()); if (np != null) byNamePhone.putIfAbsent(np, m.getId());
                String nd = nameDobKey(m.getFirstName(), m.getLastName(), m.getBirthdayMonth(), m.getBirthdayDay(), m.getBirthdayYear());
                if (nd != null) byNameDob.putIfAbsent(nd, m.getId());
                String n = nameKey(m.getFirstName(), m.getLastName()); if (n != null) byName.computeIfAbsent(n, k -> new ArrayList<>()).add(m.getId());
            }
        }

        /** Match one staged row, then register it as seen for in-file detection. */
        public Match match(StagingFamily r) {
            String sid = notBlank(r.getSourceRowId()) && sourceSystem != null ? r.getSourceRowId().trim().toLowerCase(Locale.ROOT) : null;
            String e = emailKey(r.getEmail());
            String np = namePhoneKey(r.getFirstName(), r.getLastName(), r.getPhone());
            String nd = nameDobKey(r.getFirstName(), r.getLastName(), r.getBirthdayMonth(), r.getBirthdayDay(), r.getBirthdayYear());
            String n = nameKey(r.getFirstName(), r.getLastName());
            Match out;
            // in-file first: the same person twice in one upload must not be inserted twice
            if (sid != null && fileSource.containsKey(sid))          out = new Match(IN_FILE, null, "same source id as row " + fileSource.get(sid), fileSource.get(sid));
            else if (e != null && fileEmail.containsKey(e))          out = new Match(IN_FILE, null, "same e-mail as row " + fileEmail.get(e), fileEmail.get(e));
            else if (np != null && fileNamePhone.containsKey(np))    out = new Match(IN_FILE, null, "same name + phone as row " + fileNamePhone.get(np), fileNamePhone.get(np));
            else if (nd != null && fileNameDob.containsKey(nd))      out = new Match(IN_FILE, null, "same name + birth date as row " + fileNameDob.get(nd), fileNameDob.get(nd));
            else if (sid != null && bySource.containsKey(sid))       out = new Match(EXACT, bySource.get(sid).longValue(), "source id " + r.getSourceRowId() + " already imported", null);
            else if (e != null && byEmail.containsKey(e))            out = new Match(STRONG, byEmail.get(e).longValue(), "e-mail", null);
            else if (np != null && byNamePhone.containsKey(np))      out = new Match(STRONG, byNamePhone.get(np).longValue(), "name + phone", null);
            else if (nd != null && byNameDob.containsKey(nd))        out = new Match(STRONG, byNameDob.get(nd).longValue(), "name + birth date", null);
            else if (n != null && byName.containsKey(n))             out = new Match(POSSIBLE, byName.get(n).get(0).longValue(),
                                                                         "same first and last name" + (byName.get(n).size() > 1 ? " (" + byName.get(n).size() + " members)" : ""), null);
            else out = Match.none();

            Long id = r.getId() == null ? -1L : r.getId();
            if (sid != null) fileSource.putIfAbsent(sid, id);
            if (e != null) fileEmail.putIfAbsent(e, id);
            if (np != null) fileNamePhone.putIfAbsent(np, id);
            if (nd != null) fileNameDob.putIfAbsent(nd, id);
            return out;
        }
    }

    /** @param sourceSystem the run's preset code when the people file carried real source ids, else null */
    public FamilyIndex familyIndex(String tenant, String sourceSystem) {
        return new FamilyIndex(tenant, sourceSystem);
    }

    // ───────────────────────── income / expense ─────────────────────────

    /** Index over the tenant's live money rows in the staged date window, plus rows seen in this file. */
    public final class MoneyIndex {
        private final Map<String, Integer> byRef = new HashMap<>();          // import_ref → id
        private final Map<String, Integer> byStrong = new HashMap<>();       // person|date|amount|fund → id
        private final Map<String, List<Integer>> byLoose = new HashMap<>();  // date|amount|fund → ids
        private final Map<String, Long> fileRef = new HashMap<>(), fileStrong = new HashMap<>();

        MoneyIndex(String tenant, String table, LocalDate from, LocalDate to) {
            if (from == null || to == null) return;
            if ("income".equals(table) ? incomeRepo == null : expenseRepo == null) return;   // compat wiring without live repos
            if ("income".equals(table)) {
                for (Income i : safe(incomeRepo.findByAppClientIdAndDeleteFlagFalseAndIncomeDateBetween(tenant, from, to))) {
                    if (notBlank(i.getImportRef())) byRef.putIfAbsent(i.getImportRef().trim(), i.getId());
                    String person = i.getMember() != null && i.getMember().getId() != null ? "m" + i.getMember().getId()
                                  : notBlank(i.getGuestName()) ? "g" + i.getGuestName().trim().toLowerCase(Locale.ROOT) : null;
                    String fund = i.getSubSource() == null ? "" : lower(i.getSubSource().getSourceName());
                    String loose = looseKey(i.getIncomeDate(), i.getAmount(), fund);
                    if (loose != null) {
                        byLoose.computeIfAbsent(loose, k -> new ArrayList<>()).add(i.getId());
                        if (person != null) byStrong.putIfAbsent(person + "|" + loose, i.getId());
                    }
                }
            } else {
                for (Expense x : safe(expenseRepo.findByAppClientIdAndDeleteFlagFalseAndExpenseDateBetween(tenant, from, to))) {
                    if (notBlank(x.getImportRef())) byRef.putIfAbsent(x.getImportRef().trim(), x.getId());
                    String cat = x.getMainSource() == null ? "" : lower(x.getMainSource().getSourceName());
                    String loose = looseKey(x.getExpenseDate(), x.getAmount(), "");
                    if (loose != null) {
                        byLoose.computeIfAbsent(loose, k -> new ArrayList<>()).add(x.getId());
                        byStrong.putIfAbsent(cat + "|" + loose, x.getId());
                    }
                }
            }
        }

        /** @param person resolved member id ("m123"), guest name ("gname") or null when unresolved */
        public Match matchIncome(StagingIncome r, String person) {
            String ref = notBlank(r.getImportRef()) ? r.getImportRef().trim() : null;
            String loose = looseKey(r.getIncomeDate(), r.getAmount(), lower(fundOf(r)));
            String strong = person != null && loose != null ? person + "|" + loose : null;
            return decide(r.getId(), ref, strong, loose, "same donor, date, amount and fund", "same date, amount and fund — donor unresolved");
        }

        public Match matchExpense(StagingExpense r) {
            String ref = notBlank(r.getImportRef()) ? r.getImportRef().trim() : null;
            String loose = looseKey(r.getExpenseDate(), r.getAmount(), "");
            String cat = lower(r.getCategory() != null ? r.getCategory() : r.getPurposeName());
            String strong = loose != null ? cat + "|" + loose : null;
            return decide(r.getId(), ref, strong, loose, "same date, amount and category", "same date and amount");
        }

        private Match decide(Long rowId, String ref, String strong, String loose, String strongHow, String looseHow) {
            Match out;
            if (ref != null && fileRef.containsKey(ref))             out = new Match(IN_FILE, null, "same import reference as row " + fileRef.get(ref), fileRef.get(ref));
            else if (strong != null && fileStrong.containsKey(strong)) out = new Match(IN_FILE, null, strongHow + " as row " + fileStrong.get(strong), fileStrong.get(strong));
            else if (ref != null && byRef.containsKey(ref))          out = new Match(EXACT, byRef.get(ref).longValue(), "import reference " + ref + " already on the ledger", null);
            else if (strong != null && byStrong.containsKey(strong)) out = new Match(STRONG, byStrong.get(strong).longValue(), strongHow, null);
            else if (loose != null && byLoose.containsKey(loose))    out = new Match(POSSIBLE, byLoose.get(loose).get(0).longValue(), looseHow, null);
            else out = Match.none();
            Long id = rowId == null ? -1L : rowId;
            if (ref != null) fileRef.putIfAbsent(ref, id);
            if (strong != null) fileStrong.putIfAbsent(strong, id);
            return out;
        }
    }

    public MoneyIndex moneyIndex(String tenant, String table, LocalDate from, LocalDate to) {
        return new MoneyIndex(tenant, table, from, to);
    }

    // ───────────────────────── keys ─────────────────────────

    static String fundOf(StagingIncome r) {
        return notBlank(r.getSubSourceName()) ? r.getSubSourceName() : (r.getSourceName() == null ? "" : r.getSourceName());
    }

    static String looseKey(LocalDate date, BigDecimal amount, String fund) {
        if (date == null || amount == null) return null;
        return date + "|" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "|" + (fund == null ? "" : fund);
    }

    static String emailKey(String email) {
        return notBlank(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
    }

    static String nameKey(String first, String last) {
        String f = lower(first), l = lower(last);
        if (f.isEmpty() || l.isEmpty()) return null;
        return f + "|" + l;
    }

    static String namePhoneKey(String first, String last, String phone) {
        String n = nameKey(first, last);
        String p = phone == null ? "" : phone.replaceAll("\\D", "");
        if (n == null || p.length() < 7) return null;
        return n + "|" + (p.length() > 10 ? p.substring(p.length() - 10) : p);   // national significant digits
    }

    static String nameDobKey(String first, String last, Integer m, Integer d, Integer y) {
        String n = nameKey(first, last);
        if (n == null || m == null || d == null || y == null) return null;
        return n + "|" + y + "-" + m + "-" + d;
    }

    static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }
}

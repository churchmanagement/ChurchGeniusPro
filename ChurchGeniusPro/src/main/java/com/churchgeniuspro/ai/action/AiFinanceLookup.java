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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-08 — the guided income / expense conversation's lookups. Every list and every by-id read is scoped to
 * ONE church (a blank church id answers nothing — the repositories treat null as "no filter", so it never reaches
 * them). Income fund = {@code sub_source}; expense fund = {@code main_source}; expense category = {@code purpose};
 * method = {@code transaction_type}. Only names and ids leave this class.
 */
@Component
public class AiFinanceLookup {

    private final SubSourceRepository subSources;
    private final MainSourceRepository mainSources;
    private final PurposeRepository purposes;
    private final TransactionTypeRepository methods;
    private final NamedParameterJdbcTemplate jdbc;

    public AiFinanceLookup(SubSourceRepository subSources, MainSourceRepository mainSources, PurposeRepository purposes,
                           TransactionTypeRepository methods, NamedParameterJdbcTemplate jdbc) {
        this.subSources = subSources; this.mainSources = mainSources; this.purposes = purposes; this.methods = methods; this.jdbc = jdbc;
    }

    static boolean blank(String cid) { return cid == null || cid.isBlank(); }

    /** Income funds (sub-categories) of the church, A–Z. */
    public List<Hit> incomeFunds(String cid) {
        List<Hit> out = new ArrayList<>();
        if (blank(cid)) return out;
        for (SubSource s : subSources.findAllActiveByAppUser(cid))
            if (cid.equals(s.getAppClientId()) && s.getId() != null) out.add(new Hit(s.getId(), s.getSourceName(), null));
        return out;
    }
    /** Expense funds (main sources) of the church, A–Z. */
    public List<Hit> expenseFunds(String cid) {
        List<Hit> out = new ArrayList<>();
        if (blank(cid)) return out;
        for (MainSource s : mainSources.findActiveByAppUser(cid))
            if (cid.equals(s.getAppClientId()) && s.getId() != null) out.add(new Hit(s.getId(), s.getSourceName(), null));
        return out;
    }
    /** Expense categories (purposes) of the church, A–Z. */
    public List<Hit> expenseCategories(String cid) {
        List<Hit> out = new ArrayList<>();
        if (blank(cid)) return out;
        for (Purpose p : purposes.findActiveByAppUser(cid))
            if (cid.equals(p.getAppClientId()) && p.getId() != null) out.add(new Hit(p.getId(), p.getPurposeName(), null));
        return out;
    }
    /** Payment methods (transaction types) of the church, A–Z. */
    public List<Hit> methods(String cid) {
        List<Hit> out = new ArrayList<>();
        if (blank(cid)) return out;
        for (TransactionType t : methods.findActiveByAppUser(cid))
            if (cid.equals(t.getAppClientId()) && t.getId() != null) out.add(new Hit(t.getId(), t.getTypeName(), null));
        return out;
    }

    public Hit incomeFundById(String cid, long id) {
        if (blank(cid) || id > Integer.MAX_VALUE) return null;
        return subSources.findByIdAndAppClientIdAndDeleteFlagFalse((int) id, cid).map(s -> new Hit(s.getId(), s.getSourceName(), null)).orElse(null);
    }
    public Hit expenseFundById(String cid, long id) {
        if (blank(cid) || id > Integer.MAX_VALUE) return null;
        return mainSources.findByIdAndAppClientIdAndDeleteFlagFalse((int) id, cid).map(s -> new Hit(s.getId(), s.getSourceName(), null)).orElse(null);
    }
    public Hit expenseCategoryById(String cid, long id) {
        if (blank(cid) || id > Integer.MAX_VALUE) return null;
        return purposes.findByIdAndAppClientIdAndDeleteFlagFalse((int) id, cid).map(p -> new Hit(p.getId(), p.getPurposeName(), null)).orElse(null);
    }
    public Hit methodById(String cid, long id) {
        if (blank(cid) || id > Integer.MAX_VALUE) return null;
        return methods.findByIdAndAppClientIdAndDeleteFlagFalse((int) id, cid).map(t -> new Hit(t.getId(), t.getTypeName(), null)).orElse(null);
    }

    /** Live income rows of this church with the same contributor, fund, date and amount (the "already recorded?" warning). */
    public int incomeDuplicates(String cid, LocalDate date, BigDecimal amount, long fundId, Long memberId, String guestName) {
        if (blank(cid) || date == null || amount == null) return 0;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("d", date).addValue("a", amount).addValue("f", fundId);
        String who;
        if (memberId != null) { who = "i.member_id = :m"; p.addValue("m", memberId); }
        else { who = "i.member_id IS NULL AND LOWER(TRIM(COALESCE(i.guest_name,''))) = :g"; p.addValue("g", guestName == null ? "" : guestName.trim().toLowerCase(java.util.Locale.ROOT)); }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM income i WHERE i.app_client_id = :cid AND i.delete_flag = false "
                + "AND i.income_date = :d AND i.amount = :a AND i.sub_source_id = :f AND " + who, p, Integer.class);
        return n == null ? 0 : n;
    }

    /** Live expense rows of this church with the same category, fund, date and amount. */
    public int expenseDuplicates(String cid, LocalDate date, BigDecimal amount, long categoryId, long fundId) {
        if (blank(cid) || date == null || amount == null) return 0;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM expense e WHERE e.app_client_id = :cid AND e.delete_flag = false "
                + "AND e.expense_date = :d AND e.amount = :a AND e.purpose_id = :p AND e.main_source_id = :f",
                new MapSqlParameterSource().addValue("cid", cid).addValue("d", date).addValue("a", amount).addValue("p", categoryId).addValue("f", fundId), Integer.class);
        return n == null ? 0 : n;
    }
}

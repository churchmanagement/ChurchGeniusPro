package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.KmClassroom;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingSkipDate;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PrayerRequest;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.VolunteerAssignment;
import com.churchgeniuspro.hibernate.VolunteerRole;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmClassroomRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PrayerRequestRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.VolunteerAssignmentRepository;
import com.churchgeniuspro.repository.VolunteerRoleRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiDataSearchService.DateRange;
import com.churchgeniuspro.util.MeetingRecurrence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * AI Search Phase B — structured, bounded data answers.
 *
 * <p>Every intent here (a) is allowed only by the matching {@link AiAccess} flag — decided
 * by {@link AiAccessPolicy} from the session before any repository is touched, (b) runs a
 * tenant-scoped, parameterised repository query that is bounded by {@code ai.search.max-rows}
 * (lists ask for one row more than they show, to say "more exist" without a second count
 * unless an indexed COUNT is cheap) and {@code ai.search.max-range-days} (longer periods
 * are refused with a request to narrow — never a partial or invented figure), and (c)
 * returns only the fields the answer needs: no ids, tokens, medical notes, contact details
 * or descriptions. Dates follow the church calendar ({@code AppClock}, America/Chicago).
 *
 * <p>Answers come back as {@code {handled, denied, intent, answer, columns, rows, total,
 * shown, truncated, period, link}} so the search bar can draw a table or a summary card.
 */
@Service
public class AiStructuredAnswerService {

    private final IncomeRepository incomeRepo;
    private final ExpenseRepository expenseRepo;
    private final FamilyMemberRepository memberRepo;
    private final PrayerRequestRepository prayerRepo;
    private final FollowUpRepository followUpRepo;
    private final VolunteerAssignmentRepository assignmentRepo;
    private final VolunteerRoleRepository volunteerRoleRepo;
    private final ChurchEventRepository eventRepo;
    private final ReminderService reminderService;
    private final PublicScreenLinkRepository publicLinkRepo;
    private final KmChildRepository kmChildRepo;
    private final KmClassroomRepository kmClassroomRepo;
    private final MeetingRepository meetingRepo;
    private final MeetingSkipDateRepository skipRepo;
    private final MeetingService meetingService;
    private final PledgeCampaignRepository campaignRepo;
    private final PledgeTotalsService pledgeTotals;
    private final ReportQueryService reports;

    @Value("${ai.search.max-rows:20}")        public int maxRows = 20;
    @Value("${ai.search.max-range-days:400}") public int maxRangeDays = 400;

    public AiStructuredAnswerService(IncomeRepository incomeRepo, ExpenseRepository expenseRepo, FamilyMemberRepository memberRepo,
                                     PrayerRequestRepository prayerRepo, FollowUpRepository followUpRepo,
                                     VolunteerAssignmentRepository assignmentRepo, VolunteerRoleRepository volunteerRoleRepo,
                                     ChurchEventRepository eventRepo, ReminderService reminderService,
                                     PublicScreenLinkRepository publicLinkRepo, KmChildRepository kmChildRepo, KmClassroomRepository kmClassroomRepo,
                                     MeetingRepository meetingRepo, MeetingSkipDateRepository skipRepo, MeetingService meetingService,
                                     PledgeCampaignRepository campaignRepo, PledgeTotalsService pledgeTotals, ReportQueryService reports) {
        this.incomeRepo = incomeRepo; this.expenseRepo = expenseRepo; this.memberRepo = memberRepo;
        this.prayerRepo = prayerRepo; this.followUpRepo = followUpRepo;
        this.assignmentRepo = assignmentRepo; this.volunteerRoleRepo = volunteerRoleRepo;
        this.eventRepo = eventRepo; this.reminderService = reminderService;
        this.publicLinkRepo = publicLinkRepo; this.kmChildRepo = kmChildRepo; this.kmClassroomRepo = kmClassroomRepo;
        this.meetingRepo = meetingRepo; this.skipRepo = skipRepo; this.meetingService = meetingService;
        this.campaignRepo = campaignRepo; this.pledgeTotals = pledgeTotals; this.reports = reports;
    }

    /* ════════════════════════ routing ════════════════════════ */

    private static boolean has(String q, String regex) { return Pattern.compile(".*\\b(?:" + regex + ")\\b.*").matcher(q).matches(); }
    private static boolean wantsList(String q) { return has(q, "list|show|display|which|who|what are|all|give me|see"); }

    /**
     * Routes one normalised question; returns null when it is not one of the Phase B
     * intents (the caller continues with the older intents / navigation).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> route(String q, AiAccess a, String clientId, LocalDate today) {
        if (a == null || a.kids()) return null;                       // the caller has already denied Kids sessions

        // Members / families by status (deleted, inactive, alerts disabled) — Families page records.
        Map<String, Object> byStatus = peopleByStatus(q, a, clientId);
        if (byStatus != null) return byStatus;

        // Children: two separate sources, never combined, never a fallback from one to the other (decision 3).
        if (has(q, "kids?|children|child") && !has(q, "kids? portal|birthday|anniversar\\w*|checkin|check in")) {
            boolean km  = has(q, "kids? ministry|classroom|classrooms|sunday school|registered|enrolled|ministry");
            boolean fam = has(q, "famil(y|ies)|household|member records?|directory|family records?");
            if (km && !fam)  return kidsMinistryChildren(a, clientId);
            if (fam && !km)  return familyChildren(a, clientId);
            return clarify("children", "Do you mean the children registered in Kids Ministry, or the children listed in your family records? "
                    + "Please ask for “Kids Ministry children” or “children in family records”.");
        }
        if (has(q, "public (links?|screens?|pages?|urls?)|generated links?|shared links?"))        return publicLinks(a, clientId, today);
        if (has(q, "reminders?"))                                                                return reminders(a, clientId);
        if (has(q, "events?") && !has(q, "event reminders?"))                                     return events(a, clientId, today, q);
        if (has(q, "meetings?"))                                                                  return meetings(a, clientId, today, q);
        if (has(q, "prayers?|prayer requests?|intercessions?"))                                   return prayer(a, clientId);
        if (has(q, "follow[- ]?ups?|tasks?|to-?dos?|action items?"))                              return followUps(a, clientId);
        if (has(q, "volunteers?") && has(q, "assign\\w*|schedul\\w*|serving|roster|upcoming")
                || has(q, "serving|roster") && !has(q, "meetings?|events?"))                     return assignedVolunteers(a, clientId, today);   // "who is serving / on duty"
        if (has(q, "campaigns?|pledges?|pledge drives?"))                                         return campaigns(a, clientId, q);
        if (has(q, "members?") && has(q, "how many|count|number of|total|males?|females?|gender|men|women|male|female")) return memberCounts(a, clientId);
        // Period reports (Phase C) — before the plain totals, so "income report" is a report.
        if (has(q, "reports?|statements?") && !has(q, "tax report|contribution statements?|giving statements?")) {
            if (has(q, "financial|finance|profit|net|income and expenses?|summary report"))    return financialReport(a, clientId, q, today);
            if (has(q, "expenses?|spending|expenditures?"))                                    return expenseReport(a, clientId, q, today);
            if (has(q, "income|contributions?|donations?|giving|revenue|offerings?|tithes?"))  return incomeReport(a, clientId, q, today);   // canonical words: income / contribution / tithe
            if (has(q, "transactions?"))                                                       return transactions(a, clientId, q, today);
        }
        if (has(q, "transactions?"))                                                              return transactions(a, clientId, q, today);
        if (has(q, "expenses?|spent|spending|expenditures?|costs?|paid out"))                     return expenses(a, clientId, q, today);
        // Church-wide giving (a single person's giving was answered before this router). The
        // question arrives with synonyms already mapped (AiQueryLexicon): every way of saying
        // donation / gift / offering / gave reads "contribution". A list ("who", "show", "top")
        // is the Income page's rows; a "how much" is the Reports total.
        if (has(q, "contributions?|tithes?|contributors?|donations?|donors?|givers?")) {
            if (has(q, "who|which|list|show|display|all|top|breakdown|each|everyone|names?|members?|people|details?|records?|entries|contributors?|donors?|givers?"))
                return contributors(a, clientId, q, today);
            return incomeTotal(a, clientId, q, today);
        }
        if (has(q, "income|revenue|collections?|offerings?|tithes?|giving|received|receive|came in"))   return incomeTotal(a, clientId, q, today);
        return null;
    }

    /* ════════════════════════ finance ════════════════════════ */

    private Map<String, Object> contributors(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.incomeRecords()) return denied("contributors");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "contributors"); if (narrow != null) return narrow;
        List<Object[]> rows = incomeRepo.aiIncomeRows(cid, Date.valueOf(r.from), Date.valueOf(r.to), maxRows + 1);
        Object[] tot = first(incomeRepo.aiIncomeTotals(cid, Date.valueOf(r.from), Date.valueOf(r.to)));
        long count = tot == null ? rows.size() : ((Number) tot[1]).longValue();
        if (rows.isEmpty()) return ok("contributors", "No contributions were recorded for " + r.label + " (" + range(r) + ").", r, "/income", "Open Income");
        boolean truncated = rows.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (Object[] x : rows.subList(0, Math.min(rows.size(), maxRows))) {
            String who = name(x[1], x[2]); if (who.isBlank()) who = x[3] == null ? "(guest)" : str(x[3]) + " (guest)";
            out.add(List.of(date(x[0]), who, money(x[6]), fund(x[4], x[5]), method(x[7], x[8])));
        }
        String answer = "I found " + count + " contribution" + (count == 1 ? "" : "s") + " for " + r.label + " (" + range(r) + ")"
                + (truncated ? ". Showing the first " + maxRows + "." : ".");
        return table("contributors", answer, List.of("Date", "Contributor", "Amount", "Fund", "Method"), out, count, truncated, r, "/income", "Open Income");
    }

    private Map<String, Object> incomeTotal(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.reports()) return denied("income");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "income"); if (narrow != null) return narrow;
        Object[] t = first(incomeRepo.aiIncomeTotals(cid, Date.valueOf(r.from), Date.valueOf(r.to)));
        BigDecimal total = t == null ? BigDecimal.ZERO : num(t[0]); long n = t == null ? 0 : ((Number) t[1]).longValue(); BigDecimal online = t == null ? BigDecimal.ZERO : num(t[2]);
        if (n == 0) return ok("income", "No income was recorded for " + r.label + " (" + range(r) + ").", r, "/income-report", "Open Income Report");
        String answer = "Total income for " + r.label + " (" + range(r) + "): " + money(total) + " across " + n + " transaction" + (n == 1 ? "" : "s")
                + (online.signum() > 0 ? ", of which " + money(online) + " came in as online donations (gross — processing fees are recorded separately as expenses)." : ".");
        Map<String, Object> m = ok("income", answer, r, "/income-report", "Open Income Report");
        m.put("summary", summary("Total income", money(total), "Transactions", String.valueOf(n), "Online donations (gross)", money(online)));
        return m;
    }

    private Map<String, Object> expenses(AiAccess a, String cid, String q, LocalDate today) {
        boolean list = wantsList(q) && !has(q, "how much|total");
        if (list ? !a.expenseRecords() : !a.reports()) return denied("expenses");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "expenses"); if (narrow != null) return narrow;
        ExpenseCategory cat = expenseCategoryIn(cid, q);
        if (cat != null) return expensesFor(cid, r, cat, list);
        Object[] t = first(expenseRepo.aiExpenseTotals(cid, Date.valueOf(r.from), Date.valueOf(r.to)));
        BigDecimal total = t == null ? BigDecimal.ZERO : num(t[0]); long n = t == null ? 0 : ((Number) t[1]).longValue(); BigDecimal fees = t == null ? BigDecimal.ZERO : num(t[2]);
        if (n == 0) return ok("expenses", "No expenses were recorded for " + r.label + " (" + range(r) + ").", r, "/expense-report", "Open Expense Report");
        String answer = "Total expenses for " + r.label + " (" + range(r) + "): " + money(total) + " across " + n + " entr" + (n == 1 ? "y" : "ies")
                + (fees.signum() > 0 ? ", including " + money(fees) + " of online-donation processing fees." : ".");
        if (!list) {
            Map<String, Object> m = ok("expenses", answer, r, "/expense-report", "Open Expense Report");
            m.put("summary", summary("Total expenses", money(total), "Entries", String.valueOf(n), "Processing fees", money(fees)));
            return m;
        }
        List<Object[]> rows = expenseRepo.aiExpenseRows(cid, Date.valueOf(r.from), Date.valueOf(r.to), maxRows + 1);
        boolean truncated = rows.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (Object[] x : rows.subList(0, Math.min(rows.size(), maxRows)))
            out.add(List.of(date(x[0]), str(x[1]), str(x[2]), money(x[3]), method(x[4], x[5])));
        return table("expenses", answer + (truncated ? " Showing the first " + maxRows + "." : ""), List.of("Date", "Fund", "Purpose", "Amount", "Method"), out, n, truncated, r, "/expense", "Open Expense");
    }

    /** An expense purpose ('P') or category ('M') named in the question: display name + every id with that name. */
    record ExpenseCategory(String kind, String name, List<Integer> ids) {}

    /** Names that are the question's own words, never a filter (a purpose literally called "Expenses"). */
    private static final Set<String> NOT_A_CATEGORY = Set.of("expense", "expenses", "expenditure", "expenditures", "spending", "spent", "cost", "costs", "transactions");

    private static String normName(String s) {
        return AiQueryLexicon.canonical(s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * The church's own purpose or category the question names (whole words, longest name
     * wins, a purpose before a category of the same name); null when none is named, so the
     * question keeps meaning "all expenses".
     */
    ExpenseCategory expenseCategoryIn(String cid, String q) {
        List<Object[]> names = expenseRepo.aiExpenseCategoryNames(cid);
        if (names == null || names.isEmpty()) return null;
        String hay = " " + normName(q) + " ";
        String bestNorm = null, bestKind = null, bestName = null;
        for (Object[] n : names) {
            String kind = String.valueOf(n[0]), name = str(n[2]), nn = normName(name);
            if (nn.length() < 3 || NOT_A_CATEGORY.contains(nn) || !hay.contains(" " + nn + " ")) continue;
            boolean better = bestNorm == null || nn.length() > bestNorm.length()
                    || nn.length() == bestNorm.length() && "P".equals(kind) && !"P".equals(bestKind);
            if (better) { bestNorm = nn; bestKind = kind; bestName = name.trim(); }
        }
        if (bestNorm == null) return null;
        List<Integer> ids = new ArrayList<>();
        for (Object[] n : names)
            if (bestKind.equals(String.valueOf(n[0])) && bestNorm.equals(normName(str(n[2])))) ids.add(((Number) n[1]).intValue());
        return new ExpenseCategory(bestKind, bestName, ids);
    }

    /** Expenses for one named purpose / category only — never the church-wide total under that name. */
    private Map<String, Object> expensesFor(String cid, DateRange r, ExpenseCategory cat, boolean list) {
        Object[] t = first(expenseRepo.aiExpenseTotalsFor(cid, Date.valueOf(r.from), Date.valueOf(r.to), cat.kind(), cat.ids()));
        BigDecimal total = t == null ? BigDecimal.ZERO : num(t[0]); long n = t == null ? 0 : ((Number) t[1]).longValue(); BigDecimal fees = t == null ? BigDecimal.ZERO : num(t[2]);
        String url = "/expense-report?startDate=" + r.from + "&endDate=" + r.to;
        if (n == 0) return ok("expenses", "No expenses for " + cat.name() + " were recorded for " + r.label + " (" + range(r) + ").", r, url, "Open Expense Report");
        String answer = "Spent on " + cat.name() + " for " + r.label + " (" + range(r) + "): " + money(total) + " across " + n + " entr" + (n == 1 ? "y" : "ies")
                + (fees.signum() > 0 ? ", including " + money(fees) + " of online-donation processing fees." : ".");
        if (!list) {
            Map<String, Object> m = ok("expenses", answer, r, url, "Open Expense Report");
            m.put("summary", summary(cat.name(), money(total), "Entries", String.valueOf(n)));
            return m;
        }
        List<Object[]> rows = expenseRepo.aiExpenseRowsFor(cid, Date.valueOf(r.from), Date.valueOf(r.to), cat.kind(), cat.ids(), maxRows + 1);
        boolean truncated = rows.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (Object[] x : rows.subList(0, Math.min(rows.size(), maxRows)))
            out.add(List.of(date(x[0]), str(x[1]), str(x[2]), money(x[3]), method(x[4], x[5])));
        return table("expenses", answer + (truncated ? " Showing the first " + maxRows + "." : ""), List.of("Date", "Fund", "Purpose", "Amount", "Method"), out, n, truncated, r, "/expense", "Open Expense");
    }

    private Map<String, Object> transactions(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.reports()) return denied("transactions");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "transactions"); if (narrow != null) return narrow;
        Object[] ti = first(incomeRepo.aiIncomeTotals(cid, Date.valueOf(r.from), Date.valueOf(r.to)));
        Object[] te = first(expenseRepo.aiExpenseTotals(cid, Date.valueOf(r.from), Date.valueOf(r.to)));
        BigDecimal inc = ti == null ? BigDecimal.ZERO : num(ti[0]), exp = te == null ? BigDecimal.ZERO : num(te[0]);
        long ni = ti == null ? 0 : ((Number) ti[1]).longValue(), ne = te == null ? 0 : ((Number) te[1]).longValue();
        if (ni + ne == 0) return ok("transactions", "No transactions were recorded for " + r.label + " (" + range(r) + ").", r, "/transactions-report", "Open Transactions Report");
        int half = Math.max(1, maxRows / 2);
        List<Object[]> inRows = incomeRepo.aiIncomeRows(cid, Date.valueOf(r.from), Date.valueOf(r.to), half + 1);
        List<Object[]> exRows = expenseRepo.aiExpenseRows(cid, Date.valueOf(r.from), Date.valueOf(r.to), half + 1);
        List<List<String>> out = new ArrayList<>();
        for (Object[] x : inRows.subList(0, Math.min(inRows.size(), half))) {
            String who = name(x[1], x[2]); if (who.isBlank()) who = x[3] == null ? "" : str(x[3]) + " (guest)";
            out.add(List.of(date(x[0]), "Income", (who.isBlank() ? "" : who + " — ") + fund(x[4], x[5]), money(x[6]), method(x[7], x[8])));
        }
        for (Object[] x : exRows.subList(0, Math.min(exRows.size(), half)))
            out.add(List.of(date(x[0]), "Expense", str(x[1]) + " / " + str(x[2]), money(x[3]), method(x[4], x[5])));
        out.sort((p1, p2) -> p2.get(0).compareTo(p1.get(0)));   // newest first across both kinds (ISO dates sort lexically)
        boolean truncated = inRows.size() > half || exRows.size() > half;
        String answer = ni + " income and " + ne + " expense transaction" + (ne == 1 ? "" : "s") + " for " + r.label + " (" + range(r) + "). "
                + "Income " + money(inc) + ", expenses " + money(exp) + ", net " + money(inc.subtract(exp)) + "."
                + (truncated ? " Showing the most recent " + out.size() + "." : "");
        Map<String, Object> m = table("transactions", answer, List.of("Date", "Type", "Details", "Amount", "Method"), out, ni + ne, truncated, r, "/transactions-report", "Open Transactions Report");
        m.put("summary", summary("Income", money(inc), "Expenses", money(exp), "Net", money(inc.subtract(exp))));
        return m;
    }

    private Map<String, Object> campaigns(AiAccess a, String cid, String q) {
        if (!a.pledges()) return denied("campaigns");
        boolean all = has(q, "all|closed|every|including closed|past");
        List<PledgeCampaign> cs = campaignRepo.findByClientId(cid);
        List<PledgeCampaign> shown = new ArrayList<>();
        for (PledgeCampaign c : cs) { if (all || "Active".equalsIgnoreCase(c.getStatus())) shown.add(c); }
        if (shown.isEmpty()) return ok("campaigns", all ? "There are no pledge campaigns." : "There are no active pledge campaigns. Say “all campaigns” to include closed ones.", null, "/pledges", "Open Pledges");
        boolean truncated = shown.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (PledgeCampaign c : shown.subList(0, Math.min(shown.size(), maxRows))) {
            PledgeTotalsService.Totals t = pledgeTotals.totals(cid, c);
            out.add(List.of(str(c.getName()), str(c.getStatus()), money(c.getTargetAmount()), money(t.pledged()), money(t.collected()), money(t.pending()), String.valueOf(t.pledgers())));
        }
        String answer = shown.size() + (all ? "" : " active") + " campaign" + (shown.size() == 1 ? "" : "s") + ". “Amount” is shown three ways — pledged, collected so far, and still pending — because the application tracks each separately."
                + (truncated ? " Showing the first " + maxRows + "." : "");
        return table("campaigns", answer, List.of("Campaign", "Status", "Target", "Pledged", "Collected", "Pending", "Pledgers"), out, (long) shown.size(), truncated, null, "/pledges", "Open Pledges");
    }

    /* ════════════════════════ period reports (Phase C) ════════════════════════ */

    private Map<String, Object> incomeReport(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.reports()) return denied("income-report");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "income-report"); if (narrow != null) return narrow;
        ReportQueryService.Report rep = reports.income(cid, r.from, r.to);
        String url = "/income-report?startDate=" + r.from + "&endDate=" + r.to;
        if (rep.income().isEmpty()) return ok("income-report", "No income was recorded for " + r.label + " (" + range(r) + "), so there is nothing to report.", r, url, "Open Income Report");
        List<List<String>> out = new ArrayList<>();
        for (ReportQueryService.Line l : rep.income().subList(0, Math.min(rep.income().size(), maxRows))) out.add(List.of(l.category(), l.item(), money(l.total())));
        boolean truncated = rep.income().size() > maxRows;
        Map<String, Object> m = table("income-report", "Income report for " + r.label + " (" + range(r) + "): " + money(rep.totalIncome()) + " across " + rep.income().size() + " fund" + (rep.income().size() == 1 ? "" : "s") + "." + (truncated ? " Showing the first " + maxRows + " funds." : "") + " Open the full report for every transaction, filters and PDF.",
                List.of("Category", "Fund", "Total"), out, (long) rep.income().size(), truncated, r, url, "Open Income Report");
        m.put("summary", summary("Total income", money(rep.totalIncome()), "Funds", String.valueOf(rep.income().size())));
        return m;
    }

    private Map<String, Object> expenseReport(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.reports()) return denied("expense-report");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "expense-report"); if (narrow != null) return narrow;
        ReportQueryService.Report rep = reports.expenses(cid, r.from, r.to);
        String url = "/expense-report?startDate=" + r.from + "&endDate=" + r.to;
        if (rep.expenses().isEmpty()) return ok("expense-report", "No expenses were recorded for " + r.label + " (" + range(r) + "), so there is nothing to report.", r, url, "Open Expense Report");
        List<List<String>> out = new ArrayList<>();
        for (ReportQueryService.Line l : rep.expenses().subList(0, Math.min(rep.expenses().size(), maxRows))) out.add(List.of(l.category(), l.item(), money(l.total())));
        boolean truncated = rep.expenses().size() > maxRows;
        Map<String, Object> m = table("expense-report", "Expense report for " + r.label + " (" + range(r) + "): " + money(rep.totalExpense()) + " across " + rep.expenses().size() + " purpose" + (rep.expenses().size() == 1 ? "" : "s") + "." + (truncated ? " Showing the first " + maxRows + "." : "") + " Open the full report for every entry and PDF.",
                List.of("Category", "Purpose", "Total"), out, (long) rep.expenses().size(), truncated, r, url, "Open Expense Report");
        m.put("summary", summary("Total expenses", money(rep.totalExpense()), "Purposes", String.valueOf(rep.expenses().size())));
        return m;
    }

    private Map<String, Object> financialReport(AiAccess a, String cid, String q, LocalDate today) {
        if (!a.reports()) return denied("financial-report");
        DateRange r = period(q, today);
        Map<String, Object> narrow = tooWide(r, "financial-report"); if (narrow != null) return narrow;
        ReportQueryService.Report rep = reports.financial(cid, r.from, r.to);
        // The Financial Report page is per calendar year; any other period opens the Transactions report for the same range.
        boolean wholeYear = r.from.getDayOfYear() == 1 && r.to.equals(LocalDate.of(r.from.getYear(), 12, 31));
        String url = wholeYear ? "/financial-report?year=" + r.from.getYear() : "/transactions-report?startDate=" + r.from + "&endDate=" + r.to;
        String label = wholeYear ? "Open Financial Report" : "Open Transactions Report";
        if (rep.income().isEmpty() && rep.expenses().isEmpty()) return ok("financial-report", "No income or expenses were recorded for " + r.label + " (" + range(r) + "), so there is nothing to report.", r, url, label);
        List<List<String>> out = new ArrayList<>();
        int half = Math.max(1, maxRows / 2);
        for (ReportQueryService.Line l : rep.income().subList(0, Math.min(rep.income().size(), half))) out.add(List.of("Income", l.category(), l.item(), money(l.total())));
        for (ReportQueryService.Line l : rep.expenses().subList(0, Math.min(rep.expenses().size(), half))) out.add(List.of("Expense", l.category(), l.item(), money(l.total())));
        boolean truncated = rep.income().size() > half || rep.expenses().size() > half;
        Map<String, Object> m = table("financial-report", "Financial report for " + r.label + " (" + range(r) + "): income " + money(rep.totalIncome()) + ", expenses " + money(rep.totalExpense()) + ", net " + money(rep.net()) + "." + (truncated ? " Showing the largest groups; open the report for the full breakdown." : ""),
                List.of("Type", "Category", "Fund / Purpose", "Total"), out, (long) (rep.income().size() + rep.expenses().size()), truncated, r, url, label);
        m.put("summary", summary("Income", money(rep.totalIncome()), "Expenses", money(rep.totalExpense()), "Net", money(rep.net())));
        return m;
    }

    /* ════════════════════════ ministry ════════════════════════ */

    private Map<String, Object> meetings(AiAccess a, String cid, LocalDate today, String q) {
        if (!a.meetings()) return denied("meetings");
        int days = has(q, "today") ? 0 : has(q, "this week") ? 7 : 30;
        LocalDate to = today.plusDays(days);
        List<Meeting> all = meetingRepo.findByAppClientIdAndDeleteFlagFalse(cid);
        List<Object[]> occ = new ArrayList<>();                       // [date, meeting]
        List<Integer> ids = new ArrayList<>();
        for (Meeting m : all) {
            if (m.getMeetingDate() == null) continue;
            if (m.getEndDate() != null && m.getEndDate().isBefore(today)) continue;   // expired series (the page's rule)
            for (LocalDate d : MeetingRecurrence.listOccurrences(m, today, maxRows + 1, days + 1)) {
                if (d.isAfter(to)) break;
                occ.add(new Object[] {d, m}); if (!ids.contains(m.getId())) ids.add(m.getId());
            }
        }
        Set<String> skipped = new java.util.HashSet<>();
        if (!ids.isEmpty()) for (MeetingSkipDate s : skipRepo.findByMeetingIdIn(ids)) skipped.add(s.getMeetingId() + "@" + s.getSkipDate());
        occ.removeIf(o -> skipped.contains(((Meeting) o[1]).getId() + "@" + o[0]));
        occ.sort((x, y) -> { int c = ((LocalDate) x[0]).compareTo((LocalDate) y[0]); return c != 0 ? c : str(((Meeting) x[1]).getStartTime()).compareTo(str(((Meeting) y[1]).getStartTime())); });
        String window = days == 0 ? "today" : "the next " + days + " days";
        if (occ.isEmpty()) return ok("meetings", "No meetings are scheduled for " + window + ".", null, "/meetings", "Open Meetings");
        boolean truncated = occ.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        Map<Integer, String> locs = new HashMap<>();
        for (Object[] o : occ.subList(0, Math.min(occ.size(), maxRows))) {
            Meeting m = (Meeting) o[1];
            String loc = locs.computeIfAbsent(m.getId(), k -> meetingService.locationNameFor(m));
            out.add(List.of(date(o[0]), m.getMeetingType() != null ? str(m.getMeetingType().getTypeName()) : "(no type)", time(m.getStartTime(), m.getEndTime()), loc, str(m.getOccurrence())));
        }
        return table("meetings", occ.size() + " meeting" + (occ.size() == 1 ? "" : "s") + " in " + window + (truncated ? ". Showing the first " + maxRows + "." : "."),
                List.of("Date", "Meeting", "Time", "Location", "Occurrence"), out, (long) occ.size(), truncated, null, "/meetings", "Open Meetings");
    }

    private Map<String, Object> prayer(AiAccess a, String cid) {
        if (!a.prayer()) return denied("prayer");
        long total = prayerRepo.aiCountOpen(cid);
        if (total == 0) return ok("prayer", "There are no pending prayer requests.", null, "/prayerRequest", "Open Prayer Requests");
        List<PrayerRequest> rows = prayerRepo.aiFindOpen(cid, PageRequest.of(0, maxRows));
        List<List<String>> out = new ArrayList<>();
        for (PrayerRequest p : rows)   // title + status only — the request text is private and stays on the page
            out.add(List.of(str(p.getTitle()), str(p.getStatus()).isBlank() ? "New" : str(p.getStatus()), p.getCreatedAt() == null ? "" : date(new java.sql.Date(p.getCreatedAt().getTime()).toLocalDate()), p.getAssignedVolunteerId() == null ? "Unassigned" : "Assigned"));
        return table("prayer", total + " pending prayer request" + (total == 1 ? "" : "s") + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Request", "Status", "Received", "Volunteer"), out, total, total > maxRows, null, "/prayerRequest", "Open Prayer Requests");
    }

    private Map<String, Object> followUps(AiAccess a, String cid) {
        if (!a.followUps()) return denied("followups");
        List<String> open = List.of("PENDING", "MISSED");
        long total; List<FollowUp> rows;
        if (a.followUpsAll()) { total = followUpRepo.aiCountByStatuses(cid, open); rows = followUpRepo.aiFindByStatuses(cid, open, PageRequest.of(0, maxRows)); }
        else { total = followUpRepo.aiCountByAssigneeAndStatuses(cid, a.username(), open); rows = followUpRepo.aiFindByAssigneeAndStatuses(cid, a.username(), open, PageRequest.of(0, maxRows)); }
        String scope = a.followUpsAll() ? "" : " assigned to you";
        if (total == 0) return ok("followups", "There are no open follow-up tasks" + scope + ".", null, "/followups", "Open Follow-ups");
        List<List<String>> out = new ArrayList<>();
        for (FollowUp f : rows)
            out.add(List.of(str(f.getTitle()), str(f.getStatus()), str(f.getPriority()), f.getDueDate() == null ? "" : date(new java.sql.Date(f.getDueDate().getTime()).toLocalDate()), str(f.getAssignedTo())));
        return table("followups", total + " open follow-up task" + (total == 1 ? "" : "s") + scope + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Task", "Status", "Priority", "Due", "Assigned to"), out, total, total > maxRows, null, "/followups", "Open Follow-ups");
    }

    private Map<String, Object> assignedVolunteers(AiAccess a, String cid, LocalDate today) {
        if (!a.volunteers()) return denied("volunteers");
        long total = assignmentRepo.aiCountUpcomingActive(cid, today);
        if (total == 0) return ok("volunteers", "There are no upcoming volunteer assignments.", null, "/volunteers", "Open Volunteers");
        List<VolunteerAssignment> rows = assignmentRepo.aiFindUpcomingActive(cid, today, PageRequest.of(0, maxRows));
        Map<Long, String> roles = new HashMap<>(); Map<Integer, String> people = new HashMap<>();
        List<Long> roleIds = new ArrayList<>(); List<Integer> memberIds = new ArrayList<>();
        for (VolunteerAssignment v : rows) { if (v.getRoleId() != null) roleIds.add(v.getRoleId()); if (v.getFamilyMemberId() != null) memberIds.add(v.getFamilyMemberId()); }
        if (!roleIds.isEmpty()) for (VolunteerRole r : volunteerRoleRepo.findByAppClientIdAndIdIn(cid, roleIds)) roles.put(r.getId(), str(r.getRoleName()));
        if (!memberIds.isEmpty()) for (Object[] m : memberRepo.aiMemberNames(cid, memberIds)) people.put(((Number) m[0]).intValue(), name(m[1], m[2]));
        List<List<String>> out = new ArrayList<>();
        for (VolunteerAssignment v : rows)
            out.add(List.of(v.getEventDate() == null ? "" : date(v.getEventDate()), str(v.getEventLabel()), roles.getOrDefault(v.getRoleId(), ""), people.getOrDefault(v.getFamilyMemberId(), ""), str(v.getAssignmentStatus())));
        return table("volunteers", total + " upcoming volunteer assignment" + (total == 1 ? "" : "s") + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Date", "Event", "Role", "Volunteer", "Status"), out, total, total > maxRows, null, "/volunteers", "Open Volunteers");
    }

    private Map<String, Object> events(AiAccess a, String cid, LocalDate today, String q) {
        if (!a.events()) return denied("events");
        int days = has(q, "today") ? 0 : has(q, "this week") ? 7 : has(q, "this month") ? 31 : 90;
        LocalDate to = today.plusDays(days);
        List<Object[]> rows = eventRepo.aiUpcomingEvents(cid, Date.valueOf(today), Date.valueOf(to), maxRows + 1);
        String window = days == 0 ? "today" : "the next " + days + " days";
        if (rows.isEmpty()) return ok("events", "No events are scheduled for " + window + ".", null, "/events", "Open Events");
        boolean truncated = rows.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (Object[] x : rows.subList(0, Math.min(rows.size(), maxRows)))
            out.add(List.of(date(x[3]), str(x[1]), str(x[2]), str(x[4])));
        return table("events", (truncated ? "More than " + maxRows : String.valueOf(rows.size())) + " event" + (rows.size() == 1 ? "" : "s") + " in " + window + (truncated ? ". Showing the first " + maxRows + "." : "."),
                List.of("Date", "Event", "Type", "Start"), out, truncated ? null : (long) rows.size(), truncated, null, "/events", "Open Events");
    }

    private Map<String, Object> reminders(AiAccess a, String cid) {
        if (!a.reminders()) return denied("reminders");
        List<Map<String, Object>> all = reminderService.getAllEventReminders(cid);
        List<Map<String, Object>> live = new ArrayList<>();
        for (Map<String, Object> m : all) if (!Boolean.TRUE.equals(m.get("disabled"))) live.add(m);
        if (live.isEmpty()) return ok("reminders", "There are no active event reminders.", null, "/eventReminders", "Open Reminders");
        boolean truncated = live.size() > maxRows;
        List<List<String>> out = new ArrayList<>();
        for (Map<String, Object> m : live.subList(0, Math.min(live.size(), maxRows))) {
            List<String> when = new ArrayList<>();
            if (Boolean.TRUE.equals(m.get("sameDay"))) when.add("same day");
            if (m.get("beforeDays") != null) when.add(m.get("beforeDays") + " day(s) before");
            if (m.get("afterDays") != null) when.add(m.get("afterDays") + " day(s) after");
            List<String> via = new ArrayList<>();
            if (!Boolean.FALSE.equals(m.get("sendEmail"))) via.add("email");
            if (Boolean.TRUE.equals(m.get("sendSms"))) via.add("SMS");
            if (Boolean.TRUE.equals(m.get("sendWhatsApp"))) via.add("WhatsApp");
            out.add(List.of(m.get("eventName") == null || str(m.get("eventName")).isBlank() ? "All events" : str(m.get("eventName")), String.join(", ", when), String.join(", ", via)));
        }
        return table("reminders", live.size() + " active event reminder" + (live.size() == 1 ? "" : "s") + (truncated ? ". Showing the first " + maxRows + "." : "."),
                List.of("Event", "When", "Channels"), out, (long) live.size(), truncated, null, "/eventReminders", "Open Reminders");
    }

    /* ════════════════════════ people ════════════════════════ */

    /** "Disable Alerts" members: the Family form's per-member switch (there is no family-level flag). */
    static final String UNALERT_WORDS = "un ?alert\\w*|non ?alert\\w*|alerts? (?:are |is )?(?:disabled|off|turned off)"
            + "|disabled alerts?|alerts? disabled|disable alerts?";

    /**
     * Routes "deleted members", "deleted families", "inactive families", "inactive members" and
     * "unalert" (alerts disabled) questions; null when the question is not one of them.
     * Ambiguous wording is answered with a question, never with a guess.
     */
    Map<String, Object> peopleByStatus(String q, AiAccess a, String cid) {
        // How-to questions ("how do I restore a deleted family?") stay with Help / navigation.
        if (has(q, "how (?:do|can|to|should|would)|why|restore|undelete|recover|reactivate|re activate")) return null;
        if (has(q, UNALERT_WORDS)) return unalertMembers(a, cid);
        boolean del   = has(q, "deleted|removed");
        boolean inact = has(q, "in ?active|deactivated|not active");
        if (!del && !inact) return null;
        boolean familyMembers = has(q, "family members?");
        boolean fam = has(q, "famil(?:y|ies)|households?") && !familyMembers;
        boolean mem = familyMembers || has(q, "members?|people|persons?|individuals?");
        if (!fam && !mem) return null;
        if (del && inact) return clarify("status", "Do you want the deleted records or the inactive ones? Please ask for one at a time, "
                + "for example “show deleted families” or “show inactive families”.");
        if (fam && mem) return clarify("status", "Do you mean " + (del ? "deleted" : "inactive") + " families, or "
                + (del ? "deleted" : "inactive") + " members? Please ask for one of them.");
        if (fam) return del ? deletedFamilies(a, cid) : inactiveFamilies(a, cid);
        return del ? deletedMembers(a, cid) : inactiveMembers(a, cid);
    }

    /**
     * Status lists are Families-page records: the /viewfamily page rule (role guard + the
     * admin.family permission, both resolved by AiAccessPolicy). Member-portal sessions are
     * refused even with the Directory tab — the directory never shows deleted or inactive
     * people — and church logins cannot open the Families page at all.
     */
    private static boolean mayListByStatus(AiAccess a) { return a.members() && !a.member() && !a.church(); }

    private Map<String, Object> deletedMembers(AiAccess a, String cid) {
        if (!mayListByStatus(a)) return denied("members.deleted");
        long total = memberRepo.aiCountDeletedMembers(cid);
        if (total == 0) return ok("members.deleted", "There are no deleted members.", null, "/viewfamily", "Open Families");
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : memberRepo.aiDeletedMembers(cid, maxRows))
            out.add(List.of(name(r[0], r[1]), str(r[2]), truthy(r[3]) ? "Family deleted" : "Member deleted"));
        return table("members.deleted", total + " deleted member" + (total == 1 ? "" : "s") + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Member", "Role", "Status"), out, total, total > maxRows, null, "/viewfamily", "Open Families");
    }

    private Map<String, Object> inactiveMembers(AiAccess a, String cid) {
        if (!mayListByStatus(a)) return denied("members.inactive");
        long total = memberRepo.aiCountInactiveMembers(cid);
        if (total == 0) return ok("members.inactive", "There are no inactive members.", null, "/viewfamily", "Open Families");
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : memberRepo.aiInactiveMembers(cid, maxRows))
            out.add(List.of(name(r[0], r[1]), str(r[2]), truthy(r[3]) ? "Family inactive" : "Member inactive"));
        return table("members.inactive", total + " inactive member" + (total == 1 ? "" : "s") + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Member", "Role", "Status"), out, total, total > maxRows, null, "/viewfamily", "Open Families");
    }

    private Map<String, Object> unalertMembers(AiAccess a, String cid) {
        if (!mayListByStatus(a)) return denied("members.unalert");
        long total = memberRepo.aiCountUnalertMembers(cid);
        if (total == 0) return ok("members.unalert", "No members have alerts disabled.", null, "/viewfamily", "Open Families");
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : memberRepo.aiUnalertMembers(cid, maxRows))
            out.add(List.of(name(r[0], r[1]), str(r[2]), truthy(r[3]) ? "Inactive" : "Active"));
        return table("members.unalert", total + " member" + (total == 1 ? " has" : "s have") + " alerts disabled (Disable Alerts is ticked on the family record)"
                + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Member", "Role", "Status"), out, total, total > maxRows, null, "/viewfamily", "Open Families");
    }

    private Map<String, Object> deletedFamilies(AiAccess a, String cid) {
        if (!mayListByStatus(a)) return denied("families.deleted");
        long total = memberRepo.aiCountDeletedFamilies(cid);
        if (total == 0) return ok("families.deleted", "There are no deleted families.", null, "/viewfamily", "Open Families");
        return familyTable("families.deleted", "deleted", total, memberRepo.aiDeletedFamilies(cid, maxRows));
    }

    private Map<String, Object> inactiveFamilies(AiAccess a, String cid) {
        if (!mayListByStatus(a)) return denied("families.inactive");
        long total = memberRepo.aiCountInactiveFamilies(cid);
        if (total == 0) return ok("families.inactive", "There are no inactive families.", null, "/viewfamily", "Open Families");
        return familyTable("families.inactive", "inactive", total, memberRepo.aiInactiveFamilies(cid, maxRows));
    }

    private Map<String, Object> familyTable(String intent, String status, long total, List<Object[]> rows) {
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String nm = str(r[0]);
            out.add(List.of(nm.isBlank() ? "(no members)" : nm, String.valueOf(r[1] == null ? 0 : ((Number) r[1]).longValue())));
        }
        return table(intent, total + " " + status + " famil" + (total == 1 ? "y" : "ies") + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Family (primary member)", "Members"), out, total, total > maxRows, null, "/viewfamily", "Open Families");
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : o instanceof Number n ? n.intValue() != 0 : "true".equalsIgnoreCase(str(o)) || "t".equalsIgnoreCase(str(o));
    }

    private Map<String, Object> memberCounts(AiAccess a, String cid) {
        if (!a.members()) return denied("members");
        long male = 0, female = 0, unspecified = 0, total = 0;
        for (Object[] r : memberRepo.aiCountActiveMembersByGender(cid)) {
            String g = str(r[0]).toLowerCase(Locale.ROOT); long n = ((Number) r[1]).longValue(); total += n;
            if (g.equals("male") || g.equals("m")) male += n; else if (g.equals("female") || g.equals("f")) female += n; else unspecified += n;
        }
        if (total == 0) return ok("members", "There are no active members on record.", null, "/viewfamily", "Open Families");
        String answer = "There are " + total + " active member" + (total == 1 ? "" : "s") + ": " + male + " male, " + female + " female"
                + (unspecified > 0 ? ", and " + unspecified + " with no gender recorded" : "") + ".";
        Map<String, Object> m = ok("members", answer, null, "/viewfamily", "Open Families");
        m.put("summary", summary("Active members", String.valueOf(total), "Male", String.valueOf(male), "Female", String.valueOf(female), "Not specified", String.valueOf(unspecified)));
        return m;
    }

    private Map<String, Object> kidsMinistryChildren(AiAccess a, String cid) {
        if (!a.kidsMinistry()) return denied("kids");
        long total = kmChildRepo.countByClientIdAndDeleteFlagFalseAndInactiveFalse(cid);
        if (total == 0) return ok("kids", "No children are registered in Kids Ministry.", null, "/kidsMinistry", "Open Kids Ministry");
        List<KmChild> kids = kmChildRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(cid, PageRequest.of(0, maxRows));
        Map<Long, String> rooms = new HashMap<>(); List<Long> roomIds = new ArrayList<>();
        for (KmChild k : kids) if (k.getClassroomId() != null) roomIds.add(k.getClassroomId());
        if (!roomIds.isEmpty()) for (KmClassroom c : kmClassroomRepo.findByClientIdAndIdIn(cid, roomIds)) rooms.put(c.getId(), str(c.getClassName()));
        List<List<String>> out = new ArrayList<>();
        for (KmChild k : kids) out.add(List.of(name(k.getFirstName(), k.getLastName()), rooms.getOrDefault(k.getClassroomId(), "")));   // name + classroom only
        return table("kids", total + " child" + (total == 1 ? "" : "ren") + " registered in Kids Ministry" + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Child", "Classroom"), out, total, total > maxRows, null, "/kidsMinistry", "Open Kids Ministry");
    }

    private Map<String, Object> familyChildren(AiAccess a, String cid) {
        if (!a.members()) return denied("children");
        long total = memberRepo.countChildRoleMembers(cid);
        if (total == 0) return ok("children", "No children are listed in the family records.", null, "/viewfamily", "Open Families");
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : memberRepo.aiChildRoleMemberNames(cid, maxRows)) out.add(List.of(name(r[0], r[1])));   // names only
        return table("children", total + " child" + (total == 1 ? "" : "ren") + " listed in the family records" + (total > maxRows ? ". Showing the first " + maxRows + "." : "."),
                List.of("Child"), out, total, total > maxRows, null, "/viewfamily", "Open Families");
    }

    /**
     * The public screens a visitor can be sent to: the pages Public Screens offers, plus the two
     * visitor pages behind the NTag cards. Anything else — the kiosk pre-login link, withdrawn or
     * internal pages — is never listed, even if a row for it exists.
     */
    static final Map<String, String[]> PUBLIC_SCREENS = new LinkedHashMap<>();
    static {
        for (Map<String, String> p : PublicPagePolicy.AVAILABLE_PAGES) PUBLIC_SCREENS.put(p.get("url"), new String[] { p.get("label"), "" });
        PUBLIC_SCREENS.put(PublicPagePolicy.UPCOMING_EVENTS_URL, new String[] { "Upcoming Events", "" });
        PUBLIC_SCREENS.put(PublicPagePolicy.NTAG_LANDING_URL, new String[] { "NTag Landing Page", "" });
        String[][] desc = {
            { PublicPagePolicy.PUBLIC_CALENDAR_URL,    "View the church's public event calendar" },
            { PublicPagePolicy.MEMBERSHIP_FORM_URL,    "Apply for membership online" },
            { PublicPagePolicy.DONATION_PAGE_URL,      "Give to the church online" },
            { PublicPagePolicy.MEMBER_SIGNUP_URL,      "Create a Member Portal account" },
            { PublicPagePolicy.SMS_OPT_IN_URL,         "Sign up for the church's text messages" },
            { PublicPagePolicy.GUESS_IT_URL,           "Join the Guess It game" },
            { PublicPagePolicy.KIDS_CHECKIN_URL,       "Check children in to Kids Ministry" },
            { PublicPagePolicy.CONNECT_URL,            "Visitor connect card" },
            { PublicPagePolicy.PUBLIC_PRAYER_FORM_URL, "Send the church a prayer request" },
            { PublicPagePolicy.UPCOMING_EVENTS_URL,    "See the church's upcoming events" },
            { PublicPagePolicy.NTAG_LANDING_URL,       "The tap-to-connect page behind your NTag cards" } };
        for (String[] d : desc) if (PUBLIC_SCREENS.containsKey(d[0])) PUBLIC_SCREENS.get(d[0])[1] = d[1];
    }

    /** The church's publishing rule (plan, trial/demo, withdrawn pages). Without it nothing is listed. */
    private PublicPagePolicy publicPagePolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPublicPagePolicy(PublicPagePolicy policy) { this.publicPagePolicy = policy; }

    private Map<String, Object> publicLinks(AiAccess a, String cid, LocalDate today) {
        if (!a.publicLinks()) return denied("publiclinks");
        List<PublicScreenLink> all = publicLinkRepo.findByAppClientIdAndRevokedFalseOrderByCreatedDateDesc(cid);
        List<PublicScreenLink> live = new ArrayList<>();
        for (PublicScreenLink l : all) {
            if (l == null || l.isRevoked() || cid == null || !cid.equals(l.getAppClientId())) continue;            // the caller's church only
            if (l.getExpirationDate() != null && l.getExpirationDate().isBefore(today)) continue;                   // expired (church calendar)
            if (l.getToken() == null || l.getToken().isBlank() || l.getPageUrl() == null) continue;                 // no usable public address
            if (!PUBLIC_SCREENS.containsKey(l.getPageUrl())) continue;                                                // not an explicitly public screen
            if (publicPagePolicy == null || !publicPagePolicy.mayPublish(l.getPageUrl(), cid)) continue;             // the page's own rule, as when it is opened
            live.add(l);
        }
        if (live.isEmpty()) return ok("publiclinks", "There are no active public links.", null, "/publicScreens", "Open Public Screens");
        boolean truncated = live.size() > maxRows;
        List<Map<String, String>> links = new ArrayList<>();
        for (PublicScreenLink l : live.subList(0, Math.min(live.size(), maxRows))) {
            String[] screen = PUBLIC_SCREENS.get(l.getPageUrl());
            String label = l.getPageLabel() != null && !l.getPageLabel().isBlank() ? l.getPageLabel().trim() : screen[0];
            Map<String, String> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("description", screen[1]);
            // The address the Public Screens page itself hands out (PublicScreensController.linkPath):
            // the screen's own page with this link's access parameter — never the internal route alone.
            m.put("url", com.churchgeniuspro.controller.PublicScreensController.linkPath(l));
            m.put("expires", l.getExpirationDate() == null ? "No expiry" : date(l.getExpirationDate()));
            links.add(m);
        }
        Map<String, Object> m = ok("publiclinks", live.size() + " active public link" + (live.size() == 1 ? "" : "s")
                + ". Select a name to open the public screen, or open Public Screens to copy, share or revoke a link."
                + (truncated ? " Showing the first " + maxRows + "." : ""), null, "/publicScreens", "Open Public Screens");
        m.put("publicLinks", links);
        m.put("total", (long) live.size());
        m.put("shown", links.size());
        m.put("truncated", truncated);
        return m;
    }

    /* ════════════════════════ helpers ════════════════════════ */

    public DateRange period(String q, LocalDate today) {
        DateRange r = AiDataSearchService.parsePeriod(q, today);
        return r != null ? r : DateRange.month(today, "this month");
    }

    private Map<String, Object> tooWide(DateRange r, String intent) {
        if (r.days() <= maxRangeDays) return null;
        Map<String, Object> m = base("narrow", "That period covers " + r.days() + " days, which is more than this search can total at once ("
                + maxRangeDays + " days). Which date range would you like — for example a month, a quarter, or one year?");
        m.put("period", periodMap(r));
        return m;
    }

    private static Map<String, Object> base(String intent, String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", false); m.put("intent", intent); m.put("answer", answer);
        return m;
    }
    private static Map<String, Object> ok(String intent, String answer, DateRange r, String url, String label) {
        Map<String, Object> m = base(intent, answer);
        if (r != null) m.put("period", periodMap(r));
        m.put("link", Map.of("label", label, "url", url));
        return m;
    }
    private static Map<String, Object> denied(String intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", true); m.put("intent", intent); m.put("answer", AiDataSearchService.DENIED_MSG);
        return m;
    }
    private static Map<String, Object> clarify(String intent, String question) {
        Map<String, Object> m = base("clarify", question); m.put("topic", intent); return m;
    }
    private static Map<String, Object> table(String intent, String answer, List<String> columns, List<List<String>> rows, Long total, boolean truncated, DateRange r, String url, String label) {
        Map<String, Object> m = ok(intent, answer, r, url, label);
        m.put("columns", columns); m.put("rows", rows); m.put("shown", rows.size()); m.put("truncated", truncated);
        if (total != null) m.put("total", total);
        return m;
    }
    private static Map<String, String> summary(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
    private static Map<String, Object> periodMap(DateRange r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", r.label); p.put("from", r.from.toString()); p.put("to", r.to.toString());
        return p;
    }
    private static Object[] first(List<Object[]> rows) { return rows == null || rows.isEmpty() ? null : rows.get(0); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String name(Object first, Object last) { return (str(first) + " " + str(last)).trim(); }
    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }
    private static String money(Object o) { return NumberFormat.getCurrencyInstance(Locale.US).format(o == null ? BigDecimal.ZERO : num(o)); }
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static String date(Object o) {
        if (o == null) return "";
        if (o instanceof LocalDate d) return d.toString();                 // ISO for sortable table cells
        if (o instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate().toString();
        return str(o);
    }
    private static String range(DateRange r) { return r.from.equals(r.to) ? r.from.format(D) : r.from.format(D) + " – " + r.to.format(D); }
    private static String fund(Object sub, Object main) { String s = str(sub), m = str(main); return s.isBlank() ? m : (m.isBlank() || m.equalsIgnoreCase(s) ? s : m + " / " + s); }
    private static String method(Object method, Object importRef) {
        String ref = str(importRef), m = str(method);
        if (ref.endsWith(DonationPostingService.FEE_REF_SUFFIX)) return (m.isBlank() ? "Online" : m) + " (processing fee)";
        if (ref.startsWith("pi_") || ref.startsWith("donation:")) return (m.isBlank() ? "Online" : m) + " (online donation)";
        return m;
    }
    private static String time(String start, String end) { String s = str(start), e = str(end); return s.isBlank() ? "" : (e.isBlank() ? s : s + " – " + e); }
}

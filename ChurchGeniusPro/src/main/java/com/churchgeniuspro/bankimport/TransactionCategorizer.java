package com.churchgeniuspro.bankimport;

/**
 * Heuristic transaction classifier — assigns a {@link BankTxn#category},
 * auto-detects the {@link BankTxn#fund} (General / Missions / Youth / Building)
 * from description patterns, and reports a {@link BankTxn#confidence} score
 * (0–100) reflecting how specific the matched pattern was.
 *
 * <p>This is a transparent, dependency-free rule/keyword engine (no external LLM
 * call), so it runs offline, deterministically, and with no per-request cost. The
 * keyword tables below can be extended over time as a church learns its vendors.
 */
public final class TransactionCategorizer {

    private TransactionCategorizer() {}

    // {pipe-separated keywords, fund, confidence}
    private static final String[][] FUND_RULES = {
        {"mission|missions|missionary|outreach|evangelism|global", "Missions", "90"},
        {"youth|teen|teens|student ministry|young adult|college ministry|vbs|vacation bible", "Youth", "88"},
        {"building|construction|renovation|remodel|facility|facilities|capital campaign|mortgage|roof|hvac|parking lot|sanctuary", "Building", "88"},
    };

    // EXPENSE categories (amount < 0). {keywords, category, confidence}
    private static final String[][] EXPENSE_RULES = {
        {"payroll|salary|salaries|wages|paycheck|gusto|adp|paychex|stipend", "Payroll", "92"},
        {"electric|water|sewer|utility|utilities|pg&e|duke energy|con ed|power company|internet|comcast|at&t|verizon|spectrum|t-mobile|phone bill", "Utilities", "90"},
        {"rent|lease|mortgage|property mgmt|property management", "Rent/Mortgage", "88"},
        {"insurance|geico|allstate|state farm|liability|premium|brotherhood mutual", "Insurance", "88"},
        {"bank fee|service charge|overdraft|nsf|wire fee|atm fee|monthly fee|finance charge", "Bank Fees", "90"},
        {"google|microsoft|zoom|adobe|software|subscription|godaddy|aws|mailchimp|planning center|quickbooks|canva|dropbox|squarespace", "Software/Tech", "85"},
        {"walmart|target|amazon|staples|office depot|costco|sam's club|sams club|dollar general|dollar tree|supply|supplies|office", "Supplies", "82"},
        {"home depot|lowes|lowe's|ace hardware|maintenance|repair|plumb|hvac|janitor|cleaning|lawn|landscap|pest control", "Maintenance", "82"},
        {"shell|chevron|exxon|mobil|bp|fuel|gas station|uber|lyft|delta|united airlines|american airlines|southwest|hotel|marriott|hilton|airbnb|travel|mileage", "Travel/Fuel", "80"},
        {"restaurant|cafe|coffee|starbucks|chick-fil|chick fil|mcdonald|pizza|catering|panera|food|grocery|kroger|publix|meal", "Food/Meals", "78"},
        {"benevolence|assistance|relief|charity|hardship|food bank", "Benevolence", "80"},
        {"print|printing|sign|signs|banner|marketing|advertis|facebook ads|postage|usps|fedex|ups store", "Marketing/Office", "74"},
        {"curriculum|book|books|education|training|conference|seminar|tuition", "Education", "76"},
    };

    // INCOME categories (amount > 0). {keywords, category, confidence}
    private static final String[][] INCOME_RULES = {
        {"tithe|tithes", "Tithes", "92"},
        {"offering|offerings|collection|plate", "Offerings", "88"},
        {"donation|donate|gift|contribution|give|giving|generosity|stripe|paypal|tithe.ly|tithely|pushpay|givelify|venmo", "Donations", "85"},
        {"fundrais|bake sale|auction|banquet|registration|ticket|tickets", "Fundraising", "78"},
        {"grant|foundation", "Grant", "82"},
        {"interest|dividend", "Interest", "85"},
        {"refund|reimburse|reimbursement|return|rebate|credit memo", "Refund", "80"},
    };

    /** Classify a transaction in place (sets category, fund, confidence). */
    public static void categorize(BankTxn t) {
        String d = t.description == null ? "" : t.description.toLowerCase();
        boolean income = t.amount != null && t.amount.signum() > 0;

        // ── Fund (default General) ──
        t.fund = "General";
        int fundConf = 45;
        for (String[] rule : FUND_RULES) {
            if (matchesAny(d, rule[0])) { t.fund = rule[1]; fundConf = Integer.parseInt(rule[2]); break; }
        }

        // ── Category ──
        String[][] rules = income ? INCOME_RULES : EXPENSE_RULES;
        String category = income ? "Income (uncategorized)" : "Uncategorized";
        int catConf = 35;
        for (String[] rule : rules) {
            if (matchesAny(d, rule[0])) { category = rule[1]; catConf = Integer.parseInt(rule[2]); break; }
        }
        t.category = category;

        // ── Overall confidence ──
        // Category certainty is primary; a strong, specific fund match nudges it up.
        int conf = catConf;
        if (fundConf >= 80 && catConf >= 70) conf = Math.min(98, catConf + 3);
        if (d.isBlank()) conf = Math.min(conf, 25);
        t.confidence = conf;
    }

    private static boolean matchesAny(String text, String pipeKeywords) {
        for (String kw : pipeKeywords.split("\\|")) {
            kw = kw.trim();
            if (!kw.isEmpty() && text.contains(kw)) return true;
        }
        return false;
    }
}

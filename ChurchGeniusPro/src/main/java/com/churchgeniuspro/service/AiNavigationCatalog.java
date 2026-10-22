package com.churchgeniuspro.service;

import java.util.List;
import java.util.Set;

/**
 * The one server-side catalogue of pages the AI Search may link to, each with the rule its
 * route applies (read from the page controllers, 2026-10-21). {@link AiAccessPolicy}
 * evaluates the rule for the session; the plan feature comes from
 * {@code SubscriptionFeatureCatalog.keyForPath(url)}, the same lookup the subscription
 * filter uses, so the catalogue never has to repeat it.
 *
 * <p>A page the current login cannot open is still described (the feature exists), but no
 * link is offered — the standing rule for the assistant.
 */
public final class AiNavigationCatalog {

    /** Who may open a page: the route's role guard, its page key, and member-portal / opt-in rules. */
    public record Rule(Set<String> roles, boolean church, String pageKey, String memberKey, String optInKey) {}

    public record Page(String label, String url, String category, String description, List<String> keywords, Rule rule) {}

    private static final Set<String> ADMIN            = Set.of("SuperAdmin", "Admin");
    private static final Set<String> ADMIN_OR_USER    = Set.of("SuperAdmin", "Admin", "User");
    private static final Set<String> ACCT_OR_ADMIN    = Set.of("SuperAdmin", "Admin", "Accountant");
    private static final Set<String> STAFF            = Set.of("SuperAdmin", "Admin", "Accountant", "User");
    private static final Set<String> NONE             = Set.of();

    private static Rule staff(Set<String> roles, String key) { return new Rule(roles, false, key, null, null); }
    private static Rule staffOrChurch(Set<String> roles, String key) { return new Rule(roles, true, key, null, null); }
    private static Rule churchOnly() { return new Rule(NONE, true, null, null, null); }
    private static Rule optIn(String key) { return new Rule(STAFF, true, null, null, key); }
    private static Rule memberTab(String memberKey) { return new Rule(NONE, false, null, memberKey, null); }

    private static Page pg(String label, String url, String cat, String desc, String keys, Rule rule) {
        return new Page(label, url, cat, desc, List.of(keys.split("\\s*,\\s*")), rule);
    }

    public static final List<Page> PAGES = List.of(
        // ── Member portal tabs (Member sessions only) ──
        pg("My Family",      "/memberHome?tab=family",       "Profile",  "Your own family record in the Member Portal.", "my family,family", memberTab("member.family")),
        pg("Contributions",  "/memberHome?tab=give",         "Profile",  "Member Portal Give / Contributions and giving history.", "contributions,my contributions,giving", memberTab("member.contributions")),
        pg("Groups",         "/memberHome?tab=groups",       "Member",   "The groups you belong to in the Member Portal.", "my groups", memberTab("member.groups")),
        pg("Classes",        "/memberHome?tab=sundaySchool", "Member",   "Sunday School classes with lessons and exams.", "classes,my classes", memberTab("member.classes")),
        pg("Volunteer",      "/memberHome?tab=volunteer",    "Member",   "Volunteer sign-ups and assignments in the Member Portal.", "volunteering,my volunteer", memberTab("member.volunteer")),
        pg("Directory",      "/memberHome?tab=directory",    "Member",   "The member directory (respects each member's privacy settings).", "directory,member directory", memberTab("member.directory")),
        // ── Admin ──
        pg("Families",           "/viewfamily",          "Admin",  "The church directory of families and members: add, edit, photos, privacy, printable membership form.", "families,members,people,household,view families,family records", staff(ACCT_OR_ADMIN, "admin.family")),
        pg("Groups",             "/groups",              "Admin",  "Create ministry groups, add members, email a whole group.", "groups,admin groups,small groups,ministry groups", staff(ADMIN, "admin.groups")),
        pg("Membership Requests","/membershipRequests",  "Admin",  "Applications from the public Membership Request page, reviewed before adding or renewing a family.", "membership requests,membership request,applications", staffOrChurch(ADMIN, "admin.membership")),
        pg("Unsubscribed List",  "/unsubscribed-list",   "Admin",  "People who unsubscribed from church emails.", "unsubscribed,unsubscribed list,opt out", staff(ADMIN, "admin.unsubscribed")),
        pg("Users",              "/viewusers",           "Admin",  "Staff logins, roles and the permission checkboxes that control which pages each login sees.", "users,view users,user management,manage users,permissions,staff logins", churchOnly()),
        pg("Files & Notes",      "/filesUpload",         "Admin",  "Upload files and keep notes for the church office.", "files,files and notes,notes,upload file", staff(STAFF, "admin.files")),
        pg("Stripe Integration", "/stripeIntegration",   "Admin",  "Connect the church's own Stripe account for online giving.", "stripe,stripe integration,online giving setup", churchOnly()),
        pg("WhatsApp Integration","/whatsappIntegration","Admin",  "Connect a WhatsApp sender for member messaging.", "whatsapp,whatsapp integration", churchOnly()),
        pg("Private Page Access","/private-access-settings","Admin","Restrict Kids Ministry, Event Check-in and Temporary Login to the church's own network.", "private page access,private pages,private access,private page", staff(ADMIN, null)),
        pg("NTag Landing Page",  "/ntagLandingAdmin",    "Admin",  "The NTag (NFC tap) landing page: enable it, choose its buttons and preview it.", "ntag,ntag page,ntag landing,nfc landing,nfc page", staffOrChurch(ADMIN, null)),
        pg("NTAG Login",         "/ntagAccess",          "Admin",  "Temporary NTAG (NFC) login credentials.", "ntag login,nfc login,ntag credentials", churchOnly()),
        pg("Temporary Access",   "/temporaryAccess",     "Admin",  "Time-limited logins for helpers with printable badges.", "temporary access,temporary login,access card,badge", churchOnly()),
        pg("Daily Bible Verse",  "/dailyVerseSetup",     "Admin",  "Set up the daily Bible verse shown on the home pages.", "daily verse,daily bible verse,bible verse,promise verse,verse of the day", staff(ADMIN, null)),
        pg("Meeting Types",      "/meetingtype",         "Admin",  "Configure meeting categories.", "meeting types,meeting type,meeting categories", staff(ADMIN, null)),
        // ── Finance ──
        pg("Income",         "/income",          "Finance", "Record contributions and other income; recurring income; voice entry.", "income,record income,contribution entry", staff(ACCT_OR_ADMIN, "accounting.income")),
        pg("Expense",        "/expense",         "Finance", "Record expenses by category and fund; recurring expenses; voice entry.", "expense,expenses,record expense", staff(ACCT_OR_ADMIN, "accounting.expense")),
        pg("Bank Import",    "/bank-import",     "Finance", "Import a downloaded bank statement and review many lines at once.", "bank import,import bank,bank statement", staff(ACCT_OR_ADMIN, "accounting.bankimport")),
        pg("Bank Sync",      "/bankSync",        "Finance", "Connect bank accounts (Plaid) and pull in new transactions.", "bank sync,connect bank,plaid", staff(STAFF, null)),
        pg("Pledges",        "/pledges",         "Finance", "Pledge campaigns and each member's pledge progress.", "pledges,pledge,campaigns,pledge campaign", staff(ACCT_OR_ADMIN, "accounting.pledges")),
        pg("Donation Review","/donation-review", "Finance", "Review online donations and member contributions (Stripe), see fees, post to accounting.", "donation review,donations,online giving,donation give", staff(ACCT_OR_ADMIN, "accounting.donation")),
        pg("Funds",          "/fund",            "Finance", "Add or edit income funds / sources (e.g. Tithe, Building Fund).", "fund,funds,new fund,add fund,source,sources", staff(ACCT_OR_ADMIN, "accounting.settings")),
        pg("Expense Categories","/purpose",      "Finance", "Add or edit expense categories (purposes) used when recording an expense.", "purpose,purposes,expense category,expense categories,new expense category,add expense category,expense type,add a new expense,new expense,add new expense", staff(ACCT_OR_ADMIN, "accounting.settings")),
        pg("Transaction Types","/transactiontype","Finance","Add or edit payment / transaction methods (Cash, Check, Card, Bank Transfer…).", "transaction type,transaction types,payment method,payment methods,transaction method,method,methods,new method,add method", staff(ACCT_OR_ADMIN, "accounting.settings")),
        pg("Payroll",        "/payroll",         "Payroll", "Employee payroll: pay runs with deductions and withholding.", "payroll,pay run,employees payroll", staffOrChurch(ACCT_OR_ADMIN, "accounting.payroll")),
        // ── Reports ──
        pg("Accounting Reports","/accountingReports","Reports","Reports hub: income, expense, transaction, tax and financial reports.", "reports,accounting reports,report", staff(ACCT_OR_ADMIN, "accounting.reports")),
        pg("Income Report",     "/income-report",   "Reports", "Income by period, fund and contributor.", "income report,giving report,contributions report", staff(ACCT_OR_ADMIN, "accounting.reports")),
        pg("Expense Report",    "/expense-report",  "Reports", "Expenses by period and category.", "expense report,spending report", staff(ACCT_OR_ADMIN, "accounting.reports")),
        pg("Transactions Report","/transactions-report","Reports","Every income and expense transaction for a period.", "transaction report,transactions report,ledger", staff(ACCT_OR_ADMIN, "accounting.reports")),
        pg("Tax Report",        "/tax-report",      "Reports", "Year-end giving statements per contributor, with an editable letter.", "tax report,tax statements,giving statements,year end statements", staff(ACCT_OR_ADMIN, "accounting.reports")),
        pg("Financial Report",  "/financial-report","Reports", "Church financial summary for a year: income by fund, expenses by purpose, net.", "financial report,financial statement,annual financial", staff(ACCT_OR_ADMIN, "accounting.reports")),
        // ── Ministry ──
        pg("Meetings",        "/meetings",        "Ministry", "Schedule meetings, notify attendees, keep history.", "meetings,meeting,schedule a meeting", new Rule(STAFF, false, "general.meetings", null, null)),
        pg("Events",          "/events",          "Ministry", "Church events with registration, check-in, reminders and volunteers.", "events,event,church events", staff(STAFF, "general.events")),
        pg("Event Calendar",  "/eventcalendar",   "Ministry", "The full event calendar.", "event calendar,calendar,agenda", staff(ADMIN_OR_USER, "general.calendar")),
        pg("Ministry",        "/ministry",        "Ministry", "Ministry hub: Kids Ministry and Sunday School.", "ministry,sunday school,sunday school class", staff(ADMIN_OR_USER, "general.ministry")),
        pg("Kids Ministry",   "/kidsMinistry",    "Ministry", "Children's registration, check-in and check-out with pickup alerts.", "kids ministry,children ministry,kids check in setup", staff(ADMIN_OR_USER, "general.ministry.kids")),
        pg("Worship Planning","/worshipPlanning", "Ministry", "Worship groups, instruments, songs and assignments.", "worship planning,worship,worship assignments", staff(ADMIN_OR_USER, "general.ministry.worship")),
        pg("Prayer Requests", "/prayerRequest",   "Ministry", "Prayer requests and follow-up, prayer volunteers.", "prayer,prayers,prayer requests,prayer ministry", staff(ADMIN_OR_USER, "general.ministry.prayer")),
        pg("Attendance",      "/attendance",      "Ministry", "Record and report attendance for services, classes and meetings.", "attendance,take attendance", staff(ADMIN_OR_USER, "general.attendance")),
        pg("Volunteers",      "/volunteers",      "Ministry", "Volunteer roles and assignments across events.", "volunteers,volunteer roles,volunteer assignments", staff(ADMIN_OR_USER, "general.volunteers")),
        // ── Communications ──
        pg("Email",           "/notifyEmail",     "Communications", "Compose and send email to members, groups or lists.", "email,compose email,send email,notify email,bulk email", staff(ADMIN_OR_USER, "general.email")),
        pg("Email Settings (sender name)", "/notifyEmail#emailSettings", "Communications", "The Email Settings panel on the Email page: display name shown as the sender, reply-to and signature.", "email display name,display name,sender name,email sender,set the email,email settings", staff(ADMIN_OR_USER, "general.email")),
        pg("Email Settings page","/emailSettings","Communications", "Church email settings; on Trial/Demo accounts the verified test email address.", "email settings page,test email,verified email", staff(ADMIN, "general.emailsettings")),
        pg("Reminders",       "/reminders",       "Communications", "Automatic reminders: event, periodic and one-time.", "reminders,reminder", staff(ADMIN_OR_USER, "general.reminders")),
        pg("Event Reminders", "/eventReminders",  "Communications", "Reminder emails/SMS before, on and after an event.", "event reminders,event reminder", staff(ADMIN_OR_USER, "general.reminders")),
        pg("Periodic Reminders","/autoReminders", "Communications", "Recurring reminders on a schedule.", "periodic reminders,recurring reminders,auto reminders", staff(ADMIN_OR_USER, "general.reminders")),
        pg("One-Time Reminders","/oneReminders",  "Communications", "A single reminder on a chosen date.", "one time reminders,one-time reminders", staff(ADMIN_OR_USER, "general.reminders")),
        // ── More ──
        pg("Certificates",    "/certificates",    "More", "Generate and print certificates.", "certificates,certificate", staff(ADMIN_OR_USER, "more.certificates")),
        pg("Public Screens",  "/publicScreens",   "More", "Public links for your church: calendar, prayer requests, kids check-in, membership form, signup, donation page.", "public screens,public screen,public links,public link", staff(ADMIN_OR_USER, "more.publicscreens")),
        pg("Follow-Ups",      "/followups",       "More", "Follow-up tasks for visitors and members.", "follow ups,follow-ups,followups,follow up,tasks", staff(ADMIN_OR_USER, "more.followups")),
        pg("Help Center",     "/helpCenter",      "More", "Help articles and the Help Assistant.", "help center,help centre,help,how does ai search work", staff(STAFF, "more.helpcenter")),
        pg("Song Book",       "/songbook",        "More", "Upload and organise songs; public Song Book page.", "song book,songbook,songs", new Rule(STAFF, false, null, null, null)),
        pg("Guess It",        "/guessIt",         "Activity", "Activity Corner quiz game members join from their phones.", "guess it,activity corner,quiz game", staff(ADMIN_OR_USER, null)),
        pg("Ticketing",       "/tickets",         "Support", "Support tickets to the ChurchGeniusPro team.", "ticketing,ticket,tickets,support ticket,contact support", optIn("more.ticketing")),
        pg("AI Assistant",    "/ai-assistant",    "Support", "Chat with the assistant: church data, pages, how-to help; Type, Voice and Converse.", "ai assistant,assistant page,ai chat,ai search", optIn("more.aiassistant"))
    );

    private AiNavigationCatalog() {}
}

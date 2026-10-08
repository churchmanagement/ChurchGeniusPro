package com.churchgeniuspro.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.churchgeniuspro.service.HelpArticle.list;

/**
 * Single source of truth for Help Center articles. Each article declares the
 * permission key (or admin-only flag) used to gate visibility, so the article
 * list, search, and the AI assistant all share one permission-scoped knowledge
 * base. Adding documentation for a new feature = adding an entry here; the Help
 * Center and assistant pick it up automatically.
 *
 * <p>Version history is captured per-article in {@code changelog}.
 */
@Component
public class HelpContentCatalog {

    private final List<HelpArticle> articles = new ArrayList<>();

    public HelpContentCatalog() { build(); }

    public List<HelpArticle> all() { return articles; }

    public HelpArticle byId(String id) {
        if (id == null) return null;
        for (HelpArticle a : articles) if (a.id.equals(id)) return a;
        return null;
    }

    private void add(HelpArticle a) { articles.add(a); }

    private void build() {
        // ── Members & Groups ──
        add(new HelpArticle("members", "Members", "Managing Members & Families", "admin.family", false,
            "Add, edit, and organize the people and families in your church directory.",
            "The Members module is your church directory. People are grouped into families; each family has one head and optional additional members. You can store contact details, birthdays/anniversaries, photos, and mark fields as private so they are redacted from member-facing views.",
            list("Open Members from the side menu.",
                 "Click Add Family, enter the head of household, then add additional members.",
                 "Use the privacy checkboxes to hide a phone, email, or address from member-facing screens.",
                 "Use Import to populate a family from a scanned/uploaded membership form."),
            list("Problem: a member is missing — Fix: clear the search box and check the Show Inactive / Show Deleted filters.",
                 "Problem: can't edit — Fix: editing requires the admin.family.edit permission; ask an administrator."),
            "1.2", list("v1.2 — Added nickname display and per-field privacy redaction.", "v1.1 — Membership-form scan import.", "v1.0 — Initial release."),
            list("member","family","directory","add member","household")));

        add(new HelpArticle("groups", "Groups", "Creating & Managing Groups", "admin.groups", false,
            "Organize members into ministry groups for communication and reporting.",
            "Groups let you cluster members (e.g. choir, ushers, small groups). You can add existing members, email a whole group, and track membership.",
            list("Open Groups.", "Type a group name and click Add Group.", "Open a group and use Add to Group to add members.", "Use Email Group to message everyone in it."),
            list("Problem: a member can't be added twice — Fix: they are already in the group; check the member list."),
            "1.0", list("v1.0 — Initial release."), list("group","ministry","small group","add to group")));

        // ── Events & Attendance ──
        add(new HelpArticle("create_event", "Events", "Creating an Event", "general.events", false,
            "Create events, collect RSVPs, and check attendees in.",
            "Events support registration, a registration code/ID, email confirmation templates, and an on-site Check-In screen with QR scanning.",
            list("Open Events and click Add Event.",
                 "Enter name, date, time and location, then Save.",
                 "Share the public registration link from Public Screens if you want online RSVPs.",
                 "On the event day, open Check-In to scan or search attendees in."),
            list("Problem: an attendee can't check in — Fix: confirm they registered for THIS event and that you opened Check-In for the correct event; you can also search by name and check in manually.",
                 "Problem: confirmation emails not arriving — Fix: verify the event email template is set and email settings are configured; check the recipient's spam folder."),
            "1.3", list("v1.3 — QR check-in, registration codes, upcoming/past tabs.", "v1.1 — RSVP confirmation email templates.", "v1.0 — Initial release."),
            list("event","create event","rsvp","registration","check in","check-in")));

        add(new HelpArticle("attendance", "Events", "Recording Attendance", "general.attendance", false,
            "Track attendance for services, classes, ministries, and events with reports.",
            "The Attendance module records who attended and produces member, family, ministry, event, visitor, and trend reports.",
            list("Open Attendance.", "Pick the date and group/service.", "Mark members present, then Save.", "Use the Reports tab for trends and exports."),
            list("Problem: a person is not in the list — Fix: they may be inactive; enable inactive members or add them in Members first."),
            "1.0", list("v1.0 — Initial release with dashboard, check-in, records, export and reports."),
            list("attendance","present","service","report","trends")));

        // ── Meetings ──
        add(new HelpArticle("create_meeting", "Meetings", "Creating a Meeting", "general.meetings", false,
            "Schedule meetings, manage locations, and send templated invitations.",
            "Meetings let you record meeting details (type, date, time, location) and send message templates with placeholders for dates, times, and addresses. You can type details, dictate them by voice, or use Converse to fill the form conversationally.",
            list("Open Meetings and click to add a meeting.",
                 "Enter the meeting type, date, time and location.",
                 "Pick a message template (or create one) and preview the rendered message.",
                 "Save, then notify attendees."),
            list("Problem: location won't match by voice — Fix: make sure the location exists in your locations list; you can also pick it from the options the assistant offers.",
                 "Problem: a template placeholder isn't filled — Fix: confirm the meeting has that field set (e.g. address) before sending."),
            "1.2", list("v1.2 — Voice/Converse dictation into the meeting form.", "v1.1 — Message templates with placeholders + live preview.", "v1.0 — Initial release."),
            list("meeting","create meeting","template","invite","location")));

        // ── Kids Ministry ──
        add(new HelpArticle("check_in_child", "Kids Ministry", "Checking In & Out a Child", "general.ministry", false,
            "Securely check children into and out of Kids Ministry with labels and pickup verification.",
            "Kids Ministry supports registration, check-in with printed labels/barcodes, secure checkout (code, scan, or camera) verified against the authorized guardian, paper-photo capture, pickup windows, and overdue-pickup alerts.",
            list("Open Kids Ministry and go to Check-In.",
                 "Search the child or scan their barcode and confirm the guardian.",
                 "Print the label/badge.",
                 "At pickup, open Checkout, verify the code or scan, and confirm the guardian before releasing the child."),
            list("Problem: a child can't be checked in — Fix: make sure the child is registered and active, and that you have the general.ministry permission; re-scan the barcode if it doesn't read.",
                 "Problem: checkout is blocked — Fix: the pickup code/guardian must match the registration; an administrator can update the authorized guardians.",
                 "Problem: overdue alerts not arriving — Fix: confirm alert recipients are configured and the per-child alert toggle is on."),
            "1.4", list("v1.4 — Pickup windows + overdue alerts + per-child recipients.", "v1.3 — Secure checkout (code/scan/camera) + paper photo.", "v1.2 — Check-in history + active list.", "v1.0 — Initial release."),
            list("kids","child","check in","checkout","pickup","childcare","nursery")));

        // ── Volunteers ──
        add(new HelpArticle("assign_volunteers", "Volunteers", "Adding & Assigning Volunteers", "general.volunteers", false,
            "Add volunteers, assign roles, and schedule them to serve.",
            "Volunteers are members with a volunteer profile. You can add an existing member as a volunteer or create one manually, assign roles, and assign them to follow-ups and tasks.",
            list("Open Volunteers.",
                 "Add an existing member as a volunteer, or use Add Manual Volunteer for someone new.",
                 "Assign roles and availability.",
                 "Assign volunteers to follow-ups from the Follow-Ups page's Assigned To field."),
            list("Problem: 'Already a volunteer' — Fix: the member already has a profile; find them in the volunteers list.",
                 "Problem: can't add a volunteer — Fix: volunteer management requires admin access."),
            "1.1", list("v1.1 — Inline Add New Volunteer from the Follow-Ups assignee field.", "v1.0 — Initial release."),
            list("volunteer","assign volunteer","serve","roster","ministry team")));

        // ── Follow-Ups ──
        add(new HelpArticle("followups", "Follow-Ups", "Using the Follow-Up Module", "more.followups", false,
            "Track and assign follow-up tasks for visitors, members, and prayer requests.",
            "The Follow-Up module records tasks linked to a person or record, with a title, priority, due date, status, and an assignee. The Assigned To field is a searchable dropdown of members and volunteers; you can add a new volunteer inline. Public Connect and Prayer submissions create follow-ups automatically.",
            list("Open Follow-Ups.",
                 "Click New Follow-Up (or Edit an existing one).",
                 "Search the Assigned To field by name, email, or phone and pick a member/volunteer — or use Add New Volunteer.",
                 "Set priority and due date, then Save. Mark Complete when done."),
            list("Problem: a person isn't in the Assigned To list — Fix: turn on Show Inactive, or use Add New Volunteer to create them.",
                 "Problem: a follow-up I created isn't visible — Fix: non-admin users only see follow-ups assigned to them; ask an administrator to view all."),
            "1.1", list("v1.1 — Searchable members+volunteers assignee dropdown with inline Add New Volunteer.", "v1.0 — Initial release."),
            list("follow up","follow-up","assign","task","visitor follow up")));

        // ── Prayer ──
        add(new HelpArticle("prayer", "Prayer", "The Prayer Request Module", "general.ministry", false,
            "Collect, assign, and follow up on prayer requests — including public submissions.",
            "Prayer Requests can be entered internally or submitted by the public from a branded page or the NTAG landing page. Public requests are managed separately: you can filter by source (Public/NTAG/Website), assign a prayer volunteer, track status (New → Assigned → In Progress → Prayed For → Followed Up → Closed), and log prayer activity, contact attempts, and outcomes.",
            list("Open Prayer Requests.",
                 "Use the Public Requests view to see submissions from the website/NTAG.",
                 "Assign a volunteer and set the status.",
                 "Add notes for prayer activity and follow-up outcomes."),
            list("Problem: a scheduled prayer reminder wasn't delivered — Fix: confirm a prayer schedule row exists and is enabled, that recipients are set, and that email is configured; schedules run in the church's America/Chicago time.",
                 "Problem: public requests don't appear — Fix: check the source filter and that the public link points to your church."),
            "1.2", list("v1.2 — Separate public prayer management, sources, volunteer assignment, activity log.", "v1.1 — Prayer scheduler reminders.", "v1.0 — Initial release."),
            list("prayer","prayer request","praise report","prayer team","intercession")));

        // ── Reminders ──
        add(new HelpArticle("reminders", "Reminders", "Automated Reminders & Schedules", "general.reminders", false,
            "Send automated birthday, anniversary, event, holiday, and celebrant reminders by email/SMS.",
            "Reminders run on a schedule and use customizable email/SMS templates. Recipients can include members, visitors, and groups. Dates are computed in the church's local time zone (America/Chicago).",
            list("Open Reminders.", "Choose the reminder type and edit its email/SMS template.", "Set recipients and the send time.", "Save; the scheduler sends automatically."),
            list("Problem: emails were not sent — Fix: confirm the schedule is enabled with a valid day/time, recipients are selected, and email settings are valid; check server logs for the run.",
                 "Problem: wrong send time — Fix: times are evaluated in America/Chicago, not the viewer's local zone."),
            "1.3", list("v1.3 — Holiday + members-only celebrant types with custom templates.", "v1.1 — Visitors + group recipients.", "v1.0 — Initial release."),
            list("reminder","schedule","birthday","anniversary","email blast","sms")));

        // ── Accounting ──
        add(new HelpArticle("income", "Accounting", "Recording Income & Contributions", "accounting.income", false,
            "Record contributions and income, optionally by scanning a check.",
            "Income entries capture contributor, amount, fund, method, date, and notes. You can dictate entries by voice or scan a check photo to auto-fill fields.",
            list("Open Income and click Add.", "Select or add the contributor, enter the amount and fund.", "Optionally use Scan Check to auto-fill from a photo.", "Save."),
            list("Problem: scan didn't read the amount — Fix: retake the photo in good light, flat and in focus; you can also type the values.",
                 "Problem: contributor not found — Fix: use New Contributor to add them."),
            "1.2", list("v1.2 — Check scan (OCR/vision) + voice entry.", "v1.0 — Initial release."),
            list("income","contribution","giving","tithe","offering","check")));

        add(new HelpArticle("expense", "Accounting", "Recording Expenses", "accounting.expense", false,
            "Record church expenses, optionally scanning a check or receipt.",
            "Expense entries capture payee, amount, fund/category, method, date, and notes, with optional check/receipt scanning and voice entry.",
            list("Open Expense and click Add.", "Enter payee, amount and category.", "Optionally scan the check/receipt.", "Save."),
            list("Problem: category missing — Fix: add it under Account Settings.", "Problem: can't edit — Fix: requires the accounting.expense.edit permission."),
            "1.1", list("v1.1 — Check/receipt scan + voice entry.", "v1.0 — Initial release."),
            list("expense","spending","payee","receipt","bill")));

        add(new HelpArticle("bank_import", "Accounting", "Importing Bank Statements", "accounting.bankimport", false,
            "Import bank transactions from CSV/OFX/QFX or a scanned statement and categorize them.",
            "Bank Import parses statements, auto-categorizes transactions with a confidence score, and lets you review and save them as income/expense.",
            list("Open Bank Import.", "Upload a CSV/OFX/QFX file or scan a statement photo/PDF.", "Review the suggested category/fund and confidence.", "Edit as needed and Save."),
            list("Problem: duplicates — Fix: the importer warns on likely duplicates; skip those rows.",
                 "Problem: a row won't parse — Fix: check the statement format; image scans depend on photo quality."),
            "1.1", list("v1.1 — Photo/PDF scan with OCR + duplicate warnings.", "v1.0 — CSV/OFX/QFX import + categorizer."),
            list("bank","import","statement","csv","ofx","reconcile")));

        add(new HelpArticle("reports", "Accounting", "Financial Reports", "accounting.reports", false,
            "Generate income, expense, date-range, tax, and financial summary reports.",
            "Reports summarize giving and spending by fund, contributor, and period, and produce tax statements.",
            list("Open Reports.", "Choose the report type and date range.", "Filter by fund/contributor.", "Export or print."),
            list("Problem: totals look off — Fix: confirm the date range and that entries are assigned to the right fund."),
            "1.0", list("v1.0 — Initial release."), list("report","financial","tax statement","date range","summary")));

        add(new HelpArticle("payroll", "Accounting", "Running Payroll", "accounting.payroll", false,
            "Set up employees, run payroll, and produce pay stubs and W-2 summaries.",
            "Payroll computes federal/FICA taxes from configurable tables, produces branded pay stubs (PDF) and W-2 summaries, and keeps an audit log and pay history.",
            list("Open Payroll under Accounting.", "Set up employees and pay details.", "Start a run, enter hours, and process.", "Download pay stubs; review YTD and reports."),
            list("Problem: an employee can't be paid — Fix: complete their pay setup; the run skips un-payable employees.",
                 "Problem: access denied — Fix: Payroll requires the accounting.payroll permission."),
            "1.0", list("v1.0 — Initial release with engine, pay stubs, W-2, reports, RBAC."),
            list("payroll","employee","pay stub","w-2","wages","taxes")));

        // ── NTAG (administrative) ──
        add(new HelpArticle("ntag_login", "NTAG", "NTAG Temporary Login (NFC)", null, true,
            "Let staff sign in by tapping an NTAG tag, with PIN and emailed one-time code.",
            "NTAG login is a three-factor temporary sign-in: tap the NFC tag (or type its ID), enter the 6-digit PIN, then the emailed OTP. Tags are registered per user in the NTAG admin page with an optional validity window and allowed pages.",
            list("Open the NTAG admin page and register a tag (tap the tag to capture its serial).",
                 "Set the holder, email, PIN, and Allowed Access Pages.",
                 "On the login screen choose Login with NTAG, tap the tag, then enter PIN and the emailed code."),
            list("Problem: 'This NTAG is not recognized' — Fix: the scanned tag's serial must exactly match a registered, enabled credential. Tap the tag to see the serial it read and confirm it is registered (register by tapping, not typing, to capture the true hardware ID).",
                 "Problem: no email code arrives — Fix: confirm the holder's email is correct and email is configured; check spam.",
                 "Problem: tag works but access is limited — Fix: the user only sees pages in their Allowed Access Pages list."),
            "1.1", list("v1.1 — Show scanned serial on 'not recognized' for self-diagnosis.", "v1.0 — Initial 3-factor NTAG login."),
            list("ntag","nfc","tag login","temporary login","pin","otp")));

        add(new HelpArticle("ntag_allowed_pages", "NTAG", "NTAG Allowed Access Pages", null, true,
            "Restrict an NTAG user to only the pages you select.",
            "Each NTAG credential has an Allowed Access Pages list. The user sees only those pages in the menu and lands on a permitted page; any other page is blocked with an Access Denied message, even via a direct URL.",
            list("Open the NTAG admin page and edit a credential.",
                 "Check the pages the user may access (e.g. Meetings, Kids Ministry, Volunteers).",
                 "Save. The user must log in again to pick up changes."),
            list("Problem: the user still sees other pages — Fix: have them log out and back in; enforcement applies to new NTAG sessions.",
                 "Problem: the user sees Access Denied everywhere — Fix: no pages are selected; choose at least one allowed page."),
            "1.0", list("v1.0 — Strict frontend + backend allowed-pages enforcement."),
            list("ntag","allowed pages","restrict","access","permissions")));

        add(new HelpArticle("ntag_landing", "NTAG", "NTAG Landing Page & Buttons", null, true,
            "Customize the branded page visitors see when they tap your NTAG tag.",
            "The NTAG landing page shows your logo, colors, welcome message, and buttons (main actions + footer social links). The Connect With Us and Prayer Request buttons open public forms. Add, edit, reorder, enable/disable, and save buttons; analytics track views and clicks.",
            list("Open the NTAG Landing Page admin.",
                 "Set branding (color, logo, welcome message, optional banner).",
                 "Add buttons; a confirmation toast appears when a button is added (it goes to the bottom).",
                 "Save & Publish, then write the public link onto your tags."),
            list("Problem: a button doesn't show on the public page — Fix: it must be enabled and have a non-empty URL.",
                 "Problem: edits don't appear — Fix: click Save & Publish, then refresh the preview."),
            "1.2", list("v1.2 — Add-button confirmation toast + refreshed styling.", "v1.1 — Footer social links + Connect/Prayer buttons.", "v1.0 — Initial landing page."),
            list("ntag","landing page","buttons","branding","tap")));

        add(new HelpArticle("public_engagement", "Public", "Connect With Us & Public Prayer", null, true,
            "Public forms for new-visitor connection cards and prayer requests.",
            "Connect With Us collects a visitor's details and contact preferences and creates a Visitor record plus an automatic follow-up. Public Prayer collects a prayer request (with optional share-with-team) and creates a public prayer record plus a follow-up. Both are branded and generatable from Public Screens or linked from the NTAG landing page.",
            list("Generate the Connect or Prayer link from Public Screens (or use the NTAG landing buttons).",
                 "Share/print the link or tag.",
                 "Submissions appear under Members (visitors) and the Public Prayer management view, each with an auto follow-up."),
            list("Problem: submit fails — Fix: confirm the link's church token is valid; required fields must be filled.",
                 "Problem: a submission isn't found — Fix: visitors appear in Members; prayer requests appear in Public Prayer Requests."),
            "1.0", list("v1.0 — Connect + Public Prayer forms with auto follow-up and management."),
            list("connect","public prayer","visitor","connection card","public form")));

        // ── Public Screens & Certificates ──
        add(new HelpArticle("public_screens", "Public", "Public Screens & Links", "more.publicscreens", false,
            "Generate shareable public links for events, forms, donations, and more.",
            "Public Screens generates tokenized public links (event calendar, membership form, donation page, member signup, SMS opt-in, kids check-in, Connect, Public Prayer, and others) you can share or print.",
            list("Open Public Screens.", "Pick a page to generate.", "Copy or print the generated link.", "Optionally set an expiration."),
            list("Problem: a link shows the wrong church — Fix: regenerate it for the correct logged-in church."),
            "1.1", list("v1.1 — Added Connect With Us and Public Prayer link types.", "v1.0 — Initial release."),
            list("public link","share","qr","generate link","public screen")));

        add(new HelpArticle("certificates", "More", "Certificates", "more.certificates", false,
            "Generate and print certificates for members and milestones.",
            "Create branded certificates (e.g. baptism, membership, dedication) and print them.",
            list("Open Certificates.", "Choose a template and recipient.", "Generate and print."),
            null, "1.0", list("v1.0 — Initial release."), list("certificate","baptism","award","print certificate")));

        // ── Added 2026-10-07: every feature in the product has an article, so the assistant
        //    can explain a feature even when this user cannot open it. ──
        add(new HelpArticle("ticketing", "Support", "Ticketing — Support Tickets", "more.ticketing", false,
            "Raise a support ticket with the ChurchGeniusPro team from inside the app and follow it by email.",
            "Ticketing (More → Ticketing) lets staff send a support request to ChurchGeniusPro: a subject, description, urgency and your contact details. You receive a reference number and email updates; the support team sees your church, plan and logged-in user so they can help faster. Administrators can switch it on or off per login under Users → permissions (member logins need it switched on). On a Trial account support may be limited during the trial.",
            list("Open More → Ticketing.", "Click New Ticket, choose the urgency and describe the problem.", "Submit — you get a reference number and a confirmation email.", "Reply to the email or open the ticket again to add details."),
            list("Problem: Ticketing is missing from the menu — Fix: an administrator must tick More → Ticketing for your login in Users → permissions."),
            "1.0", list("v1.0 — Initial release."), list("ticket","ticketing","support ticket","support","raise a ticket","contact support")));

        add(new HelpArticle("ai_assistant", "Support", "AI Assistant & AI Search", "more.aiassistant", false,
            "Ask questions about your church data, find pages, get how-to help — by typing, voice or conversation.",
            "The ✨ AI Search bar on every page and the AI Assistant chat page (More → AI Assistant) answer questions from your own records (only what your role may see), navigate to pages (\"Go to the Income page\"), explain features and give how-to steps. Three modes: Type, Voice (🎤) and Converse (🗣, back-and-forth). On Income, Expense and Meeting screens the AI can also fill in the form from a spoken or typed sentence. AI features need the church's AI service to be configured and the login's AI Assistant permission.",
            list("Type a question in the ✨ bar, or open More → AI Assistant for a chat window.", "Say or type \"Go to …\" to open a page.", "Ask data questions such as \"What was the income for March?\" or \"Whose birthday is this week?\"."),
            list("Problem: the assistant says a feature is not enabled — Fix: that page is outside your role, permissions or plan; an administrator can change it."),
            "1.1", list("v1.1 — Chat-style AI Assistant page; feature questions answered for every feature.", "v1.0 — AI Search bar with Type / Voice / Converse."),
            list("ai","assistant","ai search","voice","converse","ask")));

        add(new HelpArticle("donation_give", "Accounting", "Donation / Give — Online Giving", "accounting.donation", false,
            "Online donations from the public donation page and member contributions from the Member Portal, paid through the church's Stripe account.",
            "Connect the church's Stripe account under Admin → Stripe Integration. Public Screens then gives you a donation page link; members can also pay a contribution in the Member Portal (Give). Accounting → Donation/Give lists both — a Donations tab and a Member Contributions tab — with Stripe fees, the amount remaining, and a Pending / Completed review mark. Each payment is also posted to Income automatically.",
            list("Admin → Stripe Integration: enter the church's Stripe keys and test the connection.", "Share the donation page link from Public Screens.", "Review payments under Accounting → Donation/Give; use Mark Completed when reconciled."),
            list("Problem: Online giving is not set up — Fix: add the Stripe keys under Stripe Integration (SuperAdmin/Admin)."),
            "1.1", list("v1.1 — Member Contributions tab; Pending/Completed review.", "v1.0 — Donation review."),
            list("donation","donations","give","giving","online giving","stripe","member contributions","donation review")));

        add(new HelpArticle("member_portal", "Members", "Member Portal", null, false,
            "What members see when they sign in: family, giving, groups, classes, volunteering, directory, song book and messages.",
            "Members sign in to the Member Portal (public Member Signup link, approved by the church). It has My Family (own details), Give / Contributions (online giving and statements), Groups, Classes (Sunday School), Volunteer, Directory (respecting privacy settings), Song Book and messages. Administrators approve new logins from Membership Requests or Users. There are also a Church Portal (church-level account), a Staff Portal and a Kids Portal.",
            list("Share the Member Signup link from Public Screens.", "Approve the login from Membership Requests (login account) or Users.", "Members open /memberHome and use the tabs."),
            null, "1.0", list("v1.0 — Initial release."), list("member portal","portal","member login","member signup","kids portal","church portal","staff portal")));

        add(new HelpArticle("membership_requests", "Members", "Membership Requests", "admin.membership", false,
            "Collect membership applications online and review every submitted detail before adding or renewing a family.",
            "Share the public Membership Form link (Public Screens). Each application lists the family address, phone and email, and for every member the name, nickname, gender, date of birth, wedding anniversary, contact details with privacy choices, own address and photo. Review it under Admin → Membership Requests and click Add as Member (new) or Approve (renewal of an existing family). A login account requested on the form can be approved there too.",
            list("Open Admin → Membership Requests.", "Click an application to see every submitted field.", "Click Add as Member, or Approve for an existing family."),
            null, "1.1", list("v1.1 — Full review screen with every submitted field.", "v1.0 — Initial release."), list("membership request","membership form","application","apply","renewal")));

        add(new HelpArticle("pledges", "Accounting", "Pledges", "accounting.pledges", false,
            "Run pledge campaigns and track each member's pledge against what they have given.",
            "Create a campaign with a goal and dates, record each member's pledge, and follow progress as contributions come in. Members can see their pledges in the Member Portal.",
            list("Open Accounting → Pledges.", "Create a campaign.", "Add member pledges and watch the progress."),
            null, "1.0", list("v1.0 — Initial release."), list("pledge","pledges","campaign")));

        add(new HelpArticle("bank_sync", "Accounting", "Bank Sync (Connect a Bank Account)", "accounting.bankimport", false,
            "Connect bank accounts and pull in new transactions to review and add as income or expense.",
            "Bank Sync (Accounting → Bank Sync) connects one or more bank accounts through Plaid and brings in new transactions, which you review and add with their details. Trial accounts connect a sandbox (test) bank; the Standard plan allows up to 3 connected accounts. Bank Import (statement file) is the alternative when you prefer not to connect an account.",
            list("Open Accounting → Bank Sync.", "Click Connect and follow the bank's sign-in.", "Review the imported transactions and add the ones you want."),
            list("Problem: the bank is not listed — Fix: on a trial only the sandbox bank is available; convert the account to a paid plan."),
            "1.0", list("v1.0 — Initial release."), list("bank sync","connect bank","plaid","bank account","sync")));

        add(new HelpArticle("worship_planning", "Ministry", "Worship Planning", "general.ministry", false,
            "Worship groups, instruments, songs and automatically generated assignments.",
            "Set up worship groups and instruments, add songs, then generate assignments for a date range. Skip specific dates and the order numbers keep counting correctly for the remaining dates. Members see their assignments in the Member Portal.",
            list("Open General → Worship Planning.", "Create groups and add members and instruments.", "Generate assignments for the season; mark any dates to skip."),
            null, "1.0", list("v1.0 — Initial release."), list("worship","worship planning","assignments","worship team")));

        add(new HelpArticle("song_book", "Ministry", "Song Book & Song Book Access", "general.ministry", false,
            "Upload and organise songs and publish a public Song Book page.",
            "Upload songs (More → Song Book), finalise the list, and share the public Song Book link. Song Book Access controls which logins may edit it.",
            list("Open More → Song Book and upload songs.", "Finalise the list.", "Share the public Song Book page; manage editors under Song Book Access."),
            null, "1.0", list("v1.0 — Initial release."), list("song book","songbook","songs","hymns")));

        add(new HelpArticle("sunday_school", "Ministry", "Sunday School", "general.ministry", false,
            "Classes, teachers, lessons, exams and student progress.",
            "Under General → Ministry, set up Sunday School classes, assign teachers, publish lessons and exams. Students and parents see classes and results in the Member Portal (Classes tab); teachers manage their classes from the portal too.",
            list("Open General → Ministry → Sunday School.", "Create a class and assign a teacher.", "Add students; publish lessons and exams."),
            null, "1.0", list("v1.0 — Initial release."), list("sunday school","class","teacher","lesson","exam")));

        add(new HelpArticle("stripe_integration", "Admin", "Stripe Integration", null, true,
            "Connect the church's own Stripe account for online giving.",
            "Admin → Stripe Integration stores the church's Stripe publishable and secret keys (separate from ChurchGeniusPro's own billing) and enables the public donation page and Member Portal giving. Use Test connection to confirm the keys. Payments are reviewed under Donation/Give.",
            list("Create a Stripe account for the church.", "Enter the keys under Admin → Stripe Integration and test the connection.", "Share the donation page from Public Screens."),
            null, "1.0", list("v1.0 — Initial release."), list("stripe","stripe keys","online giving setup","payment setup")));

        add(new HelpArticle("whatsapp_integration", "Admin", "WhatsApp Integration", null, true,
            "Connect a WhatsApp sender for member messaging.",
            "Admin → WhatsApp Integration links a WhatsApp sender so messages to members can go out on WhatsApp as well as email/SMS.",
            list("Open Admin → WhatsApp Integration.", "Enter the sender details and save."),
            null, "1.0", list("v1.0 — Initial release."), list("whatsapp")));

        add(new HelpArticle("email_settings", "Communications", "Email Settings & Trial Test Email", "general.emailsettings", false,
            "Sender settings for church email; on Trial/Demo accounts the verified test address that receives all outgoing mail.",
            "Email Settings holds the church's email sender details. Trial and Demo accounts never email real recipients: verify one test email address under Email Settings and every email action (bulk email, reminders, alerts) sends ONE test copy there, marked as a test email; the rest are counted as simulated. Unsubscribed people are listed under Admin → Unsubscribed List.",
            list("Open Email Settings.", "On a trial: enter your test address, receive the code, verify it.", "Send a bulk email or reminder — one test copy arrives at the verified address."),
            null, "1.1", list("v1.1 — Trial/Demo verified test email.", "v1.0 — Initial release."), list("email settings","test email","verified email","sender","unsubscribed")));

        add(new HelpArticle("files_notes", "Admin", "Files & Notes", null, true,
            "Upload files and keep notes for the church office.",
            "Admin → Files & Notes stores documents and notes for the office team.",
            list("Open Admin → Files & Notes.", "Upload a file or add a note."),
            null, "1.0", list("v1.0 — Initial release."), list("files","notes","upload","documents")));

        add(new HelpArticle("private_page_access", "Admin", "Private Page Access", null, true,
            "Restrict Kids Ministry, Event Check-in and Temporary Login to the church's own network.",
            "Admin → Private Page Access limits sensitive pages so they only work from the church's own Wi-Fi/network. Useful for check-in stations and temporary logins.",
            list("Open Admin → Private Page Access.", "Choose the pages to restrict and the allowed network."),
            null, "1.0", list("v1.0 — Initial release."), list("private page","private access","network restriction","ip restriction")));

        add(new HelpArticle("temporary_access", "Admin", "Temporary Access & Badges", null, true,
            "Time-limited logins for helpers with printable barcode badges.",
            "Admin → Temporary Access creates a login that works for a set period, with a barcode badge you can print; NTAG (NFC) login is the tap-to-sign-in alternative.",
            list("Open Admin → Temporary Access.", "Create the access with its validity period.", "Print the badge."),
            null, "1.0", list("v1.0 — Initial release."), list("temporary access","temporary login","badge","access card","barcode")));

        add(new HelpArticle("users_permissions", "Admin", "Users, Roles & Permissions", null, true,
            "Staff logins, their roles, and the permission checkboxes that decide which pages each login sees.",
            "Admin → Users manages staff logins. Roles: SuperAdmin (everything), Admin (administration and general), Accountant (accounting), User (general). The permission checkboxes control pages, menus and buttons for a login; data access follows the role. Opt-in features such as Ticketing and AI Assistant are ticked per login. You can link several church accounts and switch between them without signing out.",
            list("Open Admin → Users.", "Add a user with a role, or edit permissions for an existing login.", "Use Link Accounts to join multiple church accounts."),
            null, "1.0", list("v1.0 — Initial release."), list("users","permissions","roles","superadmin","admin","accountant","link accounts","switch account")));

        add(new HelpArticle("favorites", "Help", "Favorites", null, false,
            "Pin the pages you use most for one-click access.",
            "Click the ⭐ next to a page in the left menu or on a listing page to add it to Favorites at the top of the menu.",
            list("Find the page in the left menu.", "Click its ⭐."),
            null, "1.0", list("v1.0 — Initial release."), list("favorites","favourite","star","pin page")));

        add(new HelpArticle("install_app", "Help", "Install the App (Android & iPhone)", null, false,
            "Add ChurchGeniusPro to your phone's home screen like an app.",
            "ChurchGeniusPro works as an installable web app. Android (Chrome): menu ⋮ → Add to Home screen / Install app. iPhone (Safari): Share → Add to Home Screen. It then opens full-screen with its own icon.",
            list("Android: open the site in Chrome, tap ⋮, then Install app / Add to Home screen.", "iPhone: open the site in Safari, tap Share, then Add to Home Screen."),
            null, "1.0", list("v1.0 — Initial release."), list("install","app","home screen","android","iphone","pwa")));

        add(new HelpArticle("plans_trial", "Account", "Subscription Plans & Trial Accounts", null, false,
            "Trial, Free, Standard and Pro plans; what a trial includes and how to subscribe.",
            "Plans: Trial (full access for the trial period), Free, Standard and Pro — each with monthly allowances for emails, SMS and online giving that reset every 30 days from the subscription start date, a maximum number of people and staff logins, and plan-specific features (for example Bank Sync is sandbox-only on a trial and capped at 3 accounts on Standard). Access closes on the subscription end date; reminders are emailed before. Trial accounts: a sample-data trial comes pre-filled and cannot be converted; an empty-account trial keeps its data when it converts to a paid plan. Use Request a subscription (from the trial notice or /subscriptionReq) to subscribe or change plan; the ChurchGeniusPro team confirms it and sends an invoice you can pay online.",
            list("See your plan and usage under the account/subscription area.", "Request a subscription or plan change.", "Pay the invoice from the emailed link."),
            list("Problem: a feature says it is not in your plan — Fix: request a plan change; the team can convert the account."),
            "1.0", list("v1.0 — Initial release."), list("plan","plans","trial","subscription","free","standard","pro","pricing","allowance","limit","upgrade")));

        add(new HelpArticle("guess_it", "Activity", "Guess It (Activity Corner)", null, false,
            "A live quiz game members join from their phones.",
            "Activity Corner → Guess It runs a group quiz: the host starts a session and members join from their phones and answer in real time.",
            list("Open Activity Corner → Guess It.", "Start a session and let members join from their phones."),
            null, "1.0", list("v1.0 — Initial release."), list("guess it","quiz","game","activity corner")));

        // ── Help & Assistant (everyone) ──
        add(new HelpArticle("help_assistant", "Help", "Using the AI Help Assistant", null, false,
            "Ask questions by text or voice and get answers, steps, and troubleshooting — within your permissions.",
            "The AI Help Assistant answers questions about the app using only the help articles you are allowed to see. You can type or speak, ask for a brief or detailed explanation, request step-by-step instructions, or describe a problem for troubleshooting. Use Read Aloud to have any article or answer spoken to you with play/pause/stop and adjustable speed.",
            list("Open Help Center.",
                 "Type a question (e.g. 'How do I check in a child?') or tap the microphone to speak.",
                 "Ask follow-ups like 'explain in detail' or 'step by step'.",
                 "Press Read Aloud on any article or answer to hear it; adjust the speed as needed."),
            list("Problem: the assistant says a feature isn't available to you — Fix: that module is restricted for your account; contact your administrator for access.",
                 "Problem: voice doesn't work — Fix: allow microphone access in your browser; voice features require the church's AI to be configured.",
                 "Problem: nothing is spoken — Fix: your browser's text-to-speech must be available and the volume up."),
            "1.0", list("v1.0 — Voice + conversational assistant, Read Aloud, permission-aware answers, audit logging."),
            list("help","assistant","ai","voice","read aloud","how do i")));
    }
}

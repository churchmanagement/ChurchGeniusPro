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
        // roles: SuperAdmin,Admin,Accountant  (Member-portal logins too, when the church grants admin.family)
        add(new HelpArticle("members", "Members", "Families & Members", "admin.family", false,
            "Find, add and update the families and people in your church directory, and see who is a Member, Guest or Visitor.",
            "Admin → Families is the church directory. Every person belongs to a family with one Primary member (Head of Household) and optional other members (Spouse, Adult, Child). The list shows each family's Primary, member count and status; the Type filter shows All Families, Member Families, Non-Member Families, Guests only or Visitors only. A family's type is the Member Type of its Primary member, so a family whose Primary is a Guest or Visitor is a non-member family and carries a Guest or Visitor badge. The Deleted and Inactive switches show only deleted or only inactive families. 🔍 Member Search looks up individual people by name, role, member type, phone or email and shows each person's Status (Active, Inactive or Deleted — a person counts as inactive or deleted when they or their family are; deleted rows are read-only). On the family form, 🔒 Private on phone, email or address hides that detail from members browsing the Member Portal directory, Include in Contributions makes the person selectable as a Contributor on the Income page, and the Primary's Inactive switch marks the whole family inactive. 📄 Membership Form (PDF) prints a fillable form and 📤 Upload Scanned Form reads a completed one into the form for review. Separate permission checkboxes control Add or Edit, Delete and Mark Inactive. A subscription plan with a people limit refuses saves that would pass the limit.",
            list("Go to Admin → Families.",
                 "Click ＋ Add Family (or 📝 on a family to edit it).",
                 "Primary: enter First Name and Last Name (required), contact details, Category (Member, Guest or Visitor), birthday, address and Nick Name.",
                 "Family Members: click ＋ Add, fill in the person and click ＋ Add Member; or search an existing member to move them into this family.",
                 "Roles: choose Head of Household, Spouse, Adult or Child, and enter the Anniversary Date for a Spouse.",
                 "Click Save Family (Update Family when editing).",
                 "To act on several families, tick their rows and use Mark Inactive, Delete Selected or 📄 Family Report."),
            list("Problem: a person or family is missing — Fix: clear the search box, set Type to All Families, and check the Inactive / Deleted switches or Member Search with Show Inactive / Show Deleted.",
                 "Problem: no 📝, Delete or Mark Inactive buttons — Fix: those need the Families 'Add or Edit', 'Delete' or 'Mark Inactive' permission; ask your church account holder.",
                 "Problem: 'Your current subscription allows up to N members' — Fix: your plan's people limit is reached; upgrade the plan before adding more people.",
                 "Problem: a deleted family needs to come back — Fix: the Families page has no Restore button, so mark families Inactive instead of deleting them when they may return.",
                 "Problem: an uploaded photo of a paper form fills nothing in — Fix: upload a filled-in digital PDF, or type the details; reading photos needs the church's AI reading to be available."),
            "2.0", list("v2.0 — Rewritten for the current Families page: Type filter (Member / Non-member / Guest / Visitor), Guest/Visitor badges, Member Search Status column, 📊 Report, bulk actions, privacy and Include in Contributions, PDF/scan import, plan people limit.",
                        "v1.2 — Added nickname display and per-field privacy redaction.", "v1.1 — Membership-form scan import.", "v1.0 — Initial release."),
            list("member","family","families","directory","add member","add family","household","guest","visitor","non-member","member search","inactive","private"))
            .roles("SuperAdmin,Admin,Accountant"));

        // roles: SuperAdmin,Admin  (Member-portal logins too, when the church grants admin.groups)
        add(new HelpArticle("groups", "Members", "Groups & Group Email", "admin.groups", true,
            "Put people into groups (choir, youth, small groups) and email a group's members.",
            "Admin → Groups lists your groups with their member count. Type a Group Name and click ＋ Add Group; 📝 renames and 🗑 deletes a group. The members icon opens Group Members: add someone from your directory (search by name or email, then ＋ Add to Group — for a person without an email you can tick Use Head of Household email), or add a person to the group only with First Name, Last Name and optional Email and ＋ Add Member. To email, tick members (people without an email cannot be ticked), click ✉ Email Selected, enter Subject and Message, optionally attach a file and click ✉ Send Email; recipients get it as BCC. Only addresses that belong to your church's group members are sent to. On Trial/Demo accounts a single test email goes to the verified test address instead. Groups is part of the subscription plan's Groups feature; email needs the Group Email permission.",
            list("Go to Admin → Groups.",
                 "Type a Group Name and click ＋ Add Group.",
                 "Click the members icon, search a member and click ＋ Add to Group (or add a new person with ＋ Add Member).",
                 "Tick members and click ✉ Email Selected.",
                 "Enter Subject and Message, attach a file if needed, and click ✉ Send Email."),
            list("Problem: ✉ Email Selected is missing — Fix: your login needs the Group Email permission.",
                 "Problem: a member's checkbox is greyed out — Fix: they have no email; edit them (📝) to add one, or tick Use Head of Household email when adding them.",
                 "Problem: 'No recipients belong to your church's groups.' — Fix: the selected addresses aren't group members' emails; check the members' email addresses.",
                 "Problem: Groups is missing from the menu — Fix: the Groups permission is off for your login, or your plan doesn't include Groups."),
            "2.0", list("v2.0 — Corrected button names (＋ Add Group, ＋ Add to Group, ✉ Email Selected, ✉ Send Email); group-only members, Head of Household email option, BCC, Trial/Demo test email, Group Email permission, plan feature.",
                        "v1.0 — Initial release."),
            list("group","groups","ministry","small group","add to group","group email","email group","bcc"))
            .roles("SuperAdmin,Admin"));

        // roles: SuperAdmin,Admin,User   (Accountant and Limited can open the Events list but not create/edit)
        add(new HelpArticle("create_event", "Events", "Creating an Event", "general.events.edit", false,
            "Create one-day or multi-day events with a public RSVP page, registrants list and check-in.",
            "Events live under General → Events. Each event has a public registration (RSVP) page that visitors open without signing in; they answer Yes or No (and Maybe when allowed) and give their email, name, phone, adults and children. Optional settings: Registration End Date (the page then shows that registration has closed), Registration Fee, Max Capacity, a \"Who else is attending?\" list, a unique Registration ID and/or QR Code per registrant, self check-in (an I'm Here button), food/dietary choices and accommodation details. Event ID / Code is optional and must be unique. A confirmation email is sent after an RSVP using the default RSVP email template. After a new event is saved, the page offers a 🔔 Set Event Reminder link.",
            list("Go to General → Events and click ＋ New Event.",
                 "Enter the Event Name; optionally type an Event ID / Code or click Generate.",
                 "Choose Event Type: One Day (Date, Start/End Time) or Multiple Days (+ Add Day per day).",
                 "Set Registration options, Location, Host Information and Additional Details, then click Save Event.",
                 "Share the event with 🔗 Link or 📝 Register Page on its card; see responses with 👥 Registrants and 📋 Full Report.",
                 "Optionally click ✉️ RSVP Email Templates to customise the confirmation email (placeholders {Registrant Name}, {Event Name}, {Date}, {Time}, {Location}, {Contact}, {Google Calendar Link}, {Directions Link})."),
            list("Problem: ＋ New Event / ✏️ Edit / 🗑️ missing — Fix: creating and editing needs a SuperAdmin, Admin or User login with Events \"Add or Edit\"; deleting needs \"Delete\". Accountant and Limited logins can only view the list.",
                 "Problem: \"That Event ID / Code is already in use\" — Fix: choose another code or click Generate.",
                 "Problem: a visitor sees \"This email address has already submitted an RSVP for this event\" — Fix: they should use the link in their confirmation email, which opens Update Your RSVP.",
                 "Problem: confirmation emails not arriving on a Trial/Demo account — Fix: Trial/Demo accounts never email real people; only a verified test address receives a test copy.",
                 "Problem: deleted event — Fix: deleting removes the event and all its registrations and cannot be undone."),
            "2.0", list("v2.0 — Rewritten for the current Events page: optional unique Event ID / Code, Upcoming & Today / Past tabs, registration options, RSVP email templates, set-a-reminder prompt after saving.", "v1.3 — QR check-in, registration codes, upcoming/past tabs.", "v1.1 — RSVP confirmation email templates.", "v1.0 — Initial release."),
            list("event","create event","new event","rsvp","registration","register page","registration link","registrants","event code","multi-day")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("attendance", "Events", "Recording Attendance", "general.attendance", false,
            "Check people in by search, family, QR/barcode or as visitors, then review records and reports.",
            "General → Attendance has four tabs. 📊 Dashboard: this week's and month's attendance, first-time and returning visitors, members and volunteers present, and trend charts. ✅ Check-In: choose Service / Event and Status (Present, Late, Absent, Excused), optionally Ministry and Campus, then check in by 🔎 Manual search, 👪 Family (Check in selected), 📷 QR / Barcode (scanner or camera) or 🙋 Visitor (Check in visitor). The Code button shows a member's printable attendance QR/barcode. 📋 Records: filter, Edit, Delete and export to CSV, Excel or PDF. 📈 Reports: Member Attendance, Family Attendance, Ministry, Events, Visitors and Trends with ⬇ Export CSV.",
            list("Go to General → Attendance and open ✅ Check-In.",
                 "Choose the Service / Event and Status.",
                 "Search a person and click Check in, or use the 👪 Family, 📷 QR / Barcode or 🙋 Visitor tab.",
                 "Use 📋 Records to correct or export entries and 📈 Reports (set From/To, click Run) for summaries."),
            list("Problem: a person is not found — Fix: search by name, phone, email or member ID; someone new can be checked in as a Visitor.",
                 "Problem: Check in / Edit / Delete buttons missing — Fix: they need the Attendance \"Add or Edit\" and \"Delete\" permissions.",
                 "Problem: a person was checked in twice — Fix: each check-in is its own record; delete the extra one in 📋 Records.",
                 "Problem: the Attendance menu is missing — Fix: Attendance is for SuperAdmin, Admin and User logins and must be included in the plan."),
            "2.0", list("v2.0 — Rewritten for the tabbed Attendance page (Dashboard, Check-In methods, Records export, Reports) and ✉ Email / SMS Volunteers.", "v1.0 — Initial release with dashboard, check-in, records, export and reports."),
            list("attendance","check in","present","visitor","family check in","qr","barcode","records","report","trends")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited
        add(new HelpArticle("create_meeting", "Events", "Meetings (One-time & Recurring)", "general.meetings", false,
            "Schedule one-time or recurring meetings, skip dates, notify people, and keep a history.",
            "General → Meetings. Each meeting has a Category (required), an optional Location (searching a member fills the address from their record), Date or Start Date, Start/End Time, Occurrences (One-time, Daily, Weekly with Repeat on days, Monthly with Repeat in months plus a Day of month or a Week/Day of week pattern) and an optional End Date, Note, Meeting Image (max 5 MB) and Message Template. Row buttons: 📝 Edit, 📣 Send Notification (Email/SMS to Members/Guests, sent immediately), 📆 Manage Occurrences (recurring only: delete one or several dates, ↩️ restore, Delete This & All Future…), 🗑 Delete. 🕐 History shows past meetings; meetings more than 90 days old are deleted automatically unless \"Don't auto-delete (exempt from 90-day purge)\" is ticked. 🏷️ Category manages categories. Message templates (⚙ Manage) support {Category}, {Date}, {Time}, {LocationName}, {LocationAddress}, {Notes} and {Image} with date/time formats. The AI controls (🔎 Type, 🎤 Voice, 🗣 Converse, Fill Form) fill the form from a plain-English request; nothing is saved until you click Save.",
            list("Go to General → Meetings.",
                 "Pick a Category and (optionally) a Location.",
                 "Enter the Date/Start Date and times, choose Occurrences and the repeat days or months, and an End Date if needed.",
                 "Add a Note, image and Message Template, then click Save.",
                 "Use 📣 to notify people, 📆 to skip dates and 🕐 History for past meetings."),
            list("Problem: \"Please select a meeting type.\" — Fix: choose a Category; add one with 🏷️ Category if the list is empty.",
                 "Problem: a member is missing from Location — Fix: only members with their own address on file are listed; type the address instead.",
                 "Problem: a date should be skipped — Fix: use 📆 Manage Occurrences rather than deleting the whole meeting; deleted dates get no reminders.",
                 "Problem: an old meeting disappeared — Fix: meetings older than 90 days are auto-deleted unless Don't auto-delete was ticked.",
                 "Problem: 📝 / 📣 / 📆 / 🗑 / 🏷️ missing — Fix: they follow the Meetings permissions Add or Edit, Send Notification, Delete and Category."),
            "2.0", list("v2.0 — Rewritten: Category, recurring Weekly/Monthly patterns, Manage Occurrences, History and 90-day auto-delete, Send Notification, message template placeholders, AI fill (Type/Voice/Converse).", "v1.2 — Voice/Converse dictation into the meeting form.", "v1.1 — Message templates with placeholders + live preview.", "v1.0 — Initial release."),
            list("meeting","create meeting","recurring meeting","weekly","monthly","occurrences","history","auto-delete","notification","template","category","fill form")).roles("SuperAdmin,Admin,Accountant,User,Limited"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("check_in_child", "Ministry", "Checking In & Out a Child", "general.ministry.kids", false,
            "Check children in with a printed security code and release them only to a verified parent or authorized pickup.",
            "Children can be checked in from the 👦 Register list (Check In, then 🖨️ Print Label from the ✅ Checked In! window) or at the Kids Check-In station on the ✅ Check-In tab, which parents use by entering their phone number (⛶ Full Screen opens it on its own for a kiosk). Every check-in gets a code, printed on the label. To check out, scan the barcode, type the code or use 📷 Camera, click 🔍 Find, then Check Out and confirm the name in Verify Pickup — it must match the parent / guardian or an authorized pickup. Currently Checked In lists today's check-ins. A Manual Check-Out override (Admin only) needs a reason and is logged. The 📋 Reports tab shows check-in / check-out history with ⬇ Export CSV. Checking in from the list and manual override are Admin-only; User logins can check out with a code. The ✅ Check-In tab is hidden when the plan does not include Kids check-ins.",
            list("Open General → Ministry → Kids Ministry.",
                 "Check in: on 👦 Register click Check In on the child and 🖨️ Print Label — or use the Kids Check-In station on ✅ Check-In.",
                 "Check out: on ✅ Check-In scan or type the code (or 📷 Camera) and click 🔍 Find.",
                 "Click Check Out next to the child, enter Picked up by (name) and click Confirm Check-Out.",
                 "Without a code, an Admin uses Check Out in Currently Checked In and gives a reason."),
            list("Problem: the station finds no children — Fix: the child must be registered with that parent's phone number; register the child first.",
                 "Problem: \"Not an authorized guardian\" — Fix: the name must match the parent / guardian or an authorized pickup; an Admin can add the person under Authorized Pickups.",
                 "Problem: the ✅ Check-In tab is missing — Fix: Kids check-ins are not included in the church's plan.",
                 "Problem: Check In on the list gives Access denied — Fix: checking in from the list needs an Admin or SuperAdmin login; use the Kids Check-In station instead."),
            "2.0", list("v2.0 — Rewritten for the current screens: 👦 Register tab, Kids Check-In station, code/barcode/camera check-out with guardian verification, Manual Check-Out override, reports and role limits.",
                        "v1.4 — Pickup windows + overdue alerts + per-child recipients.", "v1.3 — Secure checkout (code/scan/camera) + paper photo.", "v1.2 — Check-in history + active list.", "v1.0 — Initial release."),
            list("kids","child","check in","checkout","pickup","security code","label","kiosk","nursery")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin   (the page also lets User in, but every volunteer API requires SuperAdmin/Admin)
        add(new HelpArticle("assign_volunteers", "Events", "Event Volunteers", "general.events.emailsms", true,
            "Add volunteers and roles to an event, track their status, and email or text them.",
            "Each event has its own volunteer list, opened with 🙋 Volunteers on the event card (Volunteer Management page). Add roles, add members by search or people manually, set each volunteer's roles, notes and status (Pending, Confirmed, Declined, Maybe), and message them with ✉️ Email All / 💬 SMS All or per row. Adding a volunteer does not message them automatically. When a member responds from the Member Portal, the event's creator is emailed. Event volunteers are separate from the volunteer profiles used by Attendance → Email / SMS Volunteers and Follow-Ups.",
            list("Go to General → Events and click 🙋 Volunteers on the event card.",
                 "Type a role next to Roles: and click + Add.",
                 "Search members to add as volunteer, or click + Add Manually.",
                 "Use ✏️ to set roles and notes; change the Status list as people respond.",
                 "Click ✉️ Email All or 💬 SMS All, write the message and click Send."),
            list("Problem: no 🙋 Volunteers button — Fix: needs the Events \"Email or SMS Volunteers\" permission.",
                 "Problem: the page shows an error or empty list for a User login — Fix: event volunteer management is available to SuperAdmin and Admin logins.",
                 "Problem: \"No volunteers with email addresses\" — Fix: add an email (or phone for SMS) to the volunteer with ✏️."),
            "2.0", list("v2.0 — Rewritten: volunteers are now managed per event (Volunteer Management page); the separate Volunteers menu page was removed.", "v1.1 — Inline Add New Volunteer from the Follow-Ups assignee field.", "v1.0 — Initial release."),
            list("volunteer","event volunteers","assign volunteer","volunteer role","email all","sms all","usher","greeter")).roles("SuperAdmin,Admin"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("followups", "Members", "Follow-Ups", "more.followups", false,
            "Follow-up tasks plus the Connect With Us, public Prayer Requests and Reported Content queues in one page.",
            "More → Follow-Ups has four tabs. 📋 Follow-Up Tasks: tasks with a Title, Description, Priority (Low, Medium, High, Urgent), Due Date, Assigned To (searchable members and volunteers, with Show inactive and ＋ Add New Volunteer) and an optional Linked Type (Member, Visitor, Prayer Request, Donation) and Linked Label. The All / Pending / Missed / Completed counters filter the list; Pending tasks past their due date become Missed overnight. Admins and SuperAdmins see all tasks; User logins see only tasks assigned to them. 🤝 Connect With Us and 🙏 Prayer Requests hold public-form submissions (Manage: assign, change status, add follow-ups or notes). 🚩 Reported Content holds Members Chat messages reported by members. New prayer requests and Connect cards also appear in the 🔔 bell. Follow-Ups is part of the plan's Follow-ups feature. The old Connect Submissions page now opens the Connect With Us tab.",
            list("Go to More → Follow-Ups.",
                 "Click ＋ New Follow-Up.",
                 "Enter a Title, Priority and Due Date, and pick someone in Assigned To.",
                 "Click Save.",
                 "Click ✔ Done when it's finished (✏️ edits, 🗑️ deletes)."),
            list("Problem: a person isn't in Assigned To — Fix: tick Show inactive, or use ＋ Add New Volunteer.",
                 "Problem: a User login sees no tasks — Fix: Users see only tasks assigned to them; an Admin can see and reassign every task.",
                 "Problem: Follow-Ups is missing from More — Fix: the Follow-ups permission is off for your login, or your plan doesn't include Follow-ups."),
            "2.0", list("v2.0 — Four tabs (Follow-Up Tasks, Connect With Us, Prayer Requests, Reported Content), Missed status, Linked Type, bell notifications; Connect Submissions page retired into this page.",
                        "v1.1 — Searchable members+volunteers assignee dropdown with inline Add New Volunteer.", "v1.0 — Initial release."),
            list("follow up","follow-up","followups","assign","task","missed","pastoral care","connect with us","prayer requests","reported content"))
            .roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("prayer", "Ministry", "Prayer Requests", "general.ministry.prayer", false,
            "Keep the prayer list in sections, assign prayer volunteers, track status and notes, and send reminder emails.",
            "General → Ministry → 🙏 Prayer Ministry opens Prayer Requests. Requests are grouped in sections (+ Add Main Section). Each request has a Title, Description, Requested By, Status (New, Assigned, In Progress, Following Up, Answered, Closed) and Assigned Volunteers. 👥 Manage Volunteers builds the prayer team from members or manual entries with an optional role. 💬 opens notes, 📦 closes (archives) a request and 🔄 reopens it; the Filter bar searches by status, volunteer and text, with Show closed. The 🔔 Prayer Reminder Schedule sends reminder emails to members One-time, Daily, Weekly or Monthly at 6:00 AM or 6:00 PM, and ✉ Email All Members sends now. Requests submitted from the public website, public link or NTAG page are managed separately — 🌐 Public Requests, also under More → Follow-Ups → 🙏 Prayer Requests.",
            list("Open General → Ministry → 🙏 Prayer Ministry.",
                 "Click + Add Main Section, then + Add Prayer Request in that section.",
                 "Enter the details, choose Status and Assigned Volunteers, and Save.",
                 "Use 👥 Manage Volunteers to add the prayer team.",
                 "Set the 🔔 Prayer Reminder Schedule, or click ✉ Email All Members."),
            list("Problem: a scheduled prayer reminder wasn't delivered — Fix: check Occurrence is not None, the days or months are selected, and email is configured; schedules run on the church's America/Chicago time.",
                 "Problem: a request disappeared — Fix: it was closed; tick Show closed and click 🔄.",
                 "Problem: website submissions are not in the list — Fix: they are under 🌐 Public Requests / Follow-Ups → Prayer Requests.",
                 "Problem: deleting a section removed its requests — Fix: deleting a section deletes every request in it; close requests instead to keep them."),
            "2.0", list("v2.0 — Rewritten for the current page: sections, statuses, prayer team, notes, close/reopen, reminder schedule, Email All Members; public submissions moved to 🌐 Public Requests / Follow-Ups.",
                        "v1.2 — Separate public prayer management, sources, volunteer assignment, activity log.", "v1.1 — Prayer scheduler reminders.", "v1.0 — Initial release."),
            list("prayer","prayer request","prayer team","intercession","prayer reminder","prayer volunteer")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("reminders", "Communication", "Automated Reminders (Event, Periodic, One-time)", "general.reminders", false,
            "Send automatic email/SMS/WhatsApp reminders for events, birthdays, anniversaries, holidays, meetings and one-off dates.",
            "General → Reminders has three pages. Event Reminders: pick an event, tick Same Day, Before Days and/or After Days, optionally write an email template per occurrence (placeholders {eventName}, {date}, {time}, {location}, {map}, {calendar}, {beforeDays}, with 👁 Preview) and choose Email/SMS/WhatsApp; they go to registrants who answered Yes or Maybe, and the Delivery button shows delivered/failed/skipped attempts with reasons. Periodic Reminders: choose a type (Birthday, Wedding Anniversary, Birthdays & Anniversary, Meeting Reminder, Weekly Meeting Reminder, Monthly Statement, Celebrants (Members Only), New Year, Christmas, US Independence, Thanksgiving, Veterans Day, Presidents' Day, Memorial Day, Labor Day), recipients (Members, Guests, Visitors, Groups, and Celebrant options for birthday/anniversary types) and channels; holidays have email ({occasion}, {date}) and 160-character SMS templates, Birthday/Wedding Anniversary a celebrant email ({name}). One-time Reminders: a Name and Event Date; the Name is the subject and the Note is the message. Tick DISABLE to pause any reminder. Schedules run in the church's America/Chicago time.",
            list("Go to General → Reminders and open Event Reminders, Periodic Reminders or One-time Reminders.",
                 "Fill in the event or type, occurrences or date, recipients and templates.",
                 "Choose Notification Channels (📧 Email, 💬 SMS; 📱 WhatsApp sending is currently paused).",
                 "Click 💾 Save. Edit, Delete (and Delivery for event reminders) are on each row."),
            list("Problem: an event reminder reached nobody — Fix: only registrants who answered Yes or Maybe receive it; open Delivery for the reason each attempt was skipped or failed.",
                 "Problem: wrong send time — Fix: times are evaluated in America/Chicago, not the viewer's local zone.",
                 "Problem: nothing sent on a Trial/Demo account — Fix: Trial/Demo accounts never message real people; a verified test address gets one test email.",
                 "Problem: a reminder is listed but not sending — Fix: make sure DISABLE is not ticked and the church subscription is active."),
            "2.0", list("v2.0 — Rewritten for the Reminders hub: event reminder occurrences, clickable template placeholders with preview, Delivery report, full periodic type list, one-time reminders.", "v1.3 — Holiday + members-only celebrant types with custom templates.", "v1.1 — Visitors + group recipients.", "v1.0 — Initial release."),
            list("reminder","event reminder","periodic","one-time","birthday","anniversary","holiday","meeting reminder","delivery report","placeholders","sms","whatsapp")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("income", "Accounting", "Recording Income & Contributions", "accounting.income", false,
            "Record tithes, offerings and other gifts on the Income page — by typing, scanning a check, or by voice.",
            "Accounting → Income has an Add Income form above a list of recent transactions. Fund (your sub categories), Date, Method (your transaction types) and Amount are required; Contributor, Ref No and Notes are optional. A person who is not in the directory can be entered with \"Not a church member? Enter name manually\". Ticking 🔁 Recurring also keeps the entry as a template in the Recurring Income panel on the Home dashboard. 📷 Scan Check reads a photo or PDF of one check and fills the form (the image is stored with the entry). 🎤 Voice and 🗣 Converse fill the form from speech; Converse can also save after you say \"Save Now\" and confirm. The list shows 10 rows at a time (Load More ↓); 📝 edits and 🗑 deletes a row.",
            list("Open Accounting → Income.",
                 "Pick the Contributor (or click \"Not a church member? Enter name manually\").",
                 "Choose the Fund (eg:Tithe), Date and Method (eg:Cash), and enter the Amount.",
                 "Optionally add Ref No and Notes, and tick 🔁 Recurring for repeating gifts.",
                 "Click Save and confirm \"Do you want to add this income?\".",
                 "Optional: click 📷 Scan Check → 📸 Take Photo or 📂 Upload Image / PDF → Use These Values, then Save."),
            list("Problem: the form or the 📝 / 🗑 buttons are missing — Fix: the Income \"Add or Edit\" / \"Delete\" permission is off for your user; ask the church owner (Users page).",
                 "Problem: a fund or method is missing from the list — Fix: add it under 💼 ACCOUNT SETTINGS → Fund (sub category) or Transaction Type.",
                 "Problem: the scan did not read the check — Fix: retake it flat, in good light and in focus, or click Enter Values Manually; a warning appears when the numeric and written amounts differ.",
                 "Problem: voice buttons are disabled — Fix: the browser has no speech support, or voice is turned off / over its limit for your church; type instead.",
                 "Problem: can't find one person's gifts — Fix: the Income page has no search; use Report → Income Reports with the Member filter."),
            "2.0", list("v2.0 — Rewritten for the current page: Recurring (dashboard Quick Add), Scan Check, Voice/Converse, paging with Load More, guest contributor, Add or Edit / Delete permissions.",
                        "v1.2 — Check scan (OCR/vision) + voice entry.", "v1.0 — Initial release."),
            list("income","contribution","giving","tithe","offering","check","scan check","recurring","quick add","voice","converse","load more")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("expense", "Accounting", "Recording Expenses", "accounting.expense", false,
            "Record church expenses on the Expense page, optionally by scanning a check or by voice.",
            "Accounting → Expense has a New Expense form: Expense (the category, from your Purposes), Fund (your main categories), Date, Method and Amount are required; Ref No and Notes are optional. 🔁 Recurring keeps the entry as a template in the Recurring Expense panel on the Home dashboard. 📷 Scan Check fills the amount, date, check number and memo from a photo/PDF of a check — you still pick the Expense category and Fund. 🎤 Voice and 🗣 Converse work as on the Income page. The list shows 10 rows at a time (Load More ↓); 📝 edits (💾 Update Expense) and 🗑 deletes.",
            list("Open Accounting → Expense.",
                 "Search and pick the Expense category.",
                 "Choose the Fund, Date and Method, and enter the Amount.",
                 "Optionally add Ref No and Notes, or tick 🔁 Recurring.",
                 "Click Save and confirm \"Do you want to add this expense?\"."),
            list("Problem: category missing — Fix: add it under 💼 ACCOUNT SETTINGS → Purpose.",
                 "Problem: fund missing — Fix: add a main category under 💼 ACCOUNT SETTINGS → Fund.",
                 "Problem: can't edit or delete — Fix: requires the Expense \"Add or Edit\" / \"Delete\" permission."),
            "2.0", list("v2.0 — Rewritten for the current page: Recurring, Scan Check (category & fund picked after), Voice/Converse, Load More paging.",
                        "v1.1 — Check/receipt scan + voice entry.", "v1.0 — Initial release."),
            list("expense","spending","bill","payment","purpose","scan check","recurring","voice")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("bank_import", "Accounting", "Importing Bank Statements", "accounting.bankimport", false,
            "Import bank or credit-card transactions from a statement file or photo and save them as Income or Expense.",
            "Accounting → Bank Import reads .csv, .ofx, .qfx, PDF or a photo/screenshot (JPG / PNG / HEIC) of a statement, or a photo taken with 📷 Take Photo. Upload & Categorize shows a summary and a list with a suggested category, fund and confidence for each row; filter by Fund, Category, Type and Min confidence. → Income / → Expense opens an editor (⇄ Switch to change type) where you confirm contributor or category, fund, date, amount, method, Ref No and Note, then Save to Income / Save to Expense. A likely duplicate offers Skip, Save as New or Update / Overwrite (Save as New is not offered when the exact line was already imported). Trial and demo accounts get 📄 Load sample statement.",
            list("Open Accounting → Bank Import.",
                 "Choose a file (or 📷 Take Photo) and click Upload & Categorize.",
                 "Click → Income or → Expense on a row, check the fields, then Save.",
                 "Answer any duplicate warning with Skip, Save as New or Update / Overwrite."),
            list("Problem: photo not read — Fix: retake it clearly or upload a CSV/OFX or PDF statement.",
                 "Problem: \"Unsupported file type\" — Fix: export the statement as .csv, .ofx or .qfx, or use a PDF/photo."),
            "2.0", list("v2.0 — → Income / → Expense editor, ⇄ Switch, duplicate choices, filters, sample statement on trial accounts.",
                        "v1.1 — Photo/PDF scan with OCR + duplicate warnings.", "v1.0 — CSV/OFX/QFX import + categorizer."),
            list("bank","import","statement","csv","ofx","qfx","pdf","photo","duplicate","sample statement")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("reports", "Accounting", "Accounting Reports", "accounting.reports", false,
            "Income, Expense, Transactions, year-end Tax and annual Financial reports, each printable as PDF.",
            "Accounting → Report opens Income Reports (filter by Member and Sub-Category), Expense Report (filter by Expense), Transactions Report (income, expense and net balance; tick \"Online Donations & Contributions\" to list only online gifts that were saved to accounting, with fees and deposited amounts), Tax Report (year-end giving letters for one contributor or everyone, with editable letter text and signature Name/Title) and Financial Reports (a year's income by main category and sub-source, expenses by main category and purpose, and net balance). Date-range reports have a Quick Period list (Year to Date, Last 1–5 months). Rows can be edited (✏️ opens the Income/Expense page) or deleted from the Income, Expense and Transactions reports.",
            list("Open Accounting → Report and pick a report.",
                 "Choose Start/End Date or a Quick Period (Tax and Financial: Financial Year).",
                 "Add filters if needed and click Generate Report.",
                 "Click ⬇ Download PDF.",
                 "Tax Report wording: click ✎ Edit letter text, change the text or the signature Name/Title, then Save letter text (Restore original wording undoes it)."),
            list("Problem: totals look off — Fix: confirm the dates and that entries are in the right fund.",
                 "Problem: an online gift is missing — Fix: it appears only after it is saved to accounting on Donation/Give.",
                 "Problem: a fee shows \"Not recorded\" — Fix: no Stripe fee Expense exists yet for that gift; it is left out of Total Fees and Total Deposited."),
            "2.0", list("v2.0 — Rewritten: the five reports, Quick Period, Online Donations & Contributions option, editable tax letter text and signature.", "v1.0 — Initial release."),
            list("report","reports","income report","expense report","transactions","tax report","giving statement","financial report","quick period","online donations")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,Accountant,Church
        add(new HelpArticle("payroll", "Accounting", "Running Payroll", "accounting.payroll", false,
            "Set up employees, run payroll, hand out paystubs and produce W-2 figures.",
            "Accounting → Payroll has Employees, Payroll Runs, Reports and Activity. Employees: + New, profile, Pay setup (Salary/Hourly, Pay frequency, State tax code, Annual salary or Hourly rate, optional Bank name and DD type), Form W-4 and assigned deductions from the Deduction catalog (pre-tax or post-tax). Social Security numbers and bank account numbers are not collected. Payroll Runs: Create run (Frequency, Period start/end, Pay date), process employees (salaried automatically, enter hours for hourly), then Submit for approval → Approve → Mark paid; Void cancels an unpaid run. Paystubs: View PDF / Download. Reports: Year-end W-2 (Boxes 1–6, 17) with W-2 PDF per employee and Download W-2 CSV (all), plus each run's payroll register and employer tax liability. Activity: audit log of payroll changes. Admin logins see only Payroll inside Accounting.",
            list("Open Accounting → Payroll → Employees, add each employee and Save W-4.",
                 "Open Payroll Runs, Create run for the period.",
                 "Process employees (enter hours for hourly staff).",
                 "Submit for approval → Approve → Mark paid; download paystubs.",
                 "At year end use Reports → Year-end W-2."),
            list("Problem: can't delete an employee — Fix: employees with payroll history can only be Deactivated.",
                 "Problem: access denied — Fix: Payroll needs the Payroll permission and an Admin, Accountant or SuperAdmin login."),
            "2.0", list("v2.0 — SSN and bank-account last-4 removed from the employee form; run approval steps, W-2 PDF/CSV, register & tax liability, Activity log documented.",
                        "v1.0 — Initial release with engine, pay stubs, W-2, reports, RBAC."),
            list("payroll","employee","pay stub","paystub","w-2","w-4","wages","taxes","deduction","pay run")).roles("SuperAdmin,Admin,Accountant"));

        // roles: Church
        add(new HelpArticle("ntag_login", "Admin", "NTAG Temporary Login (NFC)", null, true,
            "Register NFC tags so helpers sign in by tapping a tag, entering a PIN and an emailed code.",
            "The NTAG Login page is for the church owner login and needs the Customize NTag plan feature. Register a tag by tapping it with 📶 Tap tag (Chrome on Android) or typing the NTAG serial; enter Holder name, Email (for the code), optional Phone and Link to user, Session role (User or Admin), a 6-digit PIN, Require PIN / Require email OTP, optional Valid from / Valid until, and the Allowed pages; then Save Tag. "
          + "Helpers sign in on the Temporary Access login with 🔐 Login with NTAG: tap the tag (or type the NTAG ID), enter the PIN (Verify PIN), then the emailed code (Verify & Log In). The emailed code expires after 10 minutes. "
          + "Registered Tags offers Edit, Disable/Enable, Reset PIN, History, Revoke (for a lost tag) and Delete; Login History lists every attempt.",
            list("Open the NTAG Login page.", "Click 📶 Tap tag and hold the tag to the phone (or type the serial).", "Fill in the holder details, PIN and Allowed pages, then click Save Tag.", "Helpers use 🔐 Login with NTAG on the Temporary Access login page."),
            list("Problem: \"This NTAG tag is not recognized\" — Fix: the serial does not match an enabled registration; re-register by tapping the tag.",
                 "Problem: \"This NTAG tag is not valid at this time\" — Fix: check Valid from / Valid until.",
                 "Problem: \"Too many attempts\" — Fix: tap the tag again to start over.",
                 "Problem: no email code — Fix: check the holder's Email on the tag and the spam folder."),
            "1.2", list("v1.2 — Corrected location (church owner login), field names, sign-in steps and messages.", "v1.1 — Show scanned serial on 'not recognized' for self-diagnosis.", "v1.0 — Initial 3-factor NTAG login."),
            list("ntag","nfc","tag login","temporary login","pin","otp","revoke tag")).roles("Church"));

        // roles: Church
        add(new HelpArticle("ntag_allowed_pages", "Admin", "NTAG Allowed Pages", null, true,
            "Limit an NTAG login to the pages you tick.",
            "Each NTAG tag has an Allowed pages list: Meetings, Events, Event Check-in, Kids Ministry, Worship Planning, Sunday School and Volunteers. The holder sees only those pages and lands on the first one; anything else is refused. Accounting and admin pages can never be granted.",
            list("Open the NTAG Login page and click Edit on the tag.", "Tick the Allowed pages.", "Click Save Tag."),
            list("Problem: the holder sees Access Denied everywhere — Fix: no pages are ticked; tick at least one.",
                 "Problem: changes don't show — Fix: the holder should sign in again."),
            "1.1", list("v1.1 — Current page list and location.", "v1.0 — Strict frontend + backend allowed-pages enforcement."),
            list("ntag","allowed pages","restrict","access")).roles("Church"));

        // roles: SuperAdmin,Admin,Church
        add(new HelpArticle("ntag_landing", "Admin", "NTAG Landing Page & Buttons", null, true,
            "Customize the branded public page visitors see when they tap your NTAG tag.",
            "SuperAdmins and Admins open it from More → Public Screens → Customize NTAG Page →; the church owner login can open the NTAG Landing Page directly. "
          + "Public Link: tick Page enabled (visitors can view it) and Copy the URL to write to your tags. Branding: Theme color, Show church logo, Welcome message, optional Banner image. "
          + "Buttons: ＋ Add button, then set icon, label, Main or Footer, URL and On; ↑ ↓ reorder and ✕ removes. Website, Facebook, Instagram and YouTube buttons take their address from Church Settings; buttons with an empty URL are hidden. "
          + "Check the Live Preview, then click 💾 Save & Publish (Reset reloads the saved version). Analytics shows views, clicks by button and recent activity.",
            list("Open More → Public Screens → Customize NTAG Page →.", "Tick Page enabled and set the Branding.", "Add and order the buttons.", "Click 💾 Save & Publish and copy the Public Link to your tags."),
            list("Problem: a button doesn't show on the public page — Fix: it must be On and have a URL.",
                 "Problem: edits don't appear — Fix: click 💾 Save & Publish, then ↻ refresh the preview."),
            "1.3", list("v1.3 — How to reach the editor; Church Settings links; field names.", "v1.2 — Add-button confirmation toast + refreshed styling.", "v1.1 — Footer social links + Connect/Prayer buttons.", "v1.0 — Initial landing page."),
            list("ntag","landing page","buttons","branding","tap","nfc page")).roles("SuperAdmin,Admin,Church"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("public_engagement", "Members", "Connect With Us & Public Prayer Request Forms", "more.followups", false,
            "Public visitor-card and prayer-request forms that land in Follow-Ups, with automatic confirmations.",
            "Generate the links on More → Public Screens (Select Page: Connect With Us, or Prayer Request (Public)); the NTAG tap landing page has buttons for both. Connect With Us asks for name, email, phone, address, gender, marital status, birth date, Contact Preferences (Text Message, Email, Phone Call) and How Did You Hear About Us?. Each submission creates the person as a Visitor (a new family in Families), a 'Welcome new visitor' follow-up task and a row in Follow-Ups → 🤝 Connect With Us; a confirmation email is sent when an email was given, and a text when Text Message was chosen and a phone given. The Prayer Request form needs first and last name, email and the request, plus an optional phone and 'Can we share this request with the Prayer Team?'; it creates a row in Follow-Ups → 🙏 Prayer Requests (Source shows where it came from) and a follow-up task, and emails a confirmation. Both forms have spam protection and a per-device rate limit. Revoking or expiring the link on Public Screens stops new submissions. Deleting a Connect card keeps the visitor record and follow-ups; deleting a prayer request removes its notes and assignment too.",
            list("Go to More → Public Screens.",
                 "Select Page: Connect With Us or Prayer Request (Public); optionally set an Expiration Date.",
                 "Click Generate Link, then Copy, and share it.",
                 "Handle submissions in More → Follow-Ups → 🤝 Connect With Us or 🙏 Prayer Requests (Manage → Assign, Status, notes)."),
            list("Problem: a visitor can't submit — Fix: check the link is Active and not expired on Public Screens, and the required fields are filled; after several quick submissions they must wait before trying again.",
                 "Problem: a submission isn't found — Fix: Connect cards are in Follow-Ups → 🤝 Connect With Us (the visitor is also in Families as a Visitor); prayer requests are in Follow-Ups → 🙏 Prayer Requests.",
                 "Problem: the Confirmation column says NONE — Fix: the visitor gave no email (and no phone with Text Message chosen), or sending failed."),
            "2.0", list("v2.0 — Submissions now managed in Follow-Ups tabs; confirmation email/SMS, spam protection, bell notifications; link revocation stops submissions.",
                        "v1.0 — Connect + Public Prayer forms with auto follow-up and management."),
            list("connect","connect with us","public prayer","prayer request form","visitor","connection card","public form","confirmation"))
            .roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("public_screens", "Admin", "Public Screens & Links", "more.publicscreens", false,
            "Create secure public links to church pages that visitors open without signing in; revoke them at any time.",
            "More → Public Screens. Select Page offers Event Calendar, Membership Form, Donation Page, Member Signup, SMS Opt-In Form, Guess It, Kids Check-In, Connect With Us and Prayer Request (Public). "
          + "Options your plan does not include are not offered (Member Signup needs Member Portal, Kids Check-In needs Kids Check-ins, Donation Page needs Online Giving, Guess It needs Activity Corner); Guess It and the Donation Page are not available on Trial or Demo accounts. "
          + "Optionally set an Expiration Date (empty = never expires), click Generate Link and Copy. For the Membership Form you can show a Declaration / Statement of Faith checkbox with your own text, and 🖨️ Print / Download Form (PDF). "
          + "Generated Links lists each link with Status, Expires and Created; the delete icon revokes a link immediately for everyone who has it. Financial and admin pages can never be made public. "
          + "The 📲 NTAG Tap Landing Page box opens the NTAG landing page editor.",
            list("Open More → Public Screens.", "Choose a page in Select Page and optionally an Expiration Date.", "Click Generate Link, then Copy.", "To switch a link off, click its delete icon and confirm."),
            list("Problem: a page is missing from Select Page — Fix: your plan does not include it, or it is not available on Trial/Demo accounts.",
                 "Problem: a printed QR code stopped working — Fix: the link was revoked or expired; generate a new link."),
            "1.2", list("v1.2 — Full page list, plan/trial rules, Membership Form options, revoke behaviour.", "v1.1 — Added Connect With Us and Public Prayer link types.", "v1.0 — Initial release."),
            list("public link","public screens","share","qr","generate link","revoke","event calendar","membership form","donation page","kids check-in")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("certificates", "Ministry", "Certificates", "more.certificates", false,
            "Create, download and print church certificates of eight types.",
            "More → Certificates offers: Baptism Certificate, Certificate of Appreciation, Dedication Certificate, Completion Certificate, Sunday School Completion, Church Membership, Marriage Certificate and Other Church Certificate. Each opens a live preview with an Edit Certificate panel: search a member or type the name, fill in the type's details (date, pastor/teacher/presenter, program or class, etc.), adjust the name style and scripture. ⬇ Download PDF and 🖨 Print produce the certificate; ↺ Reset restores the defaults. Every type except Baptism has 💾 Save Record, which stores the certificate and shows a record number. The Baptism Certificate always uses today's date; use Body Text Override for another date.",
            list("Open More → Certificates.",
                 "Choose a certificate type.",
                 "Search Member or type the name; fill in the details.",
                 "Click ⬇ Download PDF or 🖨 Print.",
                 "Optionally click 💾 Save Record (not available on Baptism)."),
            list("Problem: PDF generation failed — Fix: use 🖨 Print and choose Save as PDF in the browser.",
                 "Problem: the baptism date is wrong — Fix: it is always today's date; write the wording you need in Body Text Override.",
                 "Problem: 'Please sign in to save records' — Fix: your session expired; sign in again and click Save Record."),
            "2.0", list("v2.0 — Eight certificate types, member search, live editing, Download PDF / Print, Save Record with record number.",
                        "v1.0 — Initial release."),
            list("certificate","baptism","membership","marriage","dedication","appreciation","completion","print certificate")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Church,Member
        add(new HelpArticle("ticketing", "Help", "Ticketing — Support Tickets", "more.ticketing", false,
            "Send a support request (ticket) to the ChurchGeniusPro support team and follow your church's tickets.",
            "More → Ticketing (church owner login: Admin → Ticketing). Click ＋ New Ticket, enter the Subject (Name and Email are pre-filled), an Error Description with the complete error message and steps to reproduce it, and the Urgency (Low, Medium, High), then Submit. "
          + "You get a reference number (TKT-…) on screen and by email. Submitted Tickets lists every ticket your church has raised, filterable by Open / Closed and searchable; View shows the details. "
          + "When support changes a ticket's status you are normally emailed. Tickets cannot be edited or deleted — submit a new one quoting the reference. "
          + "Staff have Ticketing unless it is unticked; Member Portal logins need it ticked; Kids Portal logins never have it. On a Trial account support may be limited, but every ticket is reviewed.",
            list("Open More → Ticketing and click ＋ New Ticket.", "Fill in Subject, Error Description and Urgency.", "Click Submit and note the TKT- reference number.", "Use View in Submitted Tickets to follow it."),
            list("Problem: Ticketing is missing from the menu — Fix: the Ticketing permission is off for your login (Member Portal logins need it ticked)."),
            "1.1", list("v1.1 — Current form fields, ticket list and permission rules.", "v1.0 — Initial release."),
            list("ticket","ticketing","support ticket","support","raise a ticket","contact support","tkt")).roles("SuperAdmin,Admin,Accountant,User,Church,Member"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Church,Member
        add(new HelpArticle("ai_assistant", "Getting Started", "AI Search & the AI Assistant", "more.aiassistant", false,
            "Ask in plain English to open a page, look up church data, get report totals, create a meeting or learn how to do something — by typing, voice or conversation.",
            "The ✨ AI Search bar is at the top of every page; More → AI Assistant (church owner login: Admin → AI Assistant) is the same assistant as a chat. "
          + "Buttons: 🔎 Type, 🎤 Voice (saying a page name opens it), 🗣 Converse (the assistant asks follow-up questions), ❔ Guide (every command available to your login) and ? Help. "
          + "It can navigate (\"Go to Payroll\"), answer data questions (\"What was the income last month?\"), give report totals for a period with a link to the pre-filled report, and create a one-time meeting after you confirm the draft. "
          + "Lists show the first 20 rows. Answers follow the same role, permission and plan rules as the pages. On Income, Expense and Meeting screens the AI can also fill the form from a sentence. "
          + "The chat page keeps the conversation only in the current browser tab; 🗑 Clear conversation starts again. "
          + "Staff have the AI Assistant permission unless it is unticked; Member Portal logins need it ticked; Kids Portal logins never have it. Voice and Converse can be limited by the church's plan and need microphone access in a recent Chrome, Edge or Safari.",
            list("Type a question in the ✨ bar and press Enter.",
                 "Click 🎤 to speak, or 🗣 to have a conversation.",
                 "Click ❔ to browse every command available to you.",
                 "Open More → AI Assistant for a chat window."),
            list("Problem: no ✨ bar — Fix: the AI Assistant permission is off for your login.",
                 "Problem: Voice is not available — Fix: Voice is switched off for your church or plan, the limit is reached, or the browser lacks microphone permission; Type always works.",
                 "Problem: \"not enabled for your current role, permissions or plan\" — Fix: an administrator can enable the feature for you."),
            "1.2", list("v1.2 — Guide and Help buttons, report totals, meeting creation, chat page details, permission rules.", "v1.1 — Chat-style AI Assistant page; feature questions answered for every feature.", "v1.0 — AI Search bar with Type / Voice / Converse."),
            list("ai","assistant","ai search","voice","converse","guide","ask","go to page","create meeting")).roles("SuperAdmin,Admin,Accountant,User,Church,Member"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("donation_give", "Accounting", "Donation / Give — Online Giving", "accounting.donation", false,
            "Review online gifts paid through the church's Stripe account and save them to accounting.",
            "Online gifts come from the public donation page (💝 Donations tab) and from members giving in the Member Portal (🙋 Member Contributions tab). They are NOT posted to Income automatically. Set up ⚙ Donation Settings once (Stripe fee rate, Income fund and method, Stripe fee Expense type, fund, method and date). Then click Save on a gift (or Review & Save to adjust and link an existing person, or tick several and ↗ Save selected to accounting): the full gift is recorded as Income and the Stripe fee as a separate Expense, both carrying the DON-… reference as Ref #. Saving marks the gift Completed. ✓ Mark Completed / ↺ Mark Pending change only the review mark, and the Pending Total / Pending Donations / Pending After Stripe Fees cards count Pending gifts only. The ⓘ next to an amount shows the Donation, Stripe fee and Remaining using the Stripe Fee % and Fixed Fee boxes (an on-screen estimate). Online giving itself is connected by the church owner under Admin → Stripe Integration, and the donation page link is shared from Public Screens.",
            list("Church owner: Admin → Stripe Integration — connect Stripe.",
                 "Share the donation page link from More → Public Screens.",
                 "Accounting → Donation/Give → ⚙ Donation Settings: fill in the required fields and Save settings.",
                 "Click Save (or Review & Save → Save to accounting) on each gift, or use ↗ Save selected to accounting.",
                 "Use ✓ Mark Completed / ↺ Mark Pending for reconciliation."),
            list("Problem: Save buttons disabled with a yellow banner — Fix: complete the required Donation Settings.",
                 "Problem: \"est.\" or \"fee pending\" — Fix: est. = fee estimated from your rate; fee pending = Stripe has not reported the fee yet.",
                 "Problem: \"In Income #… (auto)\" — Fix: the gift was posted by the older automatic process; it is not saved again.",
                 "Problem: donors see \"Online giving is not configured\" — Fix: the church owner must complete Stripe Integration."),
            "2.0", list("v2.0 — Gifts are no longer posted automatically: Donation Settings, Save / Review & Save / Save selected to accounting, DON- reference, fee popover and Remaining, Pending-only totals.",
                        "v1.1 — Member Contributions tab; Pending/Completed review.", "v1.0 — Donation review."),
            list("donation","donations","give","giving","online giving","stripe","member contributions","donation settings","stripe fee","save to accounting","mark completed")).roles("SuperAdmin,Accountant"));

        // roles: Member
        add(new HelpArticle("member_portal", "Member Portal", "Member Portal", null, false,
            "What members see when they sign in: home calendar, family, giving, groups and chat, volunteering, directory, classes, Song Book and Guess It.",
            "Members sign in with their own username and password (created from the church's Member Signup link) and land on the Member Portal home: upcoming events and meetings, upcoming birthdays and anniversaries, the church calendar and Sync Calendar. The My Profile menu has Family, Contributions, Groups, Classes, Volunteer, Directory and Song Book; Activity Corner has Guess It. The top bar has the notification bell, Settings (gear) and Logout, plus Switch Account when the church has linked two or more of the person's logins. The church owner login chooses which My Profile sections each member sees; Ticketing and the AI Assistant appear for a member only when the church ticks them. A login whose family role is Child opens the simpler Kids Portal instead.",
            list("Sign in with your username and password.",
                 "Use My Profile: Family, Contributions, Groups, Classes, Volunteer, Directory, Song Book.",
                 "Click your church name at the top of the menu to return to the home screen.",
                 "Use the bell for notifications, the gear for Settings, and Logout when done."),
            list("Problem: a menu item is missing — Fix: the church has not turned it on for your login, or it is not in the church's plan; ask the church office.",
                 "Problem: you are sent back to the sign-in page — Fix: the session ended after inactivity; sign in again, or tick Don't log out automatically in Settings on your own device.",
                 "Problem: Song Book says you don't have access — Fix: the church grants Song Book access per person; ask the administrator."),
            "2.0", list("v2.0 — Rewritten for members: signup link, home screen, menu, Switch Account, church-controlled sections, opt-in Ticketing / AI Assistant, Kids Portal.", "v1.0 — Initial release."),
            list("member portal","portal","member home","member login","my profile","switch account","kids portal")).roles("Member"));

        // roles: SuperAdmin,Admin  (server also lets the Church login open the page, but its menu hides it; Member-portal logins with admin.membership)
        add(new HelpArticle("membership_requests", "Members", "Membership Requests & Membership Form", "admin.membership", true,
            "Collect membership applications and renewals online, review every detail, then approve or apply them.",
            "Create the public Membership Form link on More → Public Screens (Select Page: Membership Form), optionally with a Declaration / Statement of Faith that applicants must accept and an expiration date. Existing members enter their phone or email, verify with a 6-digit code emailed to the address on file, and review their pre-filled family; new families choose 'New Member? Complete the full form'. Each submission appears in Admin → Membership Requests as New Member or Renewal / Update with status Pending Review; Admins and SuperAdmins are emailed and a 📝 notice appears in the 🔔 bell. Review Submitted Form shows every field and, for renewals, the exact changes (old → new, new members). ✅ Approve records the decision only. ✅ Approve and Update applies the changes to the existing family in one step and sets the membership renewal date (status Updated); for a new family the button is ✅ Approve and Add as Member, which creates the family with Member Type 'Member'. Filled-in fields are never overwritten with blanks, and a failed update changes nothing. ↩ Back to Pending Review / ↩ Set to Approved change only the status. 🗑 Delete Request (its own permission, 'Delete request') removes the request but never the family. 🔐 Approve Login Account activates a requested Member Portal login.",
            list("Share the Membership Form link from More → Public Screens.",
                 "Open Admin → Membership Requests.",
                 "Click Review Submitted Form (N members) and check the details and changes.",
                 "Click ✅ Approve (status only) or ✅ Approve and Update / ✅ Approve and Add as Member.",
                 "If a login was requested, click 🔐 Approve Login Account."),
            list("Problem: the applicant sees 'No Record Found' — Fix: their phone/email isn't on any family; they can complete the full form as a new member.",
                 "Problem: the applicant didn't get the verification code — Fix: it goes to the family's email on file; they can click Resend code, or add an email to the family first.",
                 "Problem: 'The family this request refers to no longer exists' — Fix: Approve and Update isn't possible; approve or delete the request.",
                 "Problem: 🗑 Delete Request is missing — Fix: your login needs the Membership Requests 'Delete request' permission."),
            "2.0", list("v2.0 — Approve vs Approve and Update / Approve and Add as Member, change preview, statuses Pending Review / Approved / Updated with revert, safe Delete Request, declaration, verification code, bell notice.",
                        "v1.1 — Full review screen with every submitted field.", "v1.0 — Initial release."),
            list("membership request","membership form","application","apply","renewal","approve","approve and update","declaration","statement of faith"))
            .roles("SuperAdmin,Admin"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("pledges", "Accounting", "Pledges", "accounting.pledges", false,
            "Run pledge campaigns and track each person's pledge against what they have given.",
            "Accounting → Pledges lists campaigns with Pledged, Collected, Pending, Pledgers and progress toward the Target Amount. A campaign has a name, a Subcategory (Fund), Status (Active / Closed / Archived), optional Target Amount, End Date, Other Gifts (non-cash) and Description. Manage Pledges → + Add Pledge records a member (or a walk-up guest name) with a Pledge Amount and optional Monthly Amount. Collected for a member pledge is the member's income to the campaign's fund dated from the day the campaign was created up to its End Date; guest pledges are not matched automatically. Members see 🤝 My Pledges in the Member Portal.",
            list("Open Accounting → Pledges and click + New Campaign.",
                 "Enter the Campaign Name and Subcategory (Fund); set Target Amount / End Date if wanted; Save.",
                 "Click Manage Pledges → + Add Pledge, pick the member and enter the Pledge Amount; Save."),
            list("Problem: Collected stays at zero — Fix: the income must be recorded to the campaign's fund, for that member, on or after the date the campaign was created.",
                 "Problem: deleting a campaign — Fix: its pledges are hidden too; confirm before deleting."),
            "1.1", list("v1.1 — Documented campaign fields, guest pledges and how Collected is counted (campaign window).", "v1.0 — Initial release."),
            list("pledge","pledges","campaign","building fund","collected","target")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("bank_sync", "Accounting", "Bank Sync (Connect a Bank Account)", null, false,
            "Connect bank accounts so new transactions arrive in a review queue; approve them into Income or Expense.",
            "Accounting → Bank Sync connects the church's bank accounts through Plaid. Paid accounts must first enter a 6-digit code emailed to them (15 minutes, single use, 5 wrong tries max; \"Remember this device for 30 days\" skips it on that device; the church owner can generate a one-time code with 🏦 Code on the Users page). + Connect a bank opens the bank's sign-in; each account selected counts toward the plan's account limit (shown as \"x of y bank accounts connected\"). New transactions arrive automatically (Sync now fetches immediately) into the Review Queue, grouped by account and filterable by Status (Pending / Needs attention / Approved / Rejected / All) and Type. Set the Category mapping and Approve to post to Income/Expense, or Reject; Approve selected / Reject selected work on ticked rows (rows missing a category are skipped). Refunds are never posted. Delete on a bank (confirmed by an emailed code) removes its pending transactions; approved entries stay. Trial and demo accounts skip the code and use Plaid test banks only (user_good / pass_good). Bank Sync must also be enabled for the church by ChurchGeniusPro support.",
            list("Open Accounting → Bank Sync and enter the emailed code.",
                 "Click + Connect a bank and sign in to the bank.",
                 "In the Review Queue set the Category mapping on each row.",
                 "Click Approve or Reject (or tick rows and use Approve selected / Reject selected)."),
            list("Problem: + Connect a bank is disabled — Fix: the plan's account limit is reached; delete a connection or upgrade.",
                 "Problem: \"Bank Sync is not included in your plan.\" — Fix: upgrade to a plan with Bank Sync.",
                 "Problem: \"Bank sync is not enabled for your organization.\" — Fix: contact support to enable it.",
                 "Problem: bank shows Needs sign-in / Connection problem — Fix: follow the message under the bank; usually Delete and connect again.",
                 "Problem: Approve is greyed out — Fix: the transaction is still pending at the bank.",
                 "Problem: \"Too many incorrect codes\" — Fix: click Resend Verification Code."),
            "2.0", list("v2.0 — Verification gate and trusted device, plan account limit, review queue filters, bulk approve/reject, duplicate check, delete with email code, readable sync errors; permKey corrected (Bank Sync has no permission checkbox).",
                        "v1.0 — Initial release."),
            list("bank sync","connect bank","plaid","bank account","sync","review queue","approve","verification code","trusted device")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Admin,User,Member
        add(new HelpArticle("worship_planning", "Ministry", "Worship Planning", "general.ministry.worship", false,
            "See the 12-week worship schedule; set up groups and build it automatically by rotation or by hand.",
            "General → Ministry → 🎵 Worship Planning shows, for each worship group, the next 12 weeks: dates, the members on each instrument, and the songs, with ➡ marking the next service and your own name starred. Print or download per group or for all groups. 🎵 Manage Worship opens Group Setup (+ Add Group, + Instrument, + Member with an optional Rotation Order) and Assignments, which has two independent workflows. Automatic Assignment rotates members with a Rotation Order over the chosen weekday, start date and number of weeks (Start Order, Skip specific dates; Generate replaces the group's automatic schedule). Manual Assignment adds empty dates (+ Add Empty Dates) that you fill with + Add or by dragging from the roster; ⊘ Skip keeps the people so ↩ Unskip restores them. The two never overwrite each other. 📚 Manage Songs keeps a group song library and ✎ Songs sets the songs for a date. Member Portal users reach the same pages from their 🎵 Worship Planning tab.",
            list("Open General → Ministry → 🎵 Worship Planning.",
                 "Click 🎵 Manage Worship → Group Setup → + Add Group, then + Instrument and + Member.",
                 "Assignments → Automatic Assignment: choose Group, Start Order, Day of Week, Start Date, Weeks, skip any dates, click Generate.",
                 "Or Assignments → Manual Assignment: choose Group, Day of Week, Start Date, Weeks, click + Add Empty Dates and fill the cells.",
                 "Add songs with 📚 Manage Songs and ✎ Songs; print with 🖨️ Print or ⬇️ Download PDF."),
            list("Problem: 'No worship assignments have been generated yet' — Fix: nothing is scheduled in the next 12 weeks; add dates in Manage Worship.",
                 "Problem: a member is never picked automatically — Fix: give them a Rotation Order in Group Setup.",
                 "Problem: a skipped date vanished from Worship Planning — Fix: skipped dates are hidden there; ↩ Unskip it in Manage Worship.",
                 "Problem: past manual dates are not listed — Fix: Manual Assignment and printouts show today and future dates only."),
            "2.0", list("v2.0 — Manual Assignment (empty dates, skip/unskip that keeps members, songs per date, print/PDF) alongside Automatic Assignment; next-service ➡ marker; Print All / Download All.",
                        "v1.0 — Initial release."),
            list("worship","worship planning","assignments","worship team","rotation","manual assignment","automatic assignment","songs")).roles("SuperAdmin,Admin,User,Member"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Member
        add(new HelpArticle("song_book", "Ministry", "Song Book", null, false,
            "Collect songs and lyrics in sections, finalize the running order, design a cover, add ad pages, and publish a live link.",
            "My Profile → Song Book. A church can keep several books (+ New Book, ✎ Rename, 🗑 Delete; the default book cannot be deleted). Tabs: 🗂 Sections (sections, songs with language, lyrics via 📜, bulk upload of titles, ✓ to finalize), 🎨 Cover Page (Design Cover with live preview, or upload a Custom Cover Page / Custom Last Page), ✅ Finalized Song Book (final sections and order, ✎ Edit Title, ↩ to return a song to the library), 📢 Ads (image or PDF ad pages placed after the cover, the table of contents or a section) and 🌐 Publish Song Book (Publish / Unpublish, Copy / Open the live link, 🖨 Print / PDF, and 🎵 Now Singing & Next Song, which update everyone's open book within seconds). Staff logins always have Full Access; Member Portal users need View Only or Full granted on Song Book Access. Song Book is a subscription feature.",
            list("Open My Profile → Song Book and choose or create a book.",
                 "🗂 Sections → + Add Section; add songs and lyrics.",
                 "Click ✓ on songs for the book; arrange them on ✅ Finalized Song Book.",
                 "Design the cover on 🎨 Cover Page; add pages on 📢 Ads.",
                 "🌐 Publish Song Book → enter the title → Publish → Copy the link."),
            list("Problem: a member sees 'You don't have access to the Song Book yet' — Fix: an Admin grants View Only or Full on Song Book Access.",
                 "Problem: Now Singing can't select songs — Fix: click Re-publish now on the Publish tab.",
                 "Problem: the public link stopped working — Fix: the book was unpublished; Publish it again.",
                 "Problem: a song and its lyrics are gone — Fix: 🗑 on the Finalized tab deletes permanently; use ↩ to remove a song from the book but keep it."),
            "2.0", list("v2.0 — Multiple song books, tabs (Sections | Cover Page | Finalized | 📢 Ads | Publish), custom cover/last page, ad pages, Now Singing & Next Song; Song Book Access split into its own article.",
                        "v1.0 — Initial release."),
            list("song book","songbook","songs","hymns","lyrics","publish","now singing","ads")).roles("SuperAdmin,Admin,Accountant,User,Member"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("sunday_school", "Ministry", "Sunday School", "general.ministry.kids", false,
            "Classes, teachers, students, lessons, online exams and results — inside Kids Ministry.",
            "Sunday School is the 📚 Sunday School section of Kids Ministry, with four tabs: 🏫 Classes (create classes, add teachers from the member list, class notes, and files with a Visible to students option), 👦 Students & Lessons (add students linked to a child member, track lessons with status, 🔗 Re-link a wrong member), 📝 Exams (questions of six types or 📄 Upload Paper, total/required questions, duration, Send copy to HoH, 🚀 Publish / Close / 🔄 Republish) and 📊 Results & Marks (status, automatic and total marks, ⏱ +Time, 👁 Answers with Save Marks, 📧 Send to Parent, Print). The selected class is shared across the tabs. Students take published exams from the Classes tab of the Member Portal or Kids Portal; Multiple Choice and True / False are marked automatically and written answers are matched against the correct answer for the teacher to review. On submission the teacher is emailed, and the head of household too when Send copy to HoH is on. Linked teachers manage their own classes from the Member Portal.",
            list("Open General → Ministry → Kids Ministry and click 📚 Sunday School.",
                 "🏫 Classes → + New Class; Open the class and + Add Teacher.",
                 "👦 Students & Lessons → select the class → + Add Student (choose the Assigned Teacher and the child member).",
                 "📝 Exams → + New Exam → Manage → + Add Question; set exam settings and Save Settings; click 🚀 Publish.",
                 "📊 Results & Marks → choose class and exam → 👁 Answers → Save Marks → 📧 Send to Parent."),
            list("Problem: a student can't see the exam — Fix: the exam must be Published and the student must be linked to the right child member (use 🔗 Re-link).",
                 "Problem: Exams or Results look empty — Fix: choose a class (and exam) in the selector first.",
                 "Problem: Republish wiped the results — Fix: Republish deliberately clears all submissions so students can retake; it cannot be undone.",
                 "Problem: a teacher can't manage the class in the portal — Fix: add the teacher by picking them from the member list so the record is linked."),
            "2.0", list("v2.0 — Rewritten: Sunday School now lives inside Kids Ministry (📚 Sunday School); exact tab names, exam settings, Republish warning, results emailing and HoH copy.",
                        "v1.0 — Initial release."),
            list("sunday school","class","teacher","student","lesson","exam","marks","results","grade")).roles("SuperAdmin,Admin,User"));

        // roles: Church
        add(new HelpArticle("stripe_integration", "Admin", "Stripe Integration", null, true,
            "Connect the church's own Stripe account for online giving, and check it with Test Connection.",
            "Admin → Stripe Integration (church owner login only) stores one Publishable Key (pk_live_ or pk_test_) and one Secret Key (sk_live_ or sk_test_) per church — separate from ChurchGeniusPro's own billing. Find both in the Stripe Dashboard under Developers → API keys. "
          + "Test Connection checks the typed (or saved) secret key with Stripe and reports live or test mode. Save Keys stores them; afterwards the secret key is hidden and the box can be left empty to keep it. "
          + "With keys in place the Donation Page link (More → Public Screens) and Member Portal giving work, and gifts appear under Accounting → Donation/Give. "
          + "The page needs the Online Giving plan feature and is not available on Trial or Demo accounts. Test keys never take real money.",
            list("In Stripe, open Developers → API keys.", "Admin → Stripe Integration: paste the Publishable Key and Secret Key.", "Click Test Connection.", "Click Save Keys.", "Share the Donation Page link from More → Public Screens."),
            list("Problem: Test Connection fails — Fix: the secret key is wrong or revoked; copy it again from Stripe.",
                 "Problem: the page is refused — Fix: only the church owner login can open it, and the plan must include Online Giving."),
            "1.1", list("v1.1 — Test Connection result, key handling, plan/trial rules.", "v1.0 — Initial release."),
            list("stripe","stripe keys","publishable key","secret key","test connection","online giving setup","payment setup")).roles("Church"));

        // roles: Church
        add(new HelpArticle("whatsapp_integration", "Communication", "WhatsApp Integration", null, true,
            "Store the church's Twilio account details for WhatsApp messages (church owner login).",
            "Admin → WhatsApp Integration is available to the church owner login only. Enter the Twilio Account SID and Auth Token (from the Twilio Console), the WhatsApp-enabled Sender Phone with country code, an optional Default Message (up to 1000 characters) and an optional Meeting Reminder Template SID, then click Save Settings. "
          + "After saving, the Auth Token is never shown again — the page only says it is set. "
          + "WhatsApp sending is currently paused in ChurchGeniusPro; the details are kept, and reminders and notifications still go out by email and SMS.",
            list("Open Admin → WhatsApp Integration.", "Enter Account SID, Auth Token and Sender Phone.", "Optionally add a Default Message and Meeting Reminder Template SID.", "Click Save Settings."),
            list("Problem: \"Account SID is required\" / \"Sender Phone is required\" / \"Auth Token is required\" — Fix: fill in the required field."),
            "1.1", list("v1.1 — Field-by-field steps; WhatsApp sending currently paused.", "v1.0 — Initial release."),
            list("whatsapp","twilio","account sid","auth token","sender phone")).roles("Church"));

        // roles: SuperAdmin,Admin
        add(new HelpArticle("email_settings", "Communication", "Email Settings & Trial Test Email", "general.emailsettings", true,
            "The sender name, signature, footer, logo and daily verse for church emails; on Trial/Demo accounts the verified address that receives test copies.",
            "The Email Settings page (SuperAdmin and Admin; also opened with ⚙ Settings on Compose Email) has Display Name (sender name in inboxes; blank uses the default), Footer Comments, Signature, Add Logo and Daily Verse, saved with Save Settings. "
          + "Daily Verse needs verses — Open Daily Verse Setup → loads a full year in one click. "
          + "On Trial and Demo accounts a 🧪 Trial/Demo Test Email box appears: enter an address, click Send verification code, enter the 6-digit code (valid 15 minutes) and click Verify. Every email action then sends ONE test copy there, marked as a test; nobody else is emailed. Until an address is verified, those emails are not sent at all. "
          + "Unsubscribed people are listed under Admin → Unsubscribed List.",
            list("Open the Email Settings page (or Compose Email → ⚙ Settings).",
                 "Fill in Display Name, Footer Comments and Signature; tick Add Logo / Daily Verse; click Save Settings.",
                 "Trial/Demo: enter your address, click Send verification code, enter the code and click Verify."),
            list("Problem: the daily verse never appears — Fix: load verses in Daily Verse Setup.",
                 "Problem: the verification code is rejected — Fix: codes last 15 minutes; send a new one."),
            "1.2", list("v1.2 — Corrected fields (no SMTP settings); where to find the page; test email steps.", "v1.1 — Trial/Demo verified test email.", "v1.0 — Initial release."),
            list("email settings","display name","signature","footer","daily verse","test email","verified email","sender")).roles("SuperAdmin,Admin"));

        // files_notes removed 2026-10-29: Files & Notes is switched off (menu item and /filesUpload route commented out).


        // roles: SuperAdmin,Admin
        add(new HelpArticle("private_page_access", "Admin", "Private Page Access", null, true,
            "Make Kids Ministry, Event Check-In and the Temporary Access Login work only from approved church networks.",
            "Admin → Private Page Access (SuperAdmin, Admin; needs the Private Page Access plan feature). Networks are matched by public IP address or range, not Wi-Fi name. "
          + "Add a network with ＋ Add Network, name it, paste addresses (one per line; 📍 Detect my IP shows your current address), keep Enabled ticked and click Save. "
          + "Under Restricted Pages tick Kids Ministry, Event Check-In or Temporary Access Login and the allowed networks (none ticked = any approved network). The Master Setting \"Enable Private Page Access\" turns the restriction on. "
          + "Administrators always keep access, logged as an override. When Temporary Access Login is restricted, an encrypted Temporary Access link appears to bookmark on kiosks. Access Audit shows Blocked, Allowed and Overrides with recent attempts.",
            list("On the church network, open Admin → Private Page Access.",
                 "Click ＋ Add Network, use 📍 Detect my IP, paste the address and Save.",
                 "Tick the pages to restrict and their allowed networks.",
                 "Tick Enable Private Page Access."),
            list("Problem: everyone except admins is blocked — Fix: a private page has no enabled network; add or enable one.",
                 "Problem: staff at church are blocked — Fix: the church's IP address changed; update the network with 📍 Detect my IP."),
            "1.1", list("v1.1 — Step-by-step setup, kiosk link and audit.", "v1.0 — Initial release."),
            list("private page","private access","network restriction","ip restriction","kiosk","church wifi")).roles("SuperAdmin,Admin"));

        add(new HelpArticle("temporary_access", "Admin", "Temporary Access & Badges", null, true,
            "Time-limited logins for helpers with printable barcode badges.",
            "Temporary Access (Admin → Users → ⏱️ Temporary Access) gives a helper access to chosen General pages for a set period, using a printable barcode badge and a 6-digit code instead of a username and password. Accounting and admin pages can never be granted. Access ends automatically at the end time, with a warning 15 minutes before. NTAG (NFC) login is the tap-to-sign-in alternative.",
            list("Open Admin → Users and click ⏱️ Temporary Access.", "Enter the holder name, and an email if the code should be emailed to them.", "Set the start and end time and tick the permitted pages.", "Click Generate access & badge and note the 6-digit code — it is shown only once.", "Print the badge; the helper signs in at /tempLogin by scanning it and entering the code.", "Under Active & recent passes, extend (+1h), view sign-in history or revoke a pass."),
            null, "1.1", list("v1.1 — Step-by-step instructions.", "v1.0 — Initial release."), list("temporary access","temporary login","badge","access card","barcode")).roles("Church"));

        add(new HelpArticle("users_permissions", "Admin", "Users, Roles & Permissions", null, true,
            "Staff logins, their roles, and the permission checkboxes that decide which pages each login sees.",
            "Admin → Users (the church account's Accounts page) manages staff logins. Roles: SuperAdmin (everything), Admin (administration and general), Accountant (accounting), User (general), Limited (General section only). "
          + "Add a user with + Add User (no password is set there), then click Invite on their row: they get a sign-up link and choose their own username and password; ReInvite sends a new link that replaces the old one. "
          + "The 🔐 permission checkboxes control pages, menus and buttons for a login; data access follows the role, and changes reach a signed-in user within about 15 seconds. Opt-in features such as Ticketing and AI Assistant are ticked per login. "
          + "🔗 Link Accounts joins logins that belong to the same person (for example a SuperAdmin login and their Member Portal login) so they can switch from the sidebar; linked staff accounts must have different roles and Child accounts cannot be linked. "
          + "The Member Portal Users list shows every Member Portal login: 📝 edits name and email, 🔑 Reset emails a password-reset link, 🔐 sets portal access, and 🗑 removes the login and marks the person deleted in Families.",
            list("Open Admin → Users.", "Click + Add User, enter name, email and role, and click Save User.", "Click Invite on the user's row so they can choose their own username and password.", "Click 🔐 on a row to set permissions, then Save Permissions.", "Use 🔗 Link Accounts to join two or more logins of the same person.", "Scroll to Member Portal Users to edit, reset or set portal access for member logins."),
            list("Problem: Invite is greyed out — Fix: switch the user's Status to Active first.",
                 "Problem: the invitation link no longer works — Fix: click ReInvite; only the newest link is valid.",
                 "Problem: two staff accounts will not link — Fix: linked staff accounts must each have a different role and must have completed sign-up."),
            "1.1", list("v1.1 — Invite, link accounts and member account instructions.", "v1.0 — Initial release."),
            list("users","permissions","roles","superadmin","admin","accountant","add user","invite","reinvite","link accounts","switch account","member accounts","member portal users")).roles("Church"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited
        add(new HelpArticle("favorites", "Getting Started", "Favorites", "favorites", false,
            "Pin the pages you use most to ⭐ Favorites at the top of the left menu.",
            "Point at a page in the left menu and click the ☆ that appears at its right (on touch screens it is always faintly visible); it turns into a gold ★ and the page is added under ⭐ Favorites at the top of the menu. "
          + "Some pages — Ministry, Reminders and Accounting Reports — also have a ☆ in the corner of their sections so a section can be pinned directly. Click the ★ to remove a favorite. "
          + "Favorites are saved to your login and a favorite only shows while you can still open that page. The church owner login has no Favorites, and the stars disappear when the Favorites permission is switched off for a login.",
            list("Point at a page in the left menu.", "Click its ☆ — it becomes a gold ★.", "Open ⭐ Favorites at the top of the menu to use it.", "Click the ★ to remove it."),
            list("Problem: no stars appear — Fix: the Favorites permission is off for your login; ask the church owner login.",
                 "Problem: a favorite disappeared — Fix: you no longer have access to that page."),
            "1.1", list("v1.1 — Hover star, section stars, permission and access rules.", "v1.0 — Initial release."),
            list("favorites","favourite","star","pin page","shortcut")).roles("SuperAdmin,Admin,Accountant,User,Limited"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Member,Church
        add(new HelpArticle("install_app", "Help", "Install the App (Android & iPhone)", null, false,
            "Install the Android app from Google Play, or add the website to your home screen; use fingerprint / Face ID sign-in and notifications.",
            "Android: install the ChurchGeniusPro app from Google Play, or in Chrome open churchgeniuspro.net and tap ⋮ → Install app (or Add to Home screen → Install); an Install banner may also appear. iPhone / iPad: in Safari open churchgeniuspro.net, tap Share → Add to Home Screen → Add. In the Android app, signing in with Remember password ticked lets the next sign-in use the phone's fingerprint, face or screen lock (Sign in with Fingerprint / Face ID), and the option stays after Logout; to turn it off, sign the phone out under Signed-in devices or sign in with Remember password unticked. Allow notifications when asked to receive reminders; logging out stops that phone's notifications. The Kids Portal works on the website only. Updates are automatic; the app may show 'A new version of ChurchGeniusPro is available' with Reload.",
            list("Android: install ChurchGeniusPro from Google Play, or Chrome → ⋮ → Install app.",
                 "iPhone/iPad: Safari → Share → Add to Home Screen → Add.",
                 "Sign in; tick Remember password in the app to use fingerprint / Face ID next time.",
                 "Allow notifications when asked."),
            list("Problem: no Install option — Fix: open the site directly in Chrome or Safari, not inside email, Facebook or WhatsApp.",
                 "Problem: a child cannot sign in to the app — Fix: the Kids Portal is website-only; use the phone's browser.",
                 "Problem: fingerprint sign-in is not offered — Fix: sign in once with Remember password ticked; the phone needs a fingerprint, face or screen lock set up."),
            "2.0", list("v2.0 — Google Play app, fingerprint / Face ID sign-in, notifications, Signed-in devices, Kids Portal web-only.", "v1.0 — Initial release."),
            list("install","app","home screen","android","iphone","pwa","play store","google play","fingerprint","face id","notifications")).roles("SuperAdmin,Admin,Accountant,User,Limited,Member,Church"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Church
        add(new HelpArticle("plans_trial", "Getting Started", "Subscription Plans & Trial Accounts", null, false,
            "Trial, Free, Standard and Pro plans; what a trial limits, how to receive test emails, and how to request a subscription.",
            "Plans are Trial, Free, Standard and Pro, each with monthly allowances (emails, SMS, online giving) that reset every 30 days, and plan-specific features. "
          + "On a Trial: emails never reach real people — verify a test address on the Email Settings page and each email action sends one test copy there (none are sent until one is verified); text messages are not sent; online giving and Activity Corner are off; Bank Sync uses a sandbox bank. Password resets and verification codes are always delivered. "
          + "Sample-data trials show a Trial Account note at first sign-in (click OK) and a Your trial portals panel on Home with the staff, Member Portal and Kids Portal sign-ins. Other trials show a small notice card with the end date, repeated on each page in the last 10 days. "
          + "Feature tip cards appear for trial staff, at most one at a time and three a day; Got it or ✕ retires a tip. "
          + "Request a subscription (on the trial notice, or when a trial or subscription has ended) lets the church owner login, a SuperAdmin or an Admin choose a Plan and Billing; no payment is taken on that page and the ChurchGeniusPro team follows up. An empty trial keeps its data when converted; a sample-data trial cannot be converted.",
            list("Sample-data trial: click OK on the Trial Account note.",
                 "Verify a test email address on the Email Settings page.",
                 "Click Request a subscription →, choose a Plan and Billing, and click Submit."),
            list("Problem: a group email reached nobody — Fix: expected on a trial; verify a test address to receive a copy.",
                 "Problem: a feature says it is not in your plan — Fix: request a plan change."),
            "1.1", list("v1.1 — Trial limits corrected (SMS off, online giving off), test email, feature tips, request form details.", "v1.0 — Initial release."),
            list("plan","plans","trial","subscription","free","standard","pro","allowance","upgrade","request a subscription","test email","feature tips")).roles("SuperAdmin,Admin,Accountant,User,Limited,Church"));

        // roles: SuperAdmin,Admin,Accountant,User,Member
        add(new HelpArticle("guess_it", "Ministry", "Guess It (Activity Corner)", "activity.guessit", false,
            "A live guessing game: the host reveals clues and players answer from their phones, joining with a 6-character code.",
            "Activity Corner → Guess It — Public Page opens /guessIt. Signed-in staff click ⚙ to open 🔐 Master Controls: 👥 Groups (➕ Generate New Group creates a 6-character group code with Copy code; ⏱ Duration per clue from Off to 3 minutes, changeable until the group ends; Publish / 📣 Publish All; 🏅 Leaderboard; 🏁 End Group for 🏆 Final Results), ➕ Create New Game (title, clues one per line, answer options one per line, correct answer; optionally in a group), 📋 Games (▶ Start, 🔄 Restart, 🗑 delete) and the 🎮 Active Game bar (➡️ Next Clue, ⏹ End Game, 🔄 Restart Game, ⏭ Next Game). Players open the page, enter the code, choose a name (signed-in members are not asked) and Submit ✓ answers; names are unique within a group and a player's place is kept on their device. Members can also play from the Member Portal 🎯 Guess It tab. Not available in the Kids Portal. Activity Corner is a subscription feature; the GuessIt permission hides the Master Controls for a staff login.",
            list("Open Activity Corner → Guess It — Public Page while signed in as staff.",
                 "Click ⚙ and expand 🔐 Master Controls.",
                 "Click ➕ Generate New Group and share the code; set ⏱ Duration per clue.",
                 "Create New Game in that group and Publish it.",
                 "Click ▶ Start, reveal clues with ➡️ Next Clue, and 🏁 End Group when done."),
            list("Problem: the ⚙ button is missing — Fix: sign in as staff; the GuessIt permission under Activity Corner must not be switched off for your login.",
                 "Problem: a player can't join — Fix: check all 6 characters of the code and that the group has not ended.",
                 "Problem: 'name taken' — Fix: names are unique per group; use another name or rejoin on the same device.",
                 "Problem: Activity Corner is missing — Fix: the church's plan does not include Activity Corner."),
            "2.0", list("v2.0 — Groups with join codes, running leaderboard, per-group clue duration (max 3 minutes), public join page, Master Controls.",
                        "v1.0 — Initial release."),
            list("guess it","quiz","game","activity corner","group code","leaderboard")).roles("SuperAdmin,Admin,Accountant,User,Member"));

        // roles: SuperAdmin,Admin,Accountant,User,Church
        add(new HelpArticle("help_assistant", "Help", "Using the Help Center & AI Help Assistant", "more.helpcenter", false,
            "Search, ask the AI Help Assistant by text or voice, browse guide cards, and have articles read aloud — within your permissions.",
            "More → Help Center (church owner login: Admin → Help Center) has a search box, the 🤖 AI Help Assistant, category guide cards and 📘 Help Articles. "
          + "The assistant answers only from articles you are allowed to see; type or tap 🎤 to speak and ask follow-ups such as 'step by step'. "
          + "In an article, ▶︎ Read aloud reads it to you, with a Speed choice (0.8× to 2×) and ⏹ Stop. If you are still stuck, raise a ticket under More → Ticketing.",
            list("Open More → Help Center.",
                 "Type a question and click 🔍 Search, or ask the AI Help Assistant and click Send.",
                 "Pick a category and open a guide card, or open a Help Article.",
                 "Click ▶︎ Read aloud to hear an article; choose a Speed or click ⏹ Stop."),
            list("Problem: the assistant says a feature isn't available to you — Fix: that module is restricted for your login; ask the church owner login.",
                 "Problem: voice doesn't work — Fix: allow microphone access in your browser.",
                 "Problem: nothing is spoken — Fix: your browser's text-to-speech must be available and the volume up."),
            "1.1", list("v1.1 — Help Center layout, church owner access, Ticketing pointer.", "v1.0 — Voice + conversational assistant, Read Aloud, permission-aware answers, audit logging."),
            list("help","help center","assistant","read aloud","how do i","search help")).roles("SuperAdmin,Admin,Accountant,User,Church"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Church,Member
        add(new HelpArticle("sign_in", "Getting Started", "Signing In, Passwords & Staying Signed In", null, false,
            "Sign in, recover a forgotten password or username, stay signed in on your own device, and understand the 30-minute automatic log-out.",
            "Everyone signs in on the same login page with a username and password. Remember password keeps that browser or phone signed in for up to 30 days. "
          + "Forgot Password? asks for your username and the email on the account and emails a reset link that works once and expires after 10 minutes (for a child's Kids Portal login the link goes to the head of household's email). "
          + "New passwords need at least 8 characters with a lowercase letter, an uppercase letter, a number and a special character. "
          + "Forgot Username? sends every username linked to an email address or phone number (up to 5 tries an hour). "
          + "Staff logins are signed out after 30 minutes without activity; one minute before, a message lets you click OK to stay. After signing in again you return to the page you were on. "
          + "The church owner login can switch this off per staff login with the \"Don't log out automatically\" box in that user's 🔐 Permissions. "
          + "Remembered devices are listed under 🔔 → 📱 Signed-in devices, where you can sign one or all other devices out.",
            list("Enter your Username and Password on the login page; tick Remember password only on your own device; click Login.",
                 "Forgot your password: click Forgot Password?, enter Username and Email, click Send Reset Link, then use the emailed link within 10 minutes.",
                 "Forgot your username: click Forgot Username?, choose Email or Phone, click Send Username(s).",
                 "To see remembered devices: click the 🔔 bell, then 📱 Signed-in devices; click Sign out or Sign out of all other devices."),
            list("Problem: no reset email arrives — Fix: the username and email must both match the account and the church subscription must be active; check spam; a new link is not sent while the previous one is still fresh.",
                 "Problem: \"This reset link has expired\" — Fix: links last 10 minutes and work once; request a new one.",
                 "Problem: \"Too many unsuccessful login attempts\" — Fix: wait and try again later, or reset your password.",
                 "Problem: signed out while working — Fix: that is the 30-minute inactivity log-out; the church owner login can tick Don't log out automatically for your login."),
            "1.0", list("v1.0 — Sign-in, password and username recovery, idle log-out and signed-in devices."),
            list("login","sign in","log in","forgot password","reset password","forgot username","remember password","remember me","logged out","session expired","inactivity","auto logout","signed-in devices")).roles("SuperAdmin,Admin,Accountant,User,Limited,Church,Member"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited,Member,Church
        add(new HelpArticle("account_switching", "Getting Started", "Switching Between Linked Accounts", null, false,
            "Move between your linked logins (for example staff and Member Portal) from the sidebar without signing out.",
            "When the church owner login has linked two or more of your logins on the Users page (🔗 Link Accounts), your name at the top of the sidebar opens a Switch Account list. "
          + "Your current login has a ✓; pick another to switch to it. Go to Home returns you to your start page. The list only appears when you have at least two linked logins.",
            list("Click your name at the top of the left menu.", "Under Switch Account, click the login you want.", "Use Go to Home to return to your start page."),
            list("Problem: no Switch Account list — Fix: your logins are not linked yet; ask the church owner login to link them on Admin → Users."),
            "1.0", list("v1.0 — Initial article."),
            list("switch account","linked accounts","switch login","change account")).roles("SuperAdmin,Admin,Accountant,User,Limited,Member,Church"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited
        add(new HelpArticle("home_dashboard", "Getting Started", "The Home Dashboard", null, false,
            "What the Home page shows for each role, and how to move, remove, add back or reset its sections.",
            "Admins see People (Members, New Members in the last 30 days, Guests, Families), Church Growth & Calendar, and Events & Distribution. "
          + "Accountants see Financial Greeting, Net Balance, Recurring Income, Recurring Expense, Income & Expense Stats and Recent Transactions & Chart. SuperAdmins see both sets; User and Limited logins see a welcome message. "
          + "Press and hold a section still for about 1 second, then drag it to a new place. ✏️ Customize layout opens edit mode: ✕ removes a section, ＋ Add section brings one back to its old place, Done finishes, and ↺ Reset to default layout restores the original. "
          + "The layout is saved to your login, follows you to other devices and never changes what other staff see. Removing a section deletes nothing.",
            list("Press and hold a section for about 1 second, drag it up or down, and let go.",
                 "Click ✏️ Customize layout, then ✕ on a section to remove it.",
                 "Click ＋ Add section to bring a removed section back.",
                 "Click Done; use ↺ Reset to default layout to start over."),
            list("Problem: a section will not move — Fix: hold still for a full second and do not start on a button or link; moving your finger cancels the hold.",
                 "Problem: Recurring Income or Recurring Expense is missing — Fix: it needs the Income (or Expense) permission and transactions marked as recurring on that page."),
            "1.0", list("v1.0 — Customize layout, press-and-hold reorder, add/remove sections."),
            list("home","dashboard","customize layout","edit layout","reorder","add section","remove section","reset layout","people stats")).roles("SuperAdmin,Admin,Accountant,User,Limited"));

        // roles: SuperAdmin,Admin,Accountant,User,Limited
        add(new HelpArticle("notifications_bell", "Getting Started", "The Notification Bell", null, false,
            "See reminders and new public submissions, open them, and clear them with ✕ or Clear all.",
            "The 🔔 bell at the top right of staff pages shows a red count of unread notifications; opening the panel marks them read. "
          + "New public submissions appear there — 🙏 prayer requests and 🤝 Connect With Us cards (Follow-Ups), 📝 membership requests, 💝 online donations, and 🚩 reported Members Chat messages — each only for staff whose role, permissions and plan allow the page it leads to. "
          + "Click a notification to open it, ✕ to clear one, or Clear all to clear every submission notification. Submission notifications are kept for 30 days; clearing one never deletes the submission. "
          + "The panel also holds 📱 Signed-in devices. The church owner login does not have the bell.",
            list("Click the 🔔 bell.", "Click a notification to open the page it refers to.", "Click ✕ to clear one, or Clear all."),
            list("Problem: a colleague sees a notification I don't — Fix: notifications follow each login's access to the destination page.",
                 "Problem: Clear all left some items — Fix: Clear all clears public-submission notifications only."),
            "1.0", list("v1.0 — Public submission notifications with ✕ / Clear all; Signed-in devices link."),
            list("notifications","bell","clear all","dismiss","prayer request notification","membership request notification","donation notification")).roles("SuperAdmin,Admin,Accountant,User,Limited"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("compose_email", "Communication", "Compose Email", "general.email", false,
            "Send one email to all members, all church guests, an event's registrants, or a typed list of addresses — with formatting and attachments.",
            "General → Compose Email. Recipient Type: Members (member type Member, with an email), Event Registrants (choose the event), Church Guests (member type Guest) or Group Email (type each address and press Enter or a comma). "
          + "A pill shows how many recipients were found. Enter the Subject and Content (Bold, Italic, Underline, lists, Clear), attach up to 10 files, use 🔍 Preview and 📨 Send Now, or 📨 Send Email. "
          + "Recipients are sent as blind copies. On a Trial or Demo account nobody is emailed — one test copy goes to the verified test address on the Email Settings page. "
          + "SuperAdmins and Admins can open ⚙ Settings on this page to edit the church's Email Settings. The page needs the Compose Email permission and the Compose Emails plan feature.",
            list("Go to General → Compose Email.",
                 "Choose the Recipient Type (and the event, or type the addresses).",
                 "Enter the Subject and Content; attach files if needed.",
                 "Click 🔍 Preview, then 📨 Send Now."),
            list("Problem: \"No recipient email addresses found\" — Fix: no one of that type has an email address, or nobody has registered for the event.",
                 "Problem: Compose Email is not in the menu — Fix: the Compose Email permission is off or the plan does not include Compose Emails.",
                 "Problem: on a trial the email fails — Fix: verify a test address on the Email Settings page first."),
            "1.0", list("v1.0 — Initial article."),
            list("email","compose email","send email","bulk email","group email","event registrants","church guests","attachments")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("sms_opt_in", "Communication", "SMS Sign-Up (Opt-In) Page", "more.publicscreens", false,
            "Collect consent before texting people: the public SMS Opt-In Form, the YES confirmation, and STOP / START / HELP / GIVE replies.",
            "Generate the SMS Opt-In Form link on More → Public Screens and share it. The person enters First Name, Last Name and a 10-digit US Phone Number, ticks the consent box and taps Sign Up for SMS, then replies YES to the confirmation text. "
          + "Replies: STOP (or STOPALL, UNSUBSCRIBE, CANCEL, END, QUIT) stops texts; START or UNSTOP restarts them; HELP returns who is texting and how to get help; GIVE (or GIVE 50) returns the church's giving link. "
          + "If the confirmation text cannot be sent (for example on a Trial account) the person sees \"Sign-up recorded\" and is asked to contact the church office.",
            list("More → Public Screens → Select Page: SMS Opt-In Form → Generate Link → Copy.",
                 "Share the link (QR code, website, email).",
                 "The person completes the form and replies YES to the text."),
            list("Problem: \"That phone number cannot receive text messages\" — Fix: the number cannot receive texts; check it.",
                 "Problem: \"Invalid Link\" — Fix: the link was revoked or expired; generate a new one.",
                 "Problem: \"Sign-up recorded\" instead of a text — Fix: the confirmation text could not be sent; texts are not sent on Trial accounts."),
            "1.0", list("v1.0 — Initial article."),
            list("sms","opt in","text messages","consent","stop","help","yes","unsubscribe sms","give")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,Church
        add(new HelpArticle("roles_overview", "Admin", "Roles & Where Admin Settings Live", null, true,
            "What each role can reach, and where the church's settings are.",
            "Church (owner login): Admin section only — Users, Stripe Integration, WhatsApp Integration, Ticketing, AI Assistant, Help Center; manages staff logins and permissions. "
          + "SuperAdmin: Admin, Accounting, General, More, My Profile and Activity Corner. "
          + "Admin: Admin (Families, Groups, Membership Requests, Unsubscribed List, Private Page Access), General, More, My Profile, Activity Corner; Payroll only in Accounting. "
          + "Accountant: Accounting, General, More, My Profile, Activity Corner; Families only in Admin. User: General, More, My Profile, Activity Corner. Limited: General only. "
          + "Permission checkboxes then switch individual pages and buttons off; changes reach a signed-in user within about 15 seconds; data access follows the role. "
          + "Settings: church name, address, logo, website and social links — Admin → Users → ⛪ Church Settings (church owner login); email look — Email Settings page (SuperAdmin, Admin); online giving — Admin → Stripe Integration; network-only pages — Admin → Private Page Access; automatic log-out — Admin → Users → 🔐 → Don't log out automatically.",
            list("Choose the role when adding a user on Admin → Users.", "Use 🔐 on a user's row to adjust their permissions."),
            null,
            "1.0", list("v1.0 — Initial article."),
            list("roles","superadmin","admin","accountant","user","limited","church owner","settings","logo","church settings")).roles("SuperAdmin,Admin,Church"));

        // roles: SuperAdmin,Admin,Accountant  (Member-portal logins too, when the church grants admin.family)
        add(new HelpArticle("family_member_report", "Members", "Family & Member Report", "admin.family", false,
            "Filter members or families by birthday or anniversary month, location, gender, role or type, then print or download CSV.",
            "Admin → Families → 📊 Report opens the Family & Member Report. 👤 Members gives one row per person and filters by Birthday Month, Gender, Family Role and Member Type (Member, Non-Members, Guest, Visitor); City, State and ZIP use each person's own address. 👨‍👩‍👧 Families gives one row per family (never duplicated) with the Primary member, spouse, wedding anniversary and all members, and filters by Anniversary Month and Family Type; location and type come from the Primary member and a name search matches anyone in the family. Both can include inactive and deleted records. Months match in any year. When a Member Portal login runs the report, details a member marked Private are hidden and private addresses are ignored by the location filters.",
            list("Go to Admin → Families and click 📊 Report.",
                 "Choose 👤 Members or 👨‍👩‍👧 Families.",
                 "Set filters (leave all blank for the whole directory).",
                 "Click 🔍 Run Report.",
                 "Click 🖨 Print or ⬇ Download CSV."),
            list("Problem: 'Run a report first.' — Fix: click 🔍 Run Report before printing.",
                 "Problem: 'Nothing to download — the report is empty.' — Fix: loosen the filters so at least one row is returned.",
                 "Problem: a family appears under Non-member families — Fix: its Primary member's Member Type is Guest or Visitor; change it on the Family page if that is wrong."),
            "1.0", list("v1.0 — Members / Families report with birthday, anniversary, location, gender, role and type filters, Print and Download CSV."),
            list("family report","member report","birthday","anniversary","directory","export","csv","print","non-member"))
            .roles("SuperAdmin,Admin,Accountant"));

        // roles: SuperAdmin,Admin,User  (page route also needs more.followups; Member sessions are refused by the API)
        add(new HelpArticle("content_reports", "Members", "Reported Chat Messages", "more.followups", false,
            "Review Members Chat messages that members reported, and remove, mark reviewed or dismiss them.",
            "When a member reports a Members Chat message from the Member Portal, it appears in More → Follow-Ups → 🚩 Reported Content with who reported whom, the message, the reason (Harassment, Spam, Inappropriate, Other) and the date. The tab count shows how many are Open. 🗑 Remove message removes the message from both members' inboxes (cannot be undone); ✓ Reviewed and Dismiss close the report with an optional note; Reopen sets a handled report back to Open. Filter by Open, Reviewed, Dismissed or All, or search names, reason and message.",
            list("Go to More → Follow-Ups.",
                 "Open the 🚩 Reported Content tab.",
                 "Read the report.",
                 "Click 🗑 Remove message, ✓ Reviewed or Dismiss (add a note if you like)."),
            list("Problem: a handled report is no longer listed — Fix: change the filter from Open to Reviewed, Dismissed or All.",
                 "Problem: a message was removed by mistake — Fix: removal can't be undone; Reopen only changes the report's status."),
            "1.0", list("v1.0 — Reported Content tab: Remove message, Reviewed, Dismiss, Reopen."),
            list("reported content","report","members chat","remove message","harassment","spam","dismiss"))
            .roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin  (Member-portal logins too, when the church grants admin.unsubscribed)
        add(new HelpArticle("unsubscribed_list", "Communication", "Unsubscribed List", "admin.unsubscribed", true,
            "See who opted out of church emails and re-subscribe someone who asks.",
            "Church emails carry an 'Unsubscribe from these emails' link; the person confirms on the Unsubscribe from Emails page. Their address is then listed in Admin → Unsubscribed List (Email, First Name, Last Name, Unsubscribed On) and church emails to that address are skipped. Account emails such as sign-in and password messages are not affected. Re-subscribe removes the address from the list so they receive church emails again.",
            list("Go to Admin → Unsubscribed List.",
                 "Find the address.",
                 "Click Re-subscribe and confirm — only when the person has asked."),
            list("Problem: a member stopped receiving church emails — Fix: check whether their address is on this list.",
                 "Problem: no Re-subscribe button — Fix: your login needs the Unsubscribed List permission."),
            "1.0", list("v1.0 — Unsubscribed List with Re-subscribe."),
            list("unsubscribe","unsubscribed","opt out","re-subscribe","resubscribe","not receiving emails"))
            .roles("SuperAdmin,Admin"));

        // roles: Church
        add(new HelpArticle("church_settings", "Admin", "Church Settings (Logo, Name, Address & Links)", null, true,
            "The church account's page for the logo, church name and address, and website and social media links.",
            "The church login opens Admin → Users → ⛪ Church Settings. Upload Church Logo accepts PNG, JPG, GIF or WebP up to 5 MB (square recommended); ⬆ Upload Logo saves it and 🗑 Remove Logo removes it. Church Name & Address holds the Church Name (required) and address. Website & Social Media takes Website, Facebook, Instagram and YouTube URLs, each starting with http:// or https://. 💾 Save Changes saves the details; the new name appears in the menu straight away. Data Imported for Your Church is a read-only list of imports ChurchGeniusPro support ran from your previous church software.",
            list("Go to Admin → Users and click ⛪ Church Settings.",
                 "Choose a logo image and click ⬆ Upload Logo.",
                 "Edit Church Name & Address and Website & Social Media.",
                 "Click 💾 Save Changes."),
            list("Problem: 'File must be under 5 MB.' — Fix: use a smaller image.",
                 "Problem: 'Only image files (PNG, JPG, GIF, WebP) are allowed.' — Fix: pick an image file, not a PDF.",
                 "Problem: '… must start with http:// or https://' — Fix: include the full address, e.g. https://example.org."),
            "1.0", list("v1.0 — Church Settings page for the church account."),
            list("church settings","logo","church logo","church name","address","website","facebook","instagram","youtube","data import"))
            .roles("Church"));

        // roles: SuperAdmin,Admin,User   (check-in actions need general.events.edit; the page itself is gated by general.eventcheckin + the Event Check-ins plan feature)
        add(new HelpArticle("event_checkin", "Events", "Event Check-In (QR, Walk-in, Self Check-in)", "general.events.edit", false,
            "Check registrants in on the day by search, QR scan or walk-in registration.",
            "The ✅ Check-In button on an event card opens the Event Check-in page: totals (Total Registered, Checked In, Not Checked In), a searchable list with Check In / Undo, 📷 Scan QR for registrant QR codes, ⛶ Full Screen, print lists (All, Checked-In, Not Checked-In, Selected), attendee labels, and a Walk-in Registration & Check-in form. When the event has self check-in enabled, registrants can check themselves in on the public page by entering their registration code and tapping I'm Here. The Check-In button only appears when the church's plan includes Event Check-ins.",
            list("Go to General → Events and click ✅ Check-In on the event card.",
                 "Search by name, email or phone and click Check In (Undo reverses it).",
                 "Or click 📷 Scan QR and scan the attendee's QR code.",
                 "For an unregistered guest, fill in Walk-in Registration & Check-in and click 🚶 Register & Check In.",
                 "Print lists with 🖨️ Print All / ✅ Print Checked-In / ⏳ Print Not Checked-In, or a label with 🖨️ Print Label."),
            list("Problem: no ✅ Check-In button — Fix: the church's plan must include Event Check-ins.",
                 "Problem: a registrant has no QR code — Fix: QR codes are only created when \"Generate a unique QR Code\" is ticked on the event, and only for people who answered Yes.",
                 "Problem: Check In fails with permission denied — Fix: checking people in needs a SuperAdmin, Admin or User login with Events \"Add or Edit\"."),
            "1.0", list("v1.0 — First article for the Event Check-in page (QR scan, walk-in, labels, self check-in)."),
            list("event check-in","check in","qr","scan","walk-in","self check-in","i'm here","label","print")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("event_registration_reminder", "Events", "Event Invitations & Registration Reminders", "general.events.edit", false,
            "Invite a contact list to an event and automatically remind those who have not registered.",
            "The Invite button on an event card opens the Registration Reminder window. Add contacts (email, phone or both). 📨 Send Invite sends a \"You're Invited\" email and/or SMS with the RSVP link; each person is invited at most once per event, so re-sending reaches only new contacts. Ticking \"Send Reminder Before Registration Deadline\" with N day(s) before the event sends a reminder on that date to contacts who have not registered yet; people already registered (matched by email or phone) are skipped and nobody is reminded twice. 🚀 Send Reminder runs it immediately; 📜 View Attempt Log lists every invite and reminder (sent, skipped or failed) with the reason.",
            list("Go to General → Events and click Invite on the event card.",
                 "Click ＋ Add Contact and enter an email and/or phone (US 10-digit) for each person.",
                 "Click 📨 Send Invite to invite them now.",
                 "Tick Send Reminder Before Registration Deadline, enter the day(s) before the event and click Save.",
                 "Use 🚀 Send Reminder to run it now and 📜 View Attempt Log to review results."),
            list("Problem: 📨 Send Invite is greyed out — Fix: add at least one valid email or phone and correct any invalid entries.",
                 "Problem: \"Nothing sent — church subscription is not active\" — Fix: the church subscription must be active.",
                 "Problem: Trial/Demo account — Fix: one test email goes to the verified test address; the contacts are only simulated.",
                 "Problem: someone was skipped — Fix: open 📜 View Attempt Log; common reasons are already registered, already sent, or an invalid email/phone."),
            "1.0", list("v1.0 — First article: contact list, Send Invite, scheduled and on-demand registration reminders, attempt log."),
            list("invite","send invite","invitation","registration reminder","remind to register","attempt log","send reminder")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("attendance_volunteers", "Communication", "Email / SMS Volunteers from Attendance", "general.attendance.emailsms", false,
            "Message people who have a volunteer profile, optionally only those present this week.",
            "The ✉ Email / SMS Volunteers button on the Attendance page lists active members who have a volunteer profile, marking those present this week (Monday–Sunday). Select volunteers, choose Email and/or SMS, write a Subject and Message and click Send. Each recipient's result (sent, skipped or failed, with the reason) is shown. Messages respect the unsubscribe list, the plan's email/SMS allowances and Trial/Demo rules.",
            list("Go to General → Attendance.",
                 "Click ✉ Email / SMS Volunteers.",
                 "Tick volunteers, or Select all; tick Present this week only to narrow the list.",
                 "Choose ✉️ Email and/or 💬 SMS, write the Subject (email) and Message, and click Send."),
            list("Problem: \"No volunteers yet\" — Fix: only members with a volunteer profile appear (for example added from Follow-Ups → Assigned To → Add New Volunteer).",
                 "Problem: \"You do not have permission to message volunteers\" — Fix: ask the church account to tick Attendance \"Email or SMS Volunteers\" for your login.",
                 "Problem: someone was skipped — Fix: they have no email/phone on file, are unsubscribed, or the allowance was used up; the result line gives the reason."),
            "1.0", list("v1.0 — First article for Attendance → ✉ Email / SMS Volunteers."),
            list("email volunteers","sms volunteers","text volunteers","volunteers present","message volunteers")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User   (the Calendar page; the public calendar link needs no login)
        add(new HelpArticle("event_calendar", "Events", "Calendar, Public Event Calendar & Calendar Sync", null, false,
            "See birthdays, anniversaries, meetings and events on a calendar and sync events to Google or iPhone.",
            "The Calendar (Home → Calendar card → View full ↗) shows birthdays, anniversaries, meetings and events in Monthly, Weekly or Yearly view; clicking a day lists its items and Add Meeting schedules a meeting on that date. Sync with Google Calendar gives a feed URL to subscribe to in Google Calendar (Other Calendars → From URL) or on an iPhone (Settings → Apps → Calendar → Calendar Accounts → Add Account → Other → Add Subscribed Calendar), or a .ics download. The feed and the public Event Calendar page (link generated on More → Public Screens) contain church events and meetings only — never birthdays or anniversaries. The public page has a List tab and a Calendar tab, and events with registration show a Register button. Deleting the Event Calendar link on Public Screens stops every subscribed feed that uses it.",
            list("On the Home page click View full ↗ on the Calendar card.",
                 "Switch Monthly / Weekly / Yearly; click a day to see details or Add Meeting.",
                 "Click Sync with Google Calendar, then 📋 Copy and add the URL in Google Calendar or on iPhone.",
                 "For a public page, open More → Public Screens, choose Event Calendar and click Generate Link."),
            list("Problem: Google Calendar doesn't show a new meeting yet — Fix: Google refreshes subscribed calendars on its own schedule, up to 24 hours.",
                 "Problem: birthdays missing from the public calendar — Fix: by design; personal dates are shown only to staff.",
                 "Problem: subscribed calendars stopped updating — Fix: the Event Calendar public link was deleted; generate a new one and subscribe again."),
            "1.0", list("v1.0 — First article: Calendar views, Add Meeting, Google/iPhone sync, public Event Calendar."),
            list("calendar","event calendar","google calendar","iphone calendar","ics","sync","subscribe","public calendar","upcoming events")).roles("SuperAdmin,Admin"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("ministry_hub", "Ministry", "The Ministry Page", "general.ministry", false,
            "General → Ministry is the starting point for Kids Ministry (with Sunday School), Worship Planning and Prayer Ministry.",
            "The Ministry page shows a card for each ministry program: 👧 Kids Ministry (which also holds 📚 Sunday School), 🎵 Worship Planning and 🙏 Prayer Ministry. Each card has its own permission under General → Ministry on the Users permission screen (Kids Ministry, Worship Planning, Prayer Meeting) and its own subscription feature; a card the login or plan does not allow is hidden.",
            list("Open General → Ministry.",
                 "Click 👧 Kids Ministry, 🎵 Worship Planning or 🙏 Prayer Ministry.",
                 "For Sunday School, open Kids Ministry and click 📚 Sunday School at the top of the page."),
            list("Problem: a ministry card is missing — Fix: the login does not have that ministry ticked under General → Ministry, or the church's plan does not include it; ask an administrator.",
                 "Problem: Access denied when opening Ministry — Fix: Ministry pages are for SuperAdmin, Admin and User logins; Accountant and Limited logins cannot open them."),
            "1.0", list("v1.0 — Initial article: Ministry hub with Kids, Worship and Prayer cards."),
            list("ministry","ministry page","kids ministry","worship","prayer ministry")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("kids_registration", "Ministry", "Registering Children in Kids Ministry", "general.ministry.kids", false,
            "Register children (one or several siblings at once), set up classrooms, and fill registrations from paper forms.",
            "Kids Ministry → 👦 Register lists every registered child (search by name, parent or classroom; Show Inactive for inactive records). ➕ Register Child opens a form that can link an existing member or guest, register several siblings who share the same Parent / Guardian, Emergency Contact, Medical Information and Authorized Pickups, and auto-assign a classroom by age. After saving, the ✅ Registered window lets you Check In & Print each child. ⬇ Registration Form (PDF) prints a blank form and ⬆ Upload Completed Form fills a registration from a scan, with Save as New / Merge Selected / Update (Overwrite) when the child already exists. ⚙ Setup chooses which form sections are used and how duplicates are matched. Classrooms (name, min/max age, capacity, room) are managed on 🏫 Classrooms. Registering, editing and deleting children, classrooms and Setup require an Admin or SuperAdmin login; User logins can view.",
            list("Open General → Ministry → Kids Ministry and the 👦 Register tab.",
                 "Click ➕ Register Child; optionally search Link to Existing Member / Guest.",
                 "Enter First Name and Last Name (required), Date of Birth, Gender, Grade and Classroom (Auto-assign by Age can choose it).",
                 "Click + Add another child for this parent for each sibling.",
                 "Fill in Parent / Guardian, Emergency Contact, Medical Information and Authorized Pickups (+ Add Person).",
                 "Click Save Child, then Check In & Print, Check In All & Print, or Done.",
                 "To add rooms, open 🏫 Classrooms → + Add Classroom → Save Classroom."),
            list("Problem: Save gives Access denied — Fix: registering and editing children needs an Admin or SuperAdmin login.",
                 "Problem: a child is missing from the list — Fix: tick Show Inactive; the record may be marked inactive.",
                 "Problem: a section (e.g. Medical Information) is missing from the form — Fix: it is turned off in ⚙ Setup; an Admin can tick it and Save Setup.",
                 "Problem: a scanned form matched the wrong child — Fix: choose Save as New in the comparison window, or adjust Duplicate Matching in ⚙ Setup."),
            "1.0", list("v1.0 — Initial article: multi-child registration, form PDF and AI scan import, Setup, classrooms."),
            list("register child","add child","kids registration","classroom","registration form","scan form","sibling")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin,User
        add(new HelpArticle("kids_pickup_alerts", "Ministry", "Kids Pickup Times & Overdue Alerts", "general.ministry.kids", false,
            "Set a pickup time, see who is overdue, and alert your team by email or SMS with a pickup link.",
            "In Kids Ministry → 👦 Register → ⚙ Setup, Pickup Expiration & Alerts sets the Default Pickup Time (a Pickup Expiration Time, when filled in, is used instead), Email alerts and SMS alerts, and the Alert Recipients (members, volunteers or typed contacts). When a checked-in child passes the deadline, recipients get an alert with a pickup link; alerts repeat while the child is overdue unless snoozed. The pickup link page shows the child's status and lets the recipient Snooze alerts or Confirm Picked Up, which checks the child out and tells the other recipients. The ⏰ Pickup tab (Overdue Pickup Dashboard) lists children still checked in with deadline and time left, filtered by All / Overdue only / Due within 15 min, with Extend, Snooze and Check Out. Each child's registration has a Send overdue pickup alerts for this child box. Saving Setup and Extend are Admin-only.",
            list("Open Kids Ministry → 👦 Register → ⚙ Setup.",
                 "Set Default Pickup Time, tick Email alerts and/or SMS alerts, and add Alert Recipients; click Save Setup.",
                 "During the service, watch the ⏰ Pickup tab.",
                 "Use Extend for more time, Snooze to pause alerts, or Check Out."),
            list("Problem: no alerts are sent — Fix: add at least one Alert Recipient, tick Email alerts or SMS alerts, check the child's own alert box, and check the child is not snoozed.",
                 "Problem: the pickup link says it is invalid or has expired — Fix: the child has already been checked out, or the link belongs to an older check-in.",
                 "Problem: Extend gives Access denied — Fix: extending the deadline needs an Admin or SuperAdmin login."),
            "1.0", list("v1.0 — Initial article: pickup deadline, overdue dashboard, email/SMS alerts, public pickup link."),
            list("pickup","overdue","pickup alert","late pickup","snooze","extend pickup","pickup link")).roles("SuperAdmin,Admin,User"));

        // roles: SuperAdmin,Admin
        add(new HelpArticle("song_book_access", "Ministry", "Song Book Access", null, true,
            "Grant Member Portal users None, View Only or Full access to the Song Book.",
            "More → Song Book Access lists portal members with their current level. Click None, View Only or Full to change it — it saves immediately. The Activity Log tab records every change and who made it. Members start with no access. Staff logins always have full access to the Song Book and need no level here. Only SuperAdmin and Admin logins can open this page.",
            list("Open More → Song Book Access.",
                 "Search the member by name or email.",
                 "Click None, View Only or Full.",
                 "Check the Activity Log tab for the history."),
            list("Problem: Access denied — Fix: only SuperAdmin and Admin logins can manage Song Book access.",
                 "Problem: a person is not in the list — Fix: only church members are listed; make sure they are in the directory."),
            "1.0", list("v1.0 — Initial article (previously part of song_book)."),
            list("song book access","songbook access","view only","full access","member song book")).roles("SuperAdmin,Admin"));

        // roles: SuperAdmin,Admin,Accountant
        add(new HelpArticle("accounting_setup", "Accounting", "Accounting Setup — Funds, Purposes & Transaction Types", "accounting.settings", false,
            "Set up the lists used by the Income and Expense forms.",
            "The 💼 ACCOUNT SETTINGS button in the top bar (Accountant and SuperAdmin logins) opens Fund, Purpose and Transaction Type. Fund holds main categories and their sub categories: sub categories fill the Fund list on Income, main categories fill the Fund list on Expense, and each main category needs at least one sub category. Purpose fills the Expense category list on the Expense page. Transaction Type fills the Method list on Income and Expense. The \"Show main category with sub category\" tick box on the Fund page is remembered per browser.",
            list("Click 💼 ACCOUNT SETTINGS → Fund.",
                 "Add a Main Category (＋ Add), select it, and add its Sub Categories.",
                 "💼 ACCOUNT SETTINGS → Purpose: add expense categories.",
                 "💼 ACCOUNT SETTINGS → Transaction Type: add payment methods."),
            list("Problem: deleting a main category removed its sub categories — Fix: this is by design; the confirmation warns about it.",
                 "Problem: no 💼 ACCOUNT SETTINGS button — Fix: only Accountant/SuperAdmin see it, and only with the Account Settings permission."),
            "1.0", list("v1.0 — Initial release."),
            list("fund","funds","main category","sub category","purpose","transaction type","method","account settings","setup")).roles("SuperAdmin,Accountant"));

        // roles: SuperAdmin,Accountant
        add(new HelpArticle("accountant_dashboard", "Accounting", "Accountant Dashboard (Home)", null, false,
            "The financial overview Accountant and SuperAdmin logins see on Home.",
            "Home shows Net Balance by Main Category, Recurring Income and Recurring Expense quick-entry panels (entries saved with 🔁 Recurring; Add records the next one, ✕ removes it from the dashboard only), Income and Expense Statistics by month, Recent Transactions and a yearly Transactions chart. The Recurring panels are hidden when the Income or Expense permission is off. ✏️ Customize layout moves or hides sections.",
            list("Open Home.", "Use ‹ › to change the month or year.", "Click Add in a Recurring panel to record the next entry."),
            null, "1.0", list("v1.0 — Initial release."),
            list("accountant dashboard","home","net balance","recurring income","recurring expense","statistics")).roles("SuperAdmin,Accountant"));

        // roles: Member
        add(new HelpArticle("member_signup", "Member Portal", "Create Your Member Portal Login", null, false,
            "Use the church's Member Signup link to create your own username and password.",
            "The Member Signup link comes from the church (Public Screens). Enter your first name, last name and the email address or phone number the church has on file and click Find My Account. Choose a username and password and click Send Verification Code; a 6-digit code goes to the email on your member record. Enter it and click Verify & Create Account. The login is active straight away. Passwords need at least 8 characters with a lowercase letter, an uppercase letter, a number and a special character. Forgot Password? on the sign-in page emails a reset link valid for 10 minutes; Forgot Username? sends your username(s) by email or phone.",
            list("Open the church's Member Signup link.",
                 "Enter First Name, Last Name and Email Address or Phone Number, then Find My Account.",
                 "Choose Username, Password and Confirm Password, then Send Verification Code.",
                 "Enter the 6-digit code and click Verify & Create Account."),
            list("Problem: No matching member found — Fix: use the exact name and the email or phone on your family record, or ask the church office to check it.",
                 "Problem: This member already has an account — Fix: sign in, or use Forgot Password? / Forgot Username?.",
                 "Problem: the link is invalid or has expired — Fix: ask the church for a new Member Signup link.",
                 "Problem: Member portals are not included in this church's subscription plan — Fix: the church needs a plan that includes the Member Portal."),
            "1.0", list("v1.0 — Initial release."),
            list("member signup","sign up","create account","register","verification code","password rules","forgot password","forgot username")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_family", "Member Portal", "My Family & Church Calendar", "member.family", false,
            "Update your household's details and add the church calendar to your phone.",
            "My Profile → Family shows each household member's phone, email, gender, birthday, address and nickname. ✎ Update opens Edit Family: primary member details (first and last name required), address, nickname, role, anniversary and family photo, plus Family Members with ＋ Add Member. Save Family stores the changes; removing someone from the list removes them from the family record. The portal home screen lists upcoming events and meetings and upcoming birthdays and anniversaries, with a month calendar. Sync Calendar → Google Calendar gives a feed URL (Copy), Add to Google Calendar, Download .ics, and steps for iPhone Calendar.",
            list("Open My Profile → Family and click ✎ Update.",
                 "Edit details or click ＋ Add Member.",
                 "Click Save Family.",
                 "Home screen → Sync Calendar → Google Calendar to add the church calendar to your phone."),
            list("Problem: Could not load family data — Fix: click Retry; if it persists your login may not be linked to a family record, so contact the church office.",
                 "Problem: Google Calendar does not show a new event — Fix: Google can take up to 24 hours; download the .ics for an immediate copy."),
            "1.0", list("v1.0 — Initial release."),
            list("my family","update family","family photo","add family member","calendar sync","google calendar","iphone calendar","ics","upcoming events","birthdays")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_give", "Member Portal", "Give, Contribution History & Giving Statement", "member.contributions", false,
            "Give by card from the portal, see your giving by year, follow your pledges and print your year-end statement.",
            "My Profile → Contributions has three tabs. Give / Contribute takes a card payment (Stripe Payment Element) to the church's own Stripe account: choose a Purpose, enter an Amount of at least $0.50, an optional Note / Memo, card details, then Pay & Give; the gift is recorded only after Stripe confirms it. My Pledges (shown only when you have a pledge) lists Total Pledged, Amount Given and Balance Pending per campaign. Contribution History lists your own gifts for a chosen year with the total and a Print button. Financial Report generates your year-end giving statement in the church's wording, with Print / Save PDF.",
            list("Open My Profile → Contributions.",
                 "Give / Contribute: choose Purpose, enter Amount, add card details, click Pay & Give.",
                 "Contribution History: pick a year; Print if needed.",
                 "Financial Report: pick a year, Generate, then Print / Save PDF."),
            list("Problem: Online giving is not set up for your church yet — Fix: the church has not connected Stripe; give another way or ask the office.",
                 "Problem: your login is not linked to a member record — Fix: ask the church office to add your email to your family record.",
                 "Problem: Your payment went through but could not be recorded — Fix: do not pay again; contact the church office so they record it."),
            "1.0", list("v1.0 — Initial release."),
            list("give","online giving","pay and give","contribute","contribution history","giving statement","year-end statement","tax statement","financial report","my pledges")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_groups_chat", "Member Portal", "Groups & Members Chat", "member.groups", false,
            "See your groups, email a group, message other members, and report or block.",
            "My Profile → Groups has two tabs. Groups lists each group you belong to with its members; Email Group emails the whole group, and Message / Email beside a person contacts one member. Members Chat has Inbox and Sent; ✏️ New sends a message to one or more members who have a portal login, and opening a message lets you Reply. Tick messages and click Delete to remove them. On any message from someone else, 🚩 Report sends it to church staff with a reason (Harassment or bullying, Inappropriate content, Spam or scam, Something else) and optional details — the sender is not told who reported it; you can also tick Also block this member. 🚫 Block stops that member messaging you and hides their messages; they are not told. Unblock from Settings → Blocked members.",
            list("Open My Profile → Groups.",
                 "Email Group, or Message / Email one member.",
                 "Members Chat → ✏️ New → choose recipients → Send; open a message to Reply.",
                 "Use 🚩 Report or 🚫 Block on a message from someone else."),
            list("Problem: someone is not in the To list — Fix: Members Chat only reaches members with their own portal login.",
                 "Problem: You are not assigned to any groups — Fix: the church adds people to groups; ask the office."),
            "1.0", list("v1.0 — Initial release."),
            list("groups","members chat","message","inbox","email group","report message","block member","unblock")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_volunteer", "Member Portal", "Volunteering in the Member Portal", "member.volunteer", false,
            "Answer event volunteer requests, set your volunteer profile, and see upcoming and past assignments.",
            "My Profile → Volunteer has four tabs. Event Assignments lists each event you were asked to serve at, with date, time, role and status (Pending, Accepted, Declined, Maybe) and buttons ✅ Accept, ❌ Decline and 🤔 Maybe — you can change your answer. My Volunteer Profile → Edit Profile stores Skills & Interests (comma-separated), Availability and Notes. Upcoming Assignments lists upcoming assignments (pending ones offer Confirm / Decline / Maybe) and accepted event assignments; Assignment History lists past ones.",
            list("Open My Profile → Volunteer.",
                 "Event Assignments: click ✅ Accept, ❌ Decline or 🤔 Maybe.",
                 "My Volunteer Profile → Edit Profile → Save Profile."),
            list("Problem: an assignment is missing — Fix: click Refresh; if still missing, ask the church office."),
            "1.0", list("v1.0 — Initial release."),
            list("volunteer","accept","decline","maybe","event assignment","volunteer profile","skills","availability")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_directory", "Member Portal", "Member Directory", "member.directory", false,
            "Look up church families and email several members at once, respecting each member's privacy choices.",
            "My Profile → Directory lists families ten at a time (Load More shows the next ten). Search by name… also matches email and phone. Phone, email and address a member marked private are hidden from other members. Tick the box beside members with a visible email and click 📧 Email Selected, enter a Subject and Message, then Send Email; Clear removes all ticks. Ticks are kept across Load More.",
            list("Open My Profile → Directory.",
                 "Search by name, or Load More.",
                 "Tick members → 📧 Email Selected → Subject and Message → Send Email."),
            list("Problem: a phone or address is not shown — Fix: that member marked it private.",
                 "Problem: Email Selected will not send — Fix: enter a Subject and a Message; both are required."),
            "1.0", list("v1.0 — Initial release."),
            list("directory","member directory","search members","email selected","privacy")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_classes", "Member Portal", "Classes & Exams in the Member Portal", "member.classes", false,
            "Students take exams online, parents see their children's results, and teachers manage their classes from the portal.",
            "My Profile → Classes shows an enrolled student's class lessons, documents and exams. Start Exam shows the instructions (duration, number of questions, how many are required); ✓ I Understand — Begin Exam starts it. Answers save as you move with Next → and ← Back; the last question shows Submit. A timed exam submits itself when time runs out and cannot be paused; only the teacher can extend the time. ✋ Alert Teacher sends a question or alert. Resume Exam continues an exam in progress; View Results shows a submitted one. A parent with enrolled children sees each child's class, teacher and exam status with 📋 View. A member set as a class teacher gets My Sunday School Classes: Documents (Upload, Visible to students), Exams (+ New Exam, questions, publish) and Student Submissions (extend time, Send to Parent, Finalize & Submit Grades).",
            list("Open My Profile → Classes.",
                 "Click Start Exam, read the instructions, then ✓ I Understand — Begin Exam.",
                 "Move with Next → / ← Back and click Submit on the last question.",
                 "Click 📋 View Results afterwards."),
            list("Problem: You are not currently enrolled in a Sunday School class — Fix: ask the church administrator to enroll you.",
                 "Problem: a teacher does not see their class — Fix: the church must set the teacher's member record as the class teacher."),
            "1.0", list("v1.0 — Initial release."),
            list("classes","sunday school","exam","start exam","results","alert teacher","teacher","parent")).roles("Member"));

        // roles: Member
        add(new HelpArticle("member_settings", "Member Portal", "Portal Settings, Privacy & Deleting Your Account", null, false,
            "Stop automatic sign-out, sign out other devices, unblock members, or delete your own portal login.",
            "The gear (top right) opens Settings. Don't log out automatically (then Save) stops the inactivity sign-out for your session; Logout still works. Signed-in devices lists every browser or app that stays signed in through Remember password — Sign out one, or Sign out of all other devices; signed-out devices need the password next time. Blocked members lists people you blocked with Unblock. Delete my account… asks for your password and a confirmation tick, then permanently deletes your Member Portal login and signs you out everywhere. The church keeps its membership record, giving history and year-end statements; your blocks, remembered devices, notification subscriptions and chat inbox are removed, and messages you sent show as from \"Former member\". Child logins have no Signed-in devices or Delete my account.",
            list("Click the gear (top right).",
                 "Tick Don't log out automatically and click Save, if wanted.",
                 "Signed-in devices: Sign out a device.",
                 "Delete my account…: enter your password, tick the box, click Delete my account."),
            list("Problem: you cannot sign in to delete your account — Fix: ask the church office, or email support@churchgeniuspro.com from the email on your member record.",
                 "Problem: Signed-in devices says No remembered devices — Fix: only devices where Remember password was ticked are listed."),
            "1.0", list("v1.0 — Initial release."),
            list("settings","don't log out automatically","signed-in devices","blocked members","unblock","delete my account","account deletion","privacy")).roles("Member"));

        // roles: Member
        add(new HelpArticle("kids_portal", "Member Portal", "Kids Portal", null, false,
            "What a child sees when they sign in, and why it works on the website only.",
            "A login whose family role is Child opens the Kids Portal: no side menu, just buttons for Sunday School (lessons, documents, exams), Groups, and Worship when the child is on a worship group (next 12 weeks, with Print and Download). Guess It, Signed-in devices and Delete my account are not available. The Kids Portal works on the website only; the mobile app refuses a child login with the message that the Kids Portal is available on the website.",
            list("Sign in on the website with the child's username and password.",
                 "Use the Sunday School, Groups and Worship buttons at the top."),
            list("Problem: the app says the Kids Portal is available on the website — Fix: open the website in the phone's browser and sign in there.",
                 "Problem: a child sees the full portal, or an adult sees the Kids Portal — Fix: the church office corrects the person's role in the family record."),
            "1.0", list("v1.0 — Initial release."),
            list("kids portal","child login","children","child account","web only")).roles("Member"));

        // roles: Church,SuperAdmin,Admin,User
        add(new HelpArticle("member_portal_access", "Member Portal", "Member Portal Set-up for Staff", "more.publicscreens", false,
            "Share the Member Signup link and choose what each member sees in the portal.",
            "More → Public Screens → Generate a Public Link: choose Member Signup in Select Page (optional Expiration Date), Generate Link, then Copy. A member signs up with the name and email or phone on their family record and a code sent to their email; the login is active at once. The plan must include the Member Portal and limits the number of portal logins. The church owner login manages logins in Admin → Users → 👤 Member Portal Users: 🔐 sets portal access ((defaults) = nothing saved yet) — under MY PROFILE tick Family, Contributions, Groups, Classes, Volunteer, Directory; Ticketing and AI Assistant are off for members unless ticked — then Save Permissions. 🔑 Reset emails a password-reset link, 📝 edits name and email, and 🗑 removes the login and marks the person deleted in Families. Child accounts have no 🔐 button. Members Chat reports arrive in More → Follow-Ups → Reported Content. 🔗 Link Accounts gives a person with several logins a Switch Account button.",
            list("More → Public Screens → Member Signup → Generate Link → Copy and share.",
                 "Church login: Admin → Users → 👤 Member Portal Users → 🔐.",
                 "Tick the sections the member should see and click Save Permissions."),
            list("Problem: a member cannot give online — Fix: connect Stripe under Admin → Stripe Integration and check the member's login is linked to their family record.",
                 "Problem: the 🗑 button deleted the person from Families — Fix: 🗑 removes the login and marks the person deleted; restore the family from Families if it was a mistake."),
            "1.0", list("v1.0 — Initial release."),
            list("member portal access","member permissions","member signup link","public screens","member portal users","reset member password","reported content")).roles("Church,SuperAdmin,Admin,User"));
    }
}

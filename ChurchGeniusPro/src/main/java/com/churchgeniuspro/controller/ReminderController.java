package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page routes for the Reminder Settings section.
 */
@Controller
public class ReminderController {

    /** Hub page that lists all reminder categories. */
    @GetMapping("/reminders")
    public String remindersHub(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "general.reminders");
        if (deny != null) return deny;
        return "forward:/reminders.html";
    }

    @GetMapping("/eventReminders")
    public String eventReminders(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "reminders.event");
        if (deny != null) return deny;
        return "forward:/eventReminders.html";
    }

    @GetMapping("/autoReminders")
    public String autoReminders(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "reminders.auto");
        if (deny != null) return deny;
        return "forward:/autoReminders.html";
    }

    @GetMapping("/oneReminders")
    public String oneReminders(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "reminders.onetime");
        if (deny != null) return deny;
        return "forward:/oneReminders.html";
    }

    @GetMapping("/meetingReminders")
    public String meetingReminders(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        return "forward:/meetingReminders.html";
    }
}

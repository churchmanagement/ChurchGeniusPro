package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Page route for the Attendance module (General section). */
@Controller
public class AttendancePageController {

    @GetMapping("/attendance")
    public String attendancePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "general.attendance");
        if (deny != null) return deny;
        return "forward:/attendance.html";
    }
}

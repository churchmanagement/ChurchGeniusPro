package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Admin → AI Activity page (AI Phase 0): SuperAdmin / Admin staff and the church login. */
@Controller
public class AiActivityPageController {

    @GetMapping("/aiActivity")
    public String page(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrChurch(request);
        if (deny != null) return deny;
        return "forward:/aiActivity.html";
    }
}

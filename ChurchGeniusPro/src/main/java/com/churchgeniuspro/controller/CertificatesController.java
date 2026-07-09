package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Page routes for the Certificates section.
 *
 * <ul>
 *   <li>{@code GET /certificates}                    → {@code certificates.html}</li>
 *   <li>{@code GET /certificates/baptism-certificate}→ {@code baptismCertificate.html}</li>
 * </ul>
 */
@Controller
public class CertificatesController {

    @GetMapping("/certificates")
    public String certificates(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "more.certificates");
        if (deny != null) return deny;
        return "forward:/certificates.html";
    }

    @GetMapping("/certificates/baptism-certificate")
    public String baptismCertificate(HttpServletRequest request) {
        return guarded(request, "/baptismCertificate.html");
    }

    @GetMapping("/certificates/appreciation-certificate")
    public String appreciationCertificate(HttpServletRequest request) {
        return guarded(request, "/appreciationCertificate.html");
    }

    @GetMapping("/certificates/dedication-certificate")
    public String dedicationCertificate(HttpServletRequest request) {
        return guarded(request, "/dedicationCertificate.html");
    }

    @GetMapping("/certificates/completion-certificate")
    public String completionCertificate(HttpServletRequest request) {
        return guarded(request, "/completionCertificate.html");
    }

    @GetMapping("/certificates/sunday-school-certificate")
    public String sundaySchoolCertificate(HttpServletRequest request) {
        return guarded(request, "/sundaySchoolCertificate.html");
    }

    @GetMapping("/certificates/membership-certificate")
    public String membershipCertificate(HttpServletRequest request) {
        return guarded(request, "/membershipCertificate.html");
    }

    @GetMapping("/certificates/marriage-certificate")
    public String marriageCertificate(HttpServletRequest request) {
        return guarded(request, "/marriageCertificate.html");
    }

    @GetMapping("/certificates/other-certificate")
    public String otherCertificate(HttpServletRequest request) {
        return guarded(request, "/otherCertificate.html");
    }

    /** Shared role/permission guard then forward to the given static page. */
    private String guarded(HttpServletRequest request, String page) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "more.certificates");
        if (deny != null) return deny;
        return "forward:" + page;
    }
}

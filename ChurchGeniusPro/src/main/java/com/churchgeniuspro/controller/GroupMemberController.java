package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.service.GroupEmailService;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints for Group Member management and email sending.
 *
 * <h3>Member CRUD</h3>
 * <ul>
 *   <li>{@code GET    /api/groups/{groupId}/members}      → list members</li>
 *   <li>{@code POST   /api/groups/{groupId}/members}      → add member</li>
 *   <li>{@code PUT    /api/group-members/{id}}            → update member</li>
 *   <li>{@code DELETE /api/group-members/{id}}            → remove member</li>
 * </ul>
 *
 * <h3>Email</h3>
 * <ul>
 *   <li>{@code POST /api/groups/email} (multipart) → send BCC email</li>
 * </ul>
 */
@Controller
public class GroupMemberController {

    private final GroupMemberService memberService;
    private final GroupEmailService  emailService;

    public GroupMemberController(GroupMemberService memberService,
                                 GroupEmailService emailService) {
        this.memberService = memberService;
        this.emailService  = emailService;
    }

    // ── List members ──────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/groups/{groupId}/members")
    public ResponseEntity<List<Map<String, Object>>> getMembers(
            @PathVariable Integer groupId,
            HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            return ResponseEntity.ok(memberService.getMembers(groupId, appClientId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Add member ────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/groups/{groupId}/members")
    public ResponseEntity<Map<String, Object>> addMember(
            @PathVariable Integer groupId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String firstName = body.get("firstName");
        String lastName  = body.get("lastName");
        String email     = body.get("email");

        if (isBlank(firstName)) return bad("First name is required.");
        if (isBlank(lastName))  return bad("Last name is required.");

        String appClientId = SessionUtil.getAppClientId(request);
        try {
            GroupMember saved = memberService.addMember(groupId, firstName, lastName, email, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Update member ─────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/group-members/{id}")
    public ResponseEntity<Map<String, Object>> updateMember(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body) {

        String firstName = body.get("firstName");
        String lastName  = body.get("lastName");
        String email     = body.get("email");

        if (isBlank(firstName)) return bad("First name is required.");
        if (isBlank(lastName))  return bad("Last name is required.");

        try {
            GroupMember saved = memberService.updateMember(id, firstName, lastName, email);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Remove member ─────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/group-members/{id}")
    public ResponseEntity<Map<String, Object>> removeMember(@PathVariable Integer id) {
        try {
            memberService.removeMember(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Send BCC email ────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping(value = "/api/groups/email", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> sendEmail(
            @RequestParam("subject")    String subject,
            @RequestParam("body")       String body,
            @RequestParam("bccEmails")  String bccEmailsJson,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment) {

        if (isBlank(subject))       return bad("Subject is required.");
        if (isBlank(body))          return bad("Message body is required.");
        if (isBlank(bccEmailsJson)) return bad("At least one recipient is required.");

        try {
            List<String> bccEmails = parseJsonStringArray(bccEmailsJson);

            if (bccEmails.isEmpty()) return bad("At least one recipient is required.");

            emailService.sendBccEmail(subject, body, bccEmails, attachment);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Email sent to " + bccEmails.size() + " recipient(s)."));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return bad("Failed to send email: " + ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /**
     * Parses a simple JSON string array produced by {@code JSON.stringify([])} on the
     * frontend — e.g. {@code ["a@b.com","c@d.com"]} — without requiring Jackson.
     * Email addresses never contain {@code ","}, so splitting on that delimiter is safe.
     */
    private static List<String> parseJsonStringArray(String json) {
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]"))   inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return Collections.emptyList();
        return Arrays.stream(inner.split("\",\""))
                     .map(s -> s.replaceAll("^\"|\"$", "").trim())
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toList());
    }
}

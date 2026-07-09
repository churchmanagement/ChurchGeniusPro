package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.KmChildSetup;
import com.churchgeniuspro.hibernate.KmClassroom;
import com.churchgeniuspro.repository.KmCheckinRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmChildSetupRepository;
import com.churchgeniuspro.repository.KmClassroomRepository;
import com.churchgeniuspro.service.KmPickupAlertService;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.KmPickupUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public, token-secured pickup page used by alert recipients (from the email/SMS
 * link). The signed token encodes {@code clientId|checkinId} (same encryption the
 * app uses for other public links), so no login is required but a recipient can
 * only act on the specific check-in their link points to. Actions: view status,
 * snooze (global per child), and mark picked up (checks the child out + broadcasts).
 */
@Controller
public class KmPickupPublicController {

    private final KmCheckinRepository    checkinRepo;
    private final KmChildRepository      childRepo;
    private final KmClassroomRepository  classroomRepo;
    private final KmChildSetupRepository setupRepo;
    private final KmPickupAlertService   alertService;

    public KmPickupPublicController(KmCheckinRepository checkinRepo,
                                    KmChildRepository childRepo,
                                    KmClassroomRepository classroomRepo,
                                    KmChildSetupRepository setupRepo,
                                    KmPickupAlertService alertService) {
        this.checkinRepo   = checkinRepo;
        this.childRepo     = childRepo;
        this.classroomRepo = classroomRepo;
        this.setupRepo     = setupRepo;
        this.alertService  = alertService;
    }

    @GetMapping("/kidsPickup")
    public String page() {
        return "forward:/kidsPickup.html";
    }

    /** Decrypted token → [clientId, checkinId] or null. */
    private Object[] resolve(String t) {
        if (t == null || t.isBlank()) return null;
        try {
            String plain = EncryptionUtil.decrypt(t.trim());
            if (plain == null || !plain.contains("|")) return null;
            String[] parts = plain.split("\\|", 2);
            String clientId = parts[0];
            Long checkinId = Long.parseLong(parts[1].trim());
            KmCheckin ci = checkinRepo.findById(checkinId).orElse(null);
            if (ci == null || !clientId.equals(ci.getClientId())) return null;
            return new Object[]{ clientId, ci };
        } catch (Exception e) {
            return null;
        }
    }

    @ResponseBody
    @GetMapping("/api/public/kids-pickup/status")
    public ResponseEntity<?> status(@RequestParam("t") String t) {
        Object[] r = resolve(t);
        if (r == null) return ResponseEntity.status(400).body(Map.of("error", "This link is invalid or has expired."));
        String clientId = (String) r[0];
        KmCheckin ci = (KmCheckin) r[1];
        KmChild child = ci.getChildId() != null ? childRepo.findById(ci.getChildId()).orElse(null) : null;
        KmChildSetup setup = setupRepo.findByClientId(clientId).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = KmPickupUtil.effectiveDeadline(ci, setup);
        boolean checkedOut = ci.getCheckoutTime() != null;
        boolean overdue = !checkedOut && deadline != null && now.isAfter(deadline);
        boolean snoozed = ci.getSnoozeUntil() != null && ci.getSnoozeUntil().isAfter(now);

        String classroom = "";
        if (ci.getClassroomId() != null) {
            KmClassroom rm = classroomRepo.findById(ci.getClassroomId()).orElse(null);
            if (rm != null) classroom = rm.getClassName();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("childName", child != null ? (safe(child.getFirstName()) + " " + safe(child.getLastName())).trim() : "Child");
        m.put("childId", child != null ? child.getId() : null);
        m.put("parentName", child != null ? child.getParentName() : null);
        m.put("classroom", classroom);
        m.put("checkinTime", ci.getCheckinTime() != null ? ci.getCheckinTime().toString() : null);
        m.put("pickupDeadline", deadline != null ? deadline.toString() : null);
        m.put("checkedOut", checkedOut);
        m.put("checkoutTime", ci.getCheckoutTime() != null ? ci.getCheckoutTime().toString() : null);
        m.put("overdue", overdue);
        m.put("overdueMinutes", overdue ? ChronoUnit.MINUTES.between(deadline, now) : null);
        m.put("snoozed", snoozed);
        m.put("snoozeUntil", ci.getSnoozeUntil() != null ? ci.getSnoozeUntil().toString() : null);
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @PostMapping("/api/public/kids-pickup/snooze")
    public ResponseEntity<?> snooze(@RequestBody Map<String, Object> body) {
        Object[] r = resolve(body == null ? null : str(body.get("t")));
        if (r == null) return ResponseEntity.status(400).body(Map.of("error", "Invalid link"));
        KmCheckin ci = (KmCheckin) r[1];
        if (ci.getCheckoutTime() != null) return ResponseEntity.ok(Map.of("success", true, "checkedOut", true));
        int mins;
        try { mins = Integer.parseInt(String.valueOf(body.get("minutes"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid minutes")); }
        if (mins < 1 || mins > 720) return ResponseEntity.badRequest().body(Map.of("error", "Snooze must be 1–720 minutes"));
        ci.setSnoozeUntil(LocalDateTime.now().plusMinutes(mins));
        checkinRepo.save(ci);
        return ResponseEntity.ok(Map.of("success", true, "snoozeUntil", ci.getSnoozeUntil().toString()));
    }

    @ResponseBody
    @PostMapping("/api/public/kids-pickup/picked-up")
    public ResponseEntity<?> pickedUp(@RequestBody Map<String, Object> body) {
        Object[] r = resolve(body == null ? null : str(body.get("t")));
        if (r == null) return ResponseEntity.status(400).body(Map.of("error", "Invalid link"));
        String clientId = (String) r[0];
        KmCheckin ci = (KmCheckin) r[1];
        if (ci.getCheckoutTime() != null)
            return ResponseEntity.ok(Map.of("success", true, "alreadyCheckedOut", true));

        String name = str(body.get("name"));
        String notes = str(body.get("notes"));
        LocalDateTime now = LocalDateTime.now();
        ci.setCheckoutTime(now);
        ci.setCheckedOutBy(name != null && !name.isBlank() ? name.trim() : "Pickup link");
        ci.setCheckedOutUser("pickup-link");
        ci.setCheckoutMethod("PickupConfirm");
        ci.setSnoozeUntil(null);
        String note = "Picked up via secure link by " + (name != null ? name : "recipient") + " at " + now
                + (notes != null && !notes.isBlank() ? " — " + notes.trim() : "");
        ci.setNotes(ci.getNotes() == null || ci.getNotes().isBlank() ? note : (ci.getNotes() + "\n" + note));
        checkinRepo.save(ci);

        String childName = "the child";
        if (ci.getChildId() != null) {
            KmChild child = childRepo.findById(ci.getChildId()).orElse(null);
            if (child != null) childName = (safe(child.getFirstName()) + " " + safe(child.getLastName())).trim();
        }
        try { alertService.broadcastPickedUp(clientId, childName, name); } catch (Exception ignore) { }
        return ResponseEntity.ok(Map.of("success", true, "checkoutTime", now.toString()));
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}

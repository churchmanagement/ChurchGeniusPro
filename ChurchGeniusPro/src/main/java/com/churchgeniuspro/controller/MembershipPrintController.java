package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.MembershipFormExtractor;
import com.churchgeniuspro.service.MembershipFormPdfService;
import com.churchgeniuspro.service.VisionCheckService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Printable/fillable membership-form PDF download, and the scanned-form
 * upload → extract → duplicate-check endpoint used by the Add Family page.
 * (Distinct from the existing {@code MembershipFormController}, which handles the
 * public membership application form.)
 */
@Controller
public class MembershipPrintController {

    private final MembershipFormPdfService pdfService;
    private final MembershipFormExtractor  extractor;
    private final ServiceClientRepository  clientRepo;
    private final ChurchLogoRepository     logoRepo;
    private final FamilyMemberRepository   memberRepo;
    private final VisionCheckService       vision;
    private final com.churchgeniuspro.service.VisionUploadService visionUpload;
    private final com.churchgeniuspro.repository.PublicScreenLinkRepository linkRepo;

    public MembershipPrintController(MembershipFormPdfService pdfService,
                                     MembershipFormExtractor extractor,
                                     ServiceClientRepository clientRepo,
                                     ChurchLogoRepository logoRepo,
                                     FamilyMemberRepository memberRepo,
                                     VisionCheckService vision,
                                     com.churchgeniuspro.service.VisionUploadService visionUpload,
                                     com.churchgeniuspro.repository.PublicScreenLinkRepository linkRepo) {
        this.pdfService = pdfService;
        this.extractor  = extractor;
        this.clientRepo = clientRepo;
        this.logoRepo   = logoRepo;
        this.memberRepo = memberRepo;
        this.vision     = vision;
        this.visionUpload = visionUpload;
        this.linkRepo   = linkRepo;
    }

    /**
     * Branded, printable + fillable membership form. Used by the Family page (blank
     * form) and Public Screens (pass {@code declaration} to include the agreement).
     */
    @GetMapping("/api/membership-form/pdf")
    public ResponseEntity<byte[]> membershipFormPdf(
            @RequestParam(value = "declaration", required = false) String declaration,
            HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String churchName = clientRepo.findByClientId(clientId)
                .map(ServiceClient::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(null);
        ChurchLogo logo = logoRepo.findByClientId(clientId).orElse(null);
        byte[] logoBytes = (logo != null && logo.getLogoData() != null && logo.getLogoData().length > 0) ? logo.getLogoData() : null;
        String logoType  = logo != null ? logo.getContentType() : null;

        // Source the declaration from the org's membership-form public link
        // (public_screen_link.declaration_text). An explicit ?declaration= param
        // (used by the Public Screens preview) still wins when supplied.
        String declarationText = (declaration != null && !declaration.isBlank()) ? declaration : null;
        if (declarationText == null) {
            declarationText = linkRepo
                    .findByAppClientIdAndPageUrlAndRevokedFalseOrderByCreatedDateDesc(clientId, "/membershipForm")
                    .stream()
                    .filter(l -> Boolean.TRUE.equals(l.getShowDeclaration()))
                    .map(com.churchgeniuspro.hibernate.PublicScreenLink::getDeclarationText)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst()
                    .orElse(null);
        }

        byte[] pdf = pdfService.generate(churchName, logoBytes, logoType, declarationText);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"membership-form.pdf\"")
                .body(pdf);
    }

    /**
     * Reads an uploaded membership form (digital PDF) and returns the extracted
     * member fields plus any existing-member match for de-duplication.
     */
    @ResponseBody
    @PostMapping(value = "/api/family/scan-extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> scanExtract(@RequestParam("file") MultipartFile file,
                                                           HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "admin.family.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));

        byte[] bytes;
        MembershipFormExtractor.Result result;
        try {
            bytes = file.getBytes();
            result = extractor.extract(bytes, file.getOriginalFilename(), file.getContentType());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not read the file: " + e.getMessage()));
        }

        // Scanned photo / image with no readable text → use the vision model when
        // configured AND the church is under its OpenAI Vision quota.
        if (result.fields.isEmpty() && result.additionalMembers.isEmpty()
                && vision.isEnabled() && visionUpload.available(clientId)) {
            try {
                com.churchgeniuspro.service.VisionUploadService.Prepared prep =
                        visionUpload.prepare(clientId, bytes, file.getContentType(), file.getOriginalFilename());
                Map<String, Object> vf = vision.extractMembershipForm(prep.bytes, prep.contentType);
                visionUpload.recordUse(clientId);
                if (vf != null) {
                    @SuppressWarnings("unchecked") Map<String, String> primary = (Map<String, String>) vf.get("primary");
                    @SuppressWarnings("unchecked") List<Map<String, String>> members = (List<Map<String, String>>) vf.get("members");
                    MembershipFormExtractor.Result vr = extractor.fromVisionFields(primary, members);
                    if (!vr.fields.isEmpty() || !vr.additionalMembers.isEmpty()) result = vr;
                }
            } catch (Exception ignore) { /* keep the original result */ }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("method", result.method);
        if (result.note != null && !result.note.isEmpty()) resp.put("note", result.note);
        resp.put("fields", result.fields);
        resp.put("additionalMembers", result.additionalMembers);

        // ── duplicate check by email, then phone (same client) ──
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("found", false);
        FamilyMember match = null;
        String email = result.fields.get("email");
        String phone = result.fields.get("phone");
        if (email != null && !email.isBlank()) match = first(memberRepo.findByPhoneOrEmailAndClient(email.trim(), clientId));
        if (match == null && phone != null && !phone.isBlank()) match = first(memberRepo.findByPhoneOrEmailAndClient(phone.trim(), clientId));
        if (match != null) {
            dup.put("found", true);
            dup.put("familyId", match.getFamily() != null ? match.getFamily().getId() : null);
            dup.put("memberId", match.getId());
            dup.put("member", memberMap(match));
        }
        resp.put("duplicate", dup);
        return ResponseEntity.ok(resp);
    }

    private static FamilyMember first(List<FamilyMember> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static Map<String, Object> memberMap(FamilyMember m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("firstName", m.getFirstName());
        x.put("lastName",  m.getLastName());
        x.put("otherName", m.getOtherName());
        x.put("email",     m.getEmail());
        x.put("phone",     m.getPhone());
        x.put("memberType", m.getMemberType());
        x.put("gender",    m.getGender());
        x.put("address1",  m.getAddress1());
        x.put("address2",  m.getAddress2());
        x.put("city",      m.getCity());
        x.put("state",     m.getState());
        x.put("country",   m.getCountry());
        x.put("pinCode",   m.getPinCode());
        x.put("birthdayMonth", m.getBirthdayMonth());
        x.put("birthdayDay",   m.getBirthdayDay());
        x.put("birthdayYear",  m.getBirthdayYear());
        return x;
    }
}

package com.churchgeniuspro.controller;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.Address;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central church profile — the single source of truth for the Church Name and
 * Church Address, editable by Admin / SuperAdmin from the same area as the church
 * logo ({@code /logo}).
 *
 * <p>Source of truth is {@code church_registration.church_name} plus the linked
 * {@code address} row (via {@code church_registration.address_id}). The matching
 * {@link ServiceClient} columns are kept in sync so the modules that resolve the
 * church name/address via {@code ServiceClient} also reflect updates. All PDFs,
 * emails, SMS, reports and pages already read from these records at generation
 * time, so saving here propagates everywhere with no hardcoded values.
 *
 * <ul>
 *   <li>{@code GET /api/church-profile} — current name + address (with fallbacks).</li>
 *   <li>{@code PUT /api/church-profile} — update name + address (Admin/SuperAdmin).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/church-profile")
public class ChurchProfileController {

    /** Country code → display name. Kept in sync with the logo.html dropdown. */
    private static final Map<Integer, String> COUNTRIES = Map.of(
            1, "United States", 2, "Canada", 3, "United Kingdom",
            4, "Australia", 5, "India", 6, "Other");

    private static String countryName(Integer c) { return c == null ? null : COUNTRIES.get(c); }

    private final ChurchRegistrationRepository churchRepo;
    private final ServiceClientRepository      clientRepo;

    public ChurchProfileController(ChurchRegistrationRepository churchRepo,
                                   ServiceClientRepository clientRepo) {
        this.churchRepo = churchRepo;
        this.clientRepo = clientRepo;
    }

    /** Admin, SuperAdmin or Church may view/edit church settings. */
    private static boolean notAllowed(HttpServletRequest request) {
        return RoleGuard.requireAdmin(request) != null && !RoleGuard.isChurch(request);
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest request) {
        if (notAllowed(request))
            return ResponseEntity.status(403).body(Map.of("error", "Not authorized"));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
        ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        // Church name: church_registration first (the spec default), else ServiceClient.
        String name = cr != null && cr.getChurchName() != null && !cr.getChurchName().isBlank()
                ? cr.getChurchName()
                : (sc != null ? sc.getChurchName() : null);
        out.put("churchName", nz(name));

        Address a = cr != null ? cr.getAddress() : null;
        if (a != null) {
            out.put("address1",  nz(a.getAddress1()));
            out.put("address2",  nz(a.getAddress2()));
            out.put("city",      nz(a.getCity()));
            out.put("state",     a.getState());                                  // numeric code 1–50
            out.put("stateName", a.getState() != null ? nz(States.getCode(a.getState())) : "");
            out.put("country",   a.getCountry());                                 // numeric country code
            out.put("countryName", nz(countryName(a.getCountry())));
            out.put("pinCode",   nz(a.getPinCode()));
        } else if (sc != null) {
            // Fallback to ServiceClient address columns when no Address row exists yet.
            out.put("address1",  nz(sc.getAddressLine1()));
            out.put("address2",  nz(sc.getAddressLine2()));
            out.put("city",      nz(sc.getCity()));
            int code = States.getCodeByAbbreviation(sc.getState());
            out.put("state",     code > 0 ? code : null);
            out.put("stateName", nz(sc.getState()));
            out.put("country",   null);
            out.put("countryName", nz(sc.getCountry()));
            out.put("pinCode",   nz(sc.getPinCode()));
        } else {
            out.put("address1", ""); out.put("address2", ""); out.put("city", "");
            out.put("state", null);  out.put("stateName", ""); out.put("country", null); out.put("pinCode", "");
        }

        // Website & social media — church_registration first, else ServiceClient.
        out.put("websiteUrl",   nz(firstNonBlank(cr != null ? cr.getWebsiteUrl()   : null, sc != null ? sc.getWebsiteUrl()   : null)));
        out.put("facebookUrl",  nz(firstNonBlank(cr != null ? cr.getFacebookUrl()  : null, sc != null ? sc.getFacebookUrl()  : null)));
        out.put("instagramUrl", nz(firstNonBlank(cr != null ? cr.getInstagramUrl() : null, sc != null ? sc.getInstagramUrl() : null)));
        out.put("youtubeUrl",   nz(firstNonBlank(cr != null ? cr.getYoutubeUrl()   : null, sc != null ? sc.getYoutubeUrl()   : null)));
        return ResponseEntity.ok(out);
    }

    @PutMapping
    @Transactional
    public ResponseEntity<?> update(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        if (notAllowed(request))
            return ResponseEntity.status(403).body(Map.of("error", "Not authorized"));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String churchName = str(body.get("churchName"));
        if (churchName == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Church Name is required."));

        String  address1 = str(body.get("address1"));
        String  address2 = str(body.get("address2"));
        String  city     = str(body.get("city"));
        Integer state    = intOrNull(body.get("state"));
        Integer country  = intOrNull(body.get("country"));
        String  pinCode  = str(body.get("pinCode"));

        // Website & social URLs — optional, but must be valid http(s) URLs if present.
        String websiteUrl   = str(body.get("websiteUrl"));
        String facebookUrl  = str(body.get("facebookUrl"));
        String instagramUrl = str(body.get("instagramUrl"));
        String youtubeUrl   = str(body.get("youtubeUrl"));
        String badUrl = firstInvalidUrl(
                "Website URL",   websiteUrl,
                "Facebook URL",  facebookUrl,
                "Instagram URL", instagramUrl,
                "YouTube URL",   youtubeUrl);
        if (badUrl != null)
            return ResponseEntity.badRequest().body(Map.of("error", badUrl));

        // ── Centralized source: church_registration (+ its address row). ──
        ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
        if (cr != null) {
            cr.setChurchName(churchName);
            Address a = cr.getAddress();
            if (a == null) {
                a = new Address();
                a.setType(Address.AddressType.CHURCH);
                a.setDeleteFlag(false);
                cr.setAddress(a);   // cascade ALL inserts the new address row
            }
            a.setAddress1(address1);
            a.setAddress2(address2);
            a.setCity(city);
            a.setState(state);
            a.setCountry(country);
            a.setPinCode(pinCode);
            cr.setWebsiteUrl(websiteUrl);
            cr.setFacebookUrl(facebookUrl);
            cr.setInstagramUrl(instagramUrl);
            cr.setYoutubeUrl(youtubeUrl);
            churchRepo.save(cr);
        }

        // ── Keep ServiceClient in sync (read by donations, badges, SMS, etc.). ──
        ServiceClient sc = clientRepo.findByClientId(clientId).orElse(null);
        if (sc != null) {
            sc.setChurchName(churchName);
            sc.setAddressLine1(address1);
            sc.setAddressLine2(address2);
            sc.setCity(city);
            sc.setState(state != null ? States.getCode(state) : null);
            String cName = countryName(country);
            if (cName != null) sc.setCountry(cName);
            sc.setPinCode(pinCode);
            sc.setWebsiteUrl(websiteUrl);
            sc.setFacebookUrl(facebookUrl);
            sc.setInstagramUrl(instagramUrl);
            sc.setYoutubeUrl(youtubeUrl);
            clientRepo.save(sc);
        }

        // Refresh the session so the app header + /api/session reflect the new name now.
        HttpSession session = request.getSession(false);
        if (session != null) session.setAttribute("churchName", churchName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("churchName", churchName);
        out.put("websiteUrl", nz(websiteUrl));
        out.put("facebookUrl", nz(facebookUrl));
        out.put("instagramUrl", nz(instagramUrl));
        out.put("youtubeUrl", nz(youtubeUrl));
        return ResponseEntity.ok(out);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String nz(String s) { return s == null ? "" : s; }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /** Returns true when a URL is null/blank (optional) or a valid http(s) URL. */
    static boolean isValidOptionalUrl(String url) {
        if (url == null || url.isBlank()) return true;
        String u = url.trim();
        if (!u.regionMatches(true, 0, "http://", 0, 7)
                && !u.regionMatches(true, 0, "https://", 0, 8)) return false;
        try {
            java.net.URI uri = new java.net.URI(u);
            String host = uri.getHost();
            return host != null && host.contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    /** Validates label/value pairs; returns an error message for the first invalid URL, else null. */
    private static String firstInvalidUrl(String... labelThenValue) {
        for (int i = 0; i + 1 < labelThenValue.length; i += 2) {
            if (!isValidOptionalUrl(labelThenValue[i + 1])) {
                return labelThenValue[i] + " must be a valid URL starting with http:// or https://";
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            String s = String.valueOf(o).trim();
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (Exception e) { return null; }
    }
}

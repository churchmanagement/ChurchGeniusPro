package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Returns the shared <em>family photo</em> for the currently logged-in user, used by
 * the sidebar avatar in {@code shell.js}. The same family picture is shown for every
 * member of a family (Head, Spouse, Children, …) by always resolving to one
 * representative photo for the family — the Head of Household's photo when present,
 * otherwise the first family member that has one.
 *
 * <p>The returned image is the pre-computed, optimized 60&nbsp;px JPEG thumbnail
 * ({@code FamilyMember.photoThumbnail}) — small and fast to load while still looking
 * crisp at avatar size.
 *
 * <p>Resolution by session type:
 * <ul>
 *   <li><b>Member portal</b> — {@code memberId} → that member's family → shared photo.</li>
 *   <li><b>Staff / non-church</b> — {@code clientId} → {@code app_user.email} → the
 *       matching family member's family → shared photo.</li>
 *   <li><b>Church account</b> — has no associated family; returns no photo (the sidebar
 *       user block is hidden for church accounts anyway).</li>
 * </ul>
 *
 * <p>Always responds HTTP 200 with {@code {"photo": "<data-uri>"}} or
 * {@code {"photo": null}} so the frontend can degrade gracefully to the default icon.
 */
@RestController
public class FamilyAvatarController {

    private final FamilyMemberRepository familyMemberRepository;
    private final AppUserRepository appUserRepository;

    public FamilyAvatarController(FamilyMemberRepository familyMemberRepository,
                                  AppUserRepository appUserRepository) {
        this.familyMemberRepository = familyMemberRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/family/avatar")
    public ResponseEntity<Map<String, Object>> getFamilyAvatar(HttpServletRequest request) {

        Map<String, Object> body = new HashMap<>();
        body.put("photo", null);

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("clientId") == null) {
            return ResponseEntity.ok(body);   // not logged in — default icon
        }

        // Church accounts have no family; nothing to resolve.
        if (Boolean.TRUE.equals(session.getAttribute("church"))) {
            return ResponseEntity.ok(body);
        }

        String thumb = null;

        try {
            // 1) Member portal — resolve directly from the member's own record.
            Object memberIdAttr = session.getAttribute("memberId");
            if (memberIdAttr instanceof Integer memberId) {
                thumb = familyMemberRepository.findFamilyThumbnailByMemberId(memberId);
            }

            // 2) Staff / non-church — resolve via the login account's email.
            if ((thumb == null || thumb.isBlank())) {
                String clientId = str(session.getAttribute("clientId"));
                if (!clientId.isEmpty()) {
                    AppUser appUser = appUserRepository
                            .findByUserIdAndDeleteFlagFalse(clientId)
                            .orElse(null);
                    if (appUser != null && appUser.getEmail() != null
                            && !appUser.getEmail().isBlank()) {
                        thumb = familyMemberRepository
                                .findFamilyThumbnailByEmail(appUser.getEmail().trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // Avatar is purely cosmetic — never fail the request.
        }

        if (thumb != null && !thumb.isBlank()) {
            body.put("photo", thumb);
        }
        return ResponseEntity.ok(body);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

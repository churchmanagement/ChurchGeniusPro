package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.util.PhotoImages;
import com.churchgeniuspro.util.PublicSendLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Item 10 — after a membership request carrying a new photo is applied, the Family
 * page (photo_data) and View Family (COALESCE(photo_thumbnail, photo_data)) must show
 * the same picture. The old hook copied photo_data only, so the stale thumbnail won.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Membership approval — photo and thumbnail stay in sync")
class MembershipApprovalPhotoSyncTest {

    static final String OURS = "CHURCH-A";

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock MembershipFamilyRepository mfRepo;
    @Mock MembershipFamilyMemberRepository mfmRepo;
    @Mock FamilyRepository familyRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock PublicSendLimiter sendLimiter;
    @InjectMocks MembershipFormController controller;

    static String png(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b.toByteArray());
    }

    HttpServletRequest admin() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession s = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(s);
        when(s.getAttribute("username")).thenReturn("admin@example.org");
        when(s.getAttribute("role")).thenReturn("Admin");
        when(s.getAttribute("appClientId")).thenReturn(OURS);
        return req;
    }

    @Test
    @DisplayName("existing member with an old thumbnail: approval replaces BOTH photo_data and photo_thumbnail")
    void existingMemberThumbnailRefreshed() throws Exception {
        String oldPhoto = png(300, 300, 0xFF0000);
        String newPhoto = png(1200, 900, 0x0000FF);

        FamilyMember existing = new FamilyMember();
        existing.setId(71); existing.setFirstName("Ada"); existing.setLastName("Lovelace"); existing.setRole("Head of Household");
        existing.setPhotoData(oldPhoto); existing.setPhotoThumbnail(PhotoImages.thumbnail(oldPhoto));
        Family fam = new Family(); fam.setId(7); fam.setAppClientId(OURS);
        existing.setFamily(fam);

        MembershipFamily mf = new MembershipFamily(); mf.setId(5); mf.setAppClientId(OURS); mf.setExistingFamilyId(7);
        MembershipFamilyMember sub = new MembershipFamilyMember();
        sub.setRole("Head"); sub.setFirstName("Ada"); sub.setLastName("Lovelace"); sub.setPhotoData(newPhoto); sub.setAppClientId(OURS);

        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(mf));
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(sub));
        when(familyRepo.findById(7)).thenReturn(Optional.of(fam));
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
        when(familyMemberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(existing));
        when(familyMemberRepo.save(any(FamilyMember.class))).thenAnswer(i -> i.getArgument(0));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> res = controller.apply(5, admin());
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();

        ArgumentCaptor<FamilyMember> cap = ArgumentCaptor.forClass(FamilyMember.class);
        verify(familyMemberRepo, atLeastOnce()).save(cap.capture());
        FamilyMember saved = cap.getAllValues().stream().filter(m -> Integer.valueOf(71).equals(m.getId())).findFirst().orElseThrow();
        assertThat(saved.getId()).isEqualTo(71);
        assertThat(saved.getPhotoData()).isNotEqualTo(oldPhoto).startsWith("data:image/jpeg;base64,");   // normalised to ≤ 800 px
        BufferedImage full = PhotoImages.decode(saved.getPhotoData());
        assertThat(Math.max(full.getWidth(), full.getHeight())).isEqualTo(800);

        assertThat(saved.getPhotoThumbnail()).as("thumbnail regenerated from the NEW photo").isNotEqualTo(PhotoImages.thumbnail(oldPhoto));
        BufferedImage t = PhotoImages.decode(saved.getPhotoThumbnail());
        assertThat(Math.max(t.getWidth(), t.getHeight())).isEqualTo(60);
        // the thumbnail is of the blue photo, not the red one
        assertThat(t.getRGB(t.getWidth() / 2, t.getHeight() / 2) & 0x0000FF).isGreaterThan(0xC0);
        assertThat((t.getRGB(t.getWidth() / 2, t.getHeight() / 2) >> 16) & 0xFF).isLessThan(0x40);
    }

    @Test
    @DisplayName("new family: the created member gets a thumbnail (so View Family and the sidebar avatar have one)")
    void newFamilyGetsThumbnail() throws Exception {
        MembershipFamily mf = new MembershipFamily(); mf.setId(6); mf.setAppClientId(OURS);
        MembershipFamilyMember sub = new MembershipFamilyMember();
        sub.setRole("Head"); sub.setFirstName("Grace"); sub.setLastName("Hopper"); sub.setPhotoData(png(500, 500, 0x00FF00)); sub.setAppClientId(OURS);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(6, OURS)).thenReturn(Optional.of(mf));
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(6)).thenReturn(List.of(sub));
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> { Family f = i.getArgument(0); f.setId(99); return f; });
        when(familyMemberRepo.save(any(FamilyMember.class))).thenAnswer(i -> i.getArgument(0));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> res = controller.apply(6, admin());
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        ArgumentCaptor<FamilyMember> cap = ArgumentCaptor.forClass(FamilyMember.class);
        verify(familyMemberRepo).save(cap.capture());
        assertThat(cap.getValue().getPhotoData()).isNotBlank();
        assertThat(cap.getValue().getPhotoThumbnail()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    @DisplayName("public submit stores the photo at ≤ 800 px (membershipForm.html does not resize in the browser)")
    void submitNormalises() throws Exception {
        PublicScreenLink link = new PublicScreenLink();
        link.setAppClientId(OURS); link.setPageUrl("/membershipForm"); link.setToken("tok"); link.setRevoked(false);
        when(linkRepo.findByToken("tok")).thenReturn(Optional.of(link));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        String big = png(1600, 1600, 0x123456);
        Map<String, Object> body = Map.of("cid", "tok", "declarationAccepted", Boolean.TRUE,
                "members", List.of(Map.of("role", "Head", "firstName", "Ada", "lastName", "Lovelace", "photo", big)));
        ResponseEntity<Map<String, Object>> res = controller.submit(body, new MockHttpServletRequest());
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();

        ArgumentCaptor<MembershipFamilyMember> cap = ArgumentCaptor.forClass(MembershipFamilyMember.class);
        verify(mfmRepo).save(cap.capture());
        BufferedImage stored = PhotoImages.decode(cap.getValue().getPhotoData());
        assertThat(stored.getWidth()).isEqualTo(800);
    }
}

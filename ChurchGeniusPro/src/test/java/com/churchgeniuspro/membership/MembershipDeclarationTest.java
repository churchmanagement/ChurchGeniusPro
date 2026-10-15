package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.util.PublicSendLimiter;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Item 2 — the declaration is mandatory exactly when the form shows it, on the client AND the server. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Membership form — declaration acceptance")
class MembershipDeclarationTest {

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock MembershipFamilyRepository mfRepo;
    @Mock MembershipFamilyMemberRepository mfmRepo;
    @Mock ChurchRegistrationRepository churchRegRepo;
    @Mock ChurchLogoRepository logoRepo;
    @Mock PublicSendLimiter sendLimiter;
    @InjectMocks MembershipFormController controller;

    PublicScreenLink link(Boolean show, String text) {
        PublicScreenLink l = new PublicScreenLink();
        l.setAppClientId("C"); l.setPageUrl("/membershipForm"); l.setToken("tok"); l.setRevoked(false);
        l.setShowDeclaration(show); l.setDeclarationText(text);
        when(linkRepo.findByToken("tok")).thenReturn(Optional.of(l));
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse("C")).thenReturn(Optional.empty());
        when(logoRepo.findByClientId("C")).thenReturn(Optional.empty());
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));
        return l;
    }

    ResponseEntity<Map<String, Object>> submit(Object accepted) {
        Map<String, Object> body = new HashMap<>();
        body.put("cid", "tok");
        if (accepted != null) body.put("declarationAccepted", accepted);
        body.put("members", List.of(Map.of("role", "Head", "firstName", "Ada", "lastName", "L")));
        return controller.submit(body, new MockHttpServletRequest());
    }

    @Test
    @DisplayName("shown (switch on + text): submit without acceptance is refused with 400 and nothing is stored — a direct API call cannot bypass it")
    void requiredWhenShown() {
        link(true, "I affirm the statement of faith.");
        assertThat(controller.getConfig("tok").getBody()).containsEntry("showDeclaration", true);
        for (Object v : new Object[]{null, false, "true", "yes"}) {
            ResponseEntity<Map<String, Object>> r = submit(v);
            assertThat(r.getStatusCode().value()).as("declarationAccepted=" + v).isEqualTo(400);
            assertThat(String.valueOf(r.getBody().get("error"))).contains("accept the declaration");
        }
        verify(mfRepo, never()).save(any());

        ResponseEntity<Map<String, Object>> ok = submit(true);
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();
        ArgumentCaptor<MembershipFamily> cap = ArgumentCaptor.forClass(MembershipFamily.class);
        verify(mfRepo).save(cap.capture());
        assertThat(cap.getValue().getDeclarationAccepted()).isTrue();
    }

    @Test
    @DisplayName("not shown (switch off): not required; the stored flag records what was sent")
    void notRequiredWhenOff() {
        link(false, "some text");
        assertThat(controller.getConfig("tok").getBody()).containsEntry("showDeclaration", false);
        assertThat(submit(null).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("switch on but BLANK text: the card cannot be shown, so it is not required (no deadlock) and config says so")
    void blankTextNotRequired() {
        link(true, "   ");
        assertThat(controller.getConfig("tok").getBody()).containsEntry("showDeclaration", false);
        assertThat(submit(false).getStatusCode().is2xxSuccessful()).isTrue();
        link(true, null);
        assertThat(controller.getConfig("tok").getBody()).containsEntry("showDeclaration", false);
        assertThat(submit(null).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("the page requires the box only when the card is shown, shows a visible message, and has a submitHint for server errors")
    void pageWiring() throws Exception {
        String form = Files.readString(Path.of("src/main/resources/static/membershipForm.html"));
        assertThat(form).contains("showDeclaration = !!data.showDeclaration && !!declarationText;")
                        .contains("if (showDeclaration && declChk && !declChk.checked) {")
                        .contains("Please read and accept the declaration above.")
                        .contains("id=\"submitHint\"")
                        .contains("declChk.focus();");
    }
}

package com.churchgeniuspro.platform;

import com.churchgeniuspro.controller.ServiceAdminPlatformSettingsController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.PlatformSetting;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SupportTicket;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.PlatformSettingRepository;
import com.churchgeniuspro.repository.SupportTicketRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PlatformSettingService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.SupportTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Support Email: one configurable inbox, default support@churchgeniuspro.com, live on save. */
@DisplayName("Platform Settings — Support Email")
class SupportEmailSettingTest {

    final Map<String, PlatformSetting> rows = new HashMap<>();
    PlatformSettingRepository repo;
    PlatformSettingService settings;

    @BeforeEach
    void setUp() {
        repo = mock(PlatformSettingRepository.class);
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get((String) i.getArgument(0))));
        when(repo.save(any(PlatformSetting.class))).thenAnswer(i -> { PlatformSetting s = i.getArgument(0); rows.put(s.getKey(), s); return s; });
        settings = new PlatformSettingService(repo);
    }

    private static MockHttpServletRequest serviceAdmin() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("role", "ServiceAdmin");
        r.getSession().setAttribute("serviceAdminUsername", "ops");
        return r;
    }

    @Test @DisplayName("defaults to support@churchgeniuspro.com until configured")
    void defaultValue() {
        assertThat(settings.supportEmail()).isEqualTo("support@churchgeniuspro.com");
    }

    @Test @DisplayName("a saved address is live immediately (cache cleared), and blank returns to the default")
    void saveIsImmediate() {
        assertThat(settings.supportEmail()).isEqualTo("support@churchgeniuspro.com");   // now cached
        settings.set(PlatformSettingService.SUPPORT_EMAIL, "help@example.org", "ops");
        assertThat(settings.supportEmail()).isEqualTo("help@example.org");
        settings.set(PlatformSettingService.SUPPORT_EMAIL, "  ", "ops");
        assertThat(settings.supportEmail()).isEqualTo("support@churchgeniuspro.com");
    }

    @Test @DisplayName("endpoint: Service Admin only, validates the address, records who saved it")
    void endpoint() {
        var ctl = new ServiceAdminPlatformSettingsController(settings);
        assertThat(ctl.get(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        MockHttpServletRequest church = new MockHttpServletRequest();
        church.getSession(true).setAttribute("role", "SuperAdmin");
        assertThat(ctl.update(Map.of("supportEmail", "x@y.org"), church).getStatusCode().value()).isEqualTo(401);

        assertThat(ctl.update(Map.of("supportEmail", "not-an-email"), serviceAdmin()).getStatusCode().value()).isEqualTo(400);
        ResponseEntity<Map<String, Object>> ok = ctl.update(Map.of("supportEmail", "help@example.org"), serviceAdmin());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().get("supportEmail")).isEqualTo("help@example.org");
        assertThat(rows.get(PlatformSettingService.SUPPORT_EMAIL).getUpdatedBy()).isEqualTo("ops");
        assertThat(ctl.get(serviceAdmin()).getBody().get("supportEmail")).isEqualTo("help@example.org");
    }

    @Test @DisplayName("Support Tickets go to the configured address (content and sender name unchanged)")
    void ticketsUseConfiguredAddress() throws Exception {
        settings.set(PlatformSettingService.SUPPORT_EMAIL, "help@example.org", "ops");
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        when(tickets.save(any(SupportTicket.class))).thenAnswer(i -> { SupportTicket t = i.getArgument(0); if (t.getId() == null) { t.setId(1L); t.onCreate(); } return t; });
        EmailService email = mock(EmailService.class);
        SubscriptionService subs = mock(SubscriptionService.class);
        SubscriptionPlan pro = new SubscriptionPlan(); pro.setPlanCode("PRO"); pro.setPlanName("Pro");
        when(subs.getPlan("CHR-1")).thenReturn(pro);
        ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
        ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Grace Chapel");
        when(churches.findByClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(Optional.of(cr));

        SupportTicketService svc = new SupportTicketService(tickets, churches, email, subs);
        svc.setPlatformSettings(settings);
        svc.submit("CHR-1", "staff1", "Admin",
                new SupportTicketService.NewTicket("Cannot save", "Pat Lee", "pat@grace.test", "Steps…", "High"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
        verify(email, times(2)).sendComposed(to.capture(), isNull(), anyString(), anyString(), isNull(), eq("ChurchGeniusPro Support"));
        assertThat(to.getAllValues()).contains(List.of("help@example.org"));
        assertThat(to.getAllValues()).doesNotContain(List.of("support@churchgeniuspro.com"));
    }
}

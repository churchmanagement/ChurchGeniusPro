package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.SmsOptIn;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HELP / INFO reply text: who is texting, where to get help, rates, how to stop. Invented data. */
@DisplayName("SMS webhook — HELP reply")
class SmsHelpReplyTest {

    SmsOptInController controller(ChurchRegistrationRepository churches) {
        SmsOptInController c = mock(SmsOptInController.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(c, "churchRepo", churches);
        return c;
    }

    @Test
    @DisplayName("names the church the number signed up with, the support address, rates and STOP")
    void namesChurch() {
        ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
        ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Grace Community Church");
        when(churches.findByClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(Optional.of(cr));
        SmsOptIn rec = new SmsOptIn(); rec.setAppClientId("CHR-1");
        String m = controller(churches).helpMessage(List.of(rec));
        assertThat(m).startsWith("Grace Community Church: Texts from Grace Community Church via ChurchGeniusPro.")
                .contains("support@churchgeniuspro.com").contains("Msg & data rates may apply").endsWith("Reply STOP to opt out.");
        assertThat(m.length()).isLessThanOrEqualTo(320);
    }

    @Test
    @DisplayName("a number with no sign-up still gets the platform's help text")
    void unknownNumber() {
        String m = controller(mock(ChurchRegistrationRepository.class)).helpMessage(List.of());
        assertThat(m).startsWith("ChurchGeniusPro:").contains("support@churchgeniuspro.com").endsWith("Reply STOP to opt out.");
    }
}

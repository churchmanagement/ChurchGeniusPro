package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.ChurchVoiceSetting;
import com.churchgeniuspro.hibernate.OpenAiUsage;
import com.churchgeniuspro.repository.ChurchVoiceSettingRepository;
import com.churchgeniuspro.repository.OpenAiUsageRepository;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A trial (prospect) account created from a registration link gets exactly the
 * same AI and Voice settings as a Registered Client on a full plan.
 *
 * <p>Both screens use one code path keyed by clientId, so this pins that nothing
 * about the {@code TRIAL-} prefix changes the defaults, and that the Service Admin
 * reset still works for a trial account.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial account AI / Voice defaults match Registered Clients")
class TrialAiVoiceDefaultsTest {

    private static final String TRIAL  = TestDataService.TRIAL_CLIENT_PREFIX + "1759000000000";
    private static final String PAYING = "CHR-full-plan-church";

    @Mock private OpenAiUsageRepository        usageRepo;
    @Mock private ChurchVoiceSettingRepository voiceRepo;

    private final Map<String, OpenAiUsage> usageRows = new HashMap<>();

    private OpenAiUsageService        usage;
    private ChurchVoiceSettingService voice;

    @BeforeEach
    void setUp() {
        when(usageRepo.findByClientId(anyString()))
                .thenAnswer(i -> Optional.ofNullable(usageRows.get((String) i.getArgument(0))));
        when(usageRepo.save(any(OpenAiUsage.class))).thenAnswer(i -> {
            OpenAiUsage u = i.getArgument(0);
            usageRows.put(u.getClientId(), u);
            return u;
        });
        when(voiceRepo.findByClientId(anyString())).thenReturn(Optional.<ChurchVoiceSetting>empty());
        usage = new OpenAiUsageService(usageRepo);
        voice = new ChurchVoiceSettingService(voiceRepo);
    }

    @Test
    @DisplayName("AI: voice 30 min enabled, vision 1000 uploads enabled, same image controls")
    void aiDefaults() {
        Map<String, Object> t = usage.adminMap(TRIAL);
        assertThat(t.get("voiceEnabled")).isEqualTo(true);
        assertThat(t.get("voiceLimitMinutes")).isEqualTo(30);
        assertThat(t.get("voiceUsedSeconds")).isEqualTo(0L);
        assertThat(t.get("visionEnabled")).isEqualTo(true);
        assertThat(t.get("visionLimitUploads")).isEqualTo(1000);
        assertThat(t.get("visionUsedUploads")).isEqualTo(0L);
        assertThat(t.get("maxFileSizeMb")).isEqualTo(5);
        assertThat(t.get("maxPagesPerUpload")).isEqualTo(2);
        assertThat(t.get("maxImageResolutionPx")).isEqualTo(2000);
        assertThat(t.get("autoResizeImages")).isEqualTo(true);
        assertThat(t.get("jpegQuality")).isEqualTo(85);

        Map<String, Object> p = new HashMap<>(usage.adminMap(PAYING));
        Map<String, Object> tr = new HashMap<>(t);
        p.remove("clientId"); tr.remove("clientId");
        assertThat(tr).isEqualTo(p);
    }

    @Test
    @DisplayName("AI: usage is tracked and the Service Admin resets work")
    void usageAndReset() {
        usage.addVoiceSeconds(TRIAL, 90);
        usage.incrementVisionUploads(TRIAL, 3);
        assertThat(usage.adminMap(TRIAL).get("voiceUsedSeconds")).isEqualTo(90L);
        assertThat(usage.adminMap(TRIAL).get("visionUsedUploads")).isEqualTo(3L);

        usage.resetVoice(TRIAL);
        usage.resetVision(TRIAL);
        assertThat(usage.adminMap(TRIAL).get("voiceUsedSeconds")).isEqualTo(0L);
        assertThat(usage.adminMap(TRIAL).get("visionUsedUploads")).isEqualTo(0L);
        assertThat(usage.voiceAvailable(TRIAL)).isTrue();
        assertThat(usage.visionAvailable(TRIAL)).isTrue();
    }

    @Test
    @DisplayName("Voice: every feature starts ON, identical to a Registered Client")
    void voiceDefaults() {
        assertThat(voice.rawMap(TRIAL)).isEqualTo(voice.rawMap(PAYING));
        assertThat(voice.effectiveMap(TRIAL)).isEqualTo(voice.effectiveMap(PAYING));
        assertThat(voice.effectiveMap(TRIAL).values()).containsOnly(true);
    }
}

package com.churchgeniuspro.accounting;

import com.churchgeniuspro.hibernate.FinancialReportLetter;
import com.churchgeniuspro.repository.FinancialReportLetterRepository;
import com.churchgeniuspro.service.FinancialReportLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The signature under "Sincerely," on the Year-End Tax Report and the member's
 * Financial Report: the church name and "Finance Department" were hard-coded in both
 * pages. They are now part of the letter configuration, with those two as defaults.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Financial Report — editable signature")
class FinancialReportSignatureTest {

    private static final String TENANT = "CHR-1";

    @Mock FinancialReportLetterRepository repo;
    FinancialReportLetterService service;

    @BeforeEach
    void setUp() {
        service = new FinancialReportLetterService(repo);
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(anyString())).thenReturn(Optional.empty());
    }

    private FinancialReportLetter row(String name, String title) {
        FinancialReportLetter r = new FinancialReportLetter();
        r.setAppClientId(TENANT); r.setIntroHtml("<p>x</p>"); r.setClosingHtml("<p>y</p>");
        r.setSignatureName(name); r.setSignatureTitle(title);
        return r;
    }

    @Test
    @DisplayName("default: church name and Finance Department, with no row at all")
    void defaultsWithoutRow() {
        Map<String, String> out = service.rendered(TENANT, "Test Subscription Church", 2026);
        assertThat(out).containsEntry("signatureName", "Test Subscription Church")
                       .containsEntry("signatureTitle", "Finance Department");
        assertThat(FinancialReportLetterService.defaults("Grace Chapel", 2026))
                .containsEntry("signatureName", "Grace Chapel").containsEntry("signatureTitle", "Finance Department");
    }

    @Test
    @DisplayName("an existing church with custom letter text but no signature still gets the defaults")
    void existingRowWithoutSignatureGetsDefaults() {
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(row(null, null)));
        Map<String, String> out = service.rendered(TENANT, "Grace Chapel", 2026);
        assertThat(out).containsEntry("signatureName", "Grace Chapel").containsEntry("signatureTitle", "Finance Department");
        assertThat(out).containsEntry("introHtml", "<p>x</p>");   // letter text untouched
    }

    @Test
    @DisplayName("customised: the stored name and title are printed")
    void customised() {
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(row("Rev. John Mathew", "Treasurer")));
        assertThat(service.rendered(TENANT, "Grace Chapel", 2026))
                .containsEntry("signatureName", "Rev. John Mathew").containsEntry("signatureTitle", "Treasurer");
    }

    @Test
    @DisplayName("markup in a signature prints literally — it is text, never HTML")
    void escaped() {
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(row("<b>Bold</b>", "<script>x</script>")));
        Map<String, String> out = service.rendered(TENANT, "Faith & Hope", 2026);
        assertThat(out.get("signatureName")).isEqualTo("&lt;b&gt;Bold&lt;/b&gt;");
        assertThat(out.get("signatureTitle")).isEqualTo("&lt;script&gt;x&lt;/script&gt;");
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.empty());
        assertThat(service.rendered(TENANT, "Faith & Hope", 2026).get("signatureName")).isEqualTo("Faith &amp; Hope");
    }

    @Test
    @DisplayName("saving: blank means default (stored as null), values are trimmed, over-long is refused")
    void saving() {
        ArgumentCaptor<FinancialReportLetter> saved = ArgumentCaptor.forClass(FinancialReportLetter.class);
        service.save(TENANT, "<p>a</p>", "<p>b</p>", "  Pastor Anson  ", "   ", "admin");
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSignatureName()).isEqualTo("Pastor Anson");
        assertThat(saved.getValue().getSignatureTitle()).isNull();

        assertThatThrownBy(() -> service.save(TENANT, "<p>a</p>", "<p>b</p>", "x".repeat(201), "t", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
    }

    @Test
    @DisplayName("the four-argument save still works and leaves the signature at its default")
    void legacySaveUnchanged() {
        ArgumentCaptor<FinancialReportLetter> saved = ArgumentCaptor.forClass(FinancialReportLetter.class);
        service.save(TENANT, "<p>a</p>", "<p>b</p>", "admin");
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSignatureName()).isNull();
        assertThat(saved.getValue().getSignatureTitle()).isNull();
    }

    @Test
    @DisplayName("the editor is given the stored values (blank = default) and the default title")
    void forEditing() {
        assertThat(service.forEditing(TENANT)).containsEntry("signatureName", "").containsEntry("signatureTitle", "")
                                              .containsEntry("defaultSignatureTitle", "Finance Department");
        when(repo.findFirstByAppClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(row("Rev. John", null)));
        assertThat(service.forEditing(TENANT)).containsEntry("signatureName", "Rev. John").containsEntry("signatureTitle", "");
    }

    @Test
    @DisplayName("both report pages print the configured signature and fall back to the old literals")
    void pagesUseIt() throws IOException {
        String tax = Files.readString(Paths.get("src/main/resources/static/tax-report.html"));
        assertThat(tax).contains("id=\"ltSigName\"", "id=\"ltSigTitle\"", "signatureName:  document.getElementById('ltSigName').value",
                "${data.signatureName || churchName}", "${data.signatureTitle || 'Finance Department'}");
        assertThat(tax).doesNotContain("<div class=\"ltr-sig-title\">Finance Department</div>");
        String member = Files.readString(Paths.get("src/main/resources/static/memberHome.html"));
        assertThat(member).contains("(data.signatureName || churchName)", "(data.signatureTitle || 'Finance Department')");
        assertThat(member).doesNotContain("<div class=\"ltr-sig-title\">Finance Department</div>");
    }
}

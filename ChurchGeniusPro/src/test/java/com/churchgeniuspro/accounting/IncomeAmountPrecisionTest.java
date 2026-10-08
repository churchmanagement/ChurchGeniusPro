package com.churchgeniuspro.accounting;

import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.service.IncomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit M1: {@code IncomeController} parsed the "amount" field with a
 * bare {@code new BigDecimal(val.toString())} — sub-cent input like {@code 12.345}
 * was silently accepted, and PostgreSQL's {@code numeric(15,2)} column rounded it
 * on save with nothing telling the caller. The controller now parses amounts
 * through {@link com.churchgeniuspro.util.MoneyAmounts}, which rejects a value
 * that rounding to 2 decimal places would change instead of rounding it away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomeAmountPrecisionTest {

    private static final String CLIENT = "CHR-1";

    @Mock IncomeService incomeService;

    private IncomeController controller;

    @BeforeEach
    void setUp() {
        controller = new IncomeController(incomeService);
    }

    private static MockHttpServletRequest staffRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "bookkeeper@example.org");
        session.setAttribute("role", "Accountant");
        session.setAttribute("appClientId", CLIENT);
        req.setSession(session);
        return req;
    }

    private static Map<String, Object> baseBody(Object amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subSourceId", 7);
        body.put("incomeDate", "2026-03-15");
        body.put("transactionTypeId", 2);
        body.put("amount", amount);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> resp) {
        return (Map<String, Object>) resp.getBody();
    }

    private void stubSavedIncome(Integer id) {
        Income saved = new Income();
        saved.setId(id);
        when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean())).thenReturn(saved);
    }

    @Test
    @DisplayName("create rejects a sub-cent amount with a precision-specific message, not the generic 'must be greater than zero'")
    void createRejectsSubCentAmount() {
        ResponseEntity<Map<String, Object>> resp = controller.createIncome(baseBody("12.345"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(resp).get("error").toString())
                .contains("12.345")
                .contains("2 decimal places");
        verifyNoInteractions(incomeService);
    }

    @Test
    @DisplayName("create rejects exponential near-zero notation rather than posting a phantom $0.00")
    void createRejectsExponentialNearZero() {
        ResponseEntity<Map<String, Object>> resp = controller.createIncome(baseBody("1e-7"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(incomeService);
    }

    @Test
    @DisplayName("create accepts whole-dollar exponential notation, normalized before it reaches the service")
    void createAcceptsAndNormalizesExponentialWholeDollar() {
        stubSavedIncome(99);

        ResponseEntity<Map<String, Object>> resp = controller.createIncome(baseBody("1e3"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(incomeService).createIncome(any(), any(), any(), any(), any(), amountCaptor.capture(), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("1000.00");
        assertThat(amountCaptor.getValue().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("create passes an ordinary amount through unaffected")
    void createOrdinaryAmountUnaffected() {
        stubSavedIncome(100);

        ResponseEntity<Map<String, Object>> resp = controller.createIncome(baseBody("42.50"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(incomeService).createIncome(any(), any(), any(), any(), any(), amountCaptor.capture(), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("42.50");
    }

    @Test
    @DisplayName("update rejects a sub-cent amount the same way create does")
    void updateRejectsSubCentAmount() {
        ResponseEntity<Map<String, Object>> resp = controller.updateIncome(55, baseBody("9.999"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(bodyOf(resp).get("error").toString()).contains("9.999");
        verifyNoInteractions(incomeService);
    }

    @Test
    @DisplayName("update passes a normalized whole-dollar amount through to the service")
    void updateAcceptsAndNormalizesWholeDollar() {
        Income saved = new Income();
        saved.setId(55);
        when(incomeService.updateIncome(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(saved);

        ResponseEntity<Map<String, Object>> resp = controller.updateIncome(55, baseBody("1e2"), staffRequest());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(incomeService).updateIncome(any(), any(), any(), any(), any(), any(), amountCaptor.capture(), any(),
                any(), anyBoolean(), any(), any());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("100.00");
    }
}

package com.churchgeniuspro.voice;

import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scenario 5, server side: an amount beyond the ledger column (numeric(15,2)) — such as the
 * 1.2345678910111214e+38 a runaway voice parse once produced — is refused with a clear
 * message instead of reaching the database and failing with an overflow.
 */
@DisplayName("Income / Expense — amounts above the ledger limit are rejected before the save")
class LedgerAmountBoundsTest {

    static MockHttpServletRequest staff() {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "bookkeeper@example.org"); s.setAttribute("role", "Accountant"); s.setAttribute("appClientId", "CHR-1");
        req.setSession(s); return req;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @Test @DisplayName("income: 1.2345678910111214e+38 and 10,000,000,000,000 → 400 'too large'; 9,999,999,999,999.99 is accepted")
    void income() {
        IncomeService svc = mock(IncomeService.class);
        com.churchgeniuspro.hibernate.Income saved = new com.churchgeniuspro.hibernate.Income(); saved.setId(1);
        when(svc.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(saved);
        IncomeController c = new IncomeController(svc);
        for (Object bad : new Object[] { "1.2345678910111214e+38", 1.2345678910111214e+38, "10000000000000" }) {
            Map<String, Object> b = new LinkedHashMap<>(); b.put("subSourceId", 3); b.put("incomeDate", "2026-10-21"); b.put("transactionTypeId", 2); b.put("amount", bad);
            ResponseEntity<Map<String, Object>> r = c.createIncome(b, staff());
            assertThat(r.getStatusCode().value()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(String.valueOf(body(r).get("error"))).contains("too large");
        }
        verifyNoInteractions(svc);
        Map<String, Object> ok = new LinkedHashMap<>(); ok.put("subSourceId", 3); ok.put("incomeDate", "2026-10-21"); ok.put("transactionTypeId", 2); ok.put("amount", "9999999999999.99");
        assertThat(c.createIncome(ok, staff()).getStatusCode().value()).isNotEqualTo(400);
    }

    @Test @DisplayName("expense: the same bound applies")
    void expense() {
        ExpenseService svc = mock(ExpenseService.class);
        ExpenseController c = new ExpenseController(svc);
        Map<String, Object> b = new LinkedHashMap<>(); b.put("purposeId", 3); b.put("mainSourceId", 4); b.put("expenseDate", "2026-10-21"); b.put("transactionTypeId", 2); b.put("amount", "12345678910111214");
        ResponseEntity<Map<String, Object>> r = c.create(b, staff());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(body(r).get("error"))).contains("too large");
        verifyNoInteractions(svc);
    }
}

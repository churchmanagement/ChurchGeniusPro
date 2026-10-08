package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.entity.PayrollRun;
import com.churchgeniuspro.payroll.model.PayrollRunStatus;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PayrollRunRepository;
import com.churchgeniuspro.payroll.service.PayrollService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Financial audit H3: re-running "process all" on a run that already has some
 * employees processed (a retry after a partial failure, or simply clicking it
 * twice) must skip the employees who are already done — not fail the whole
 * batch, and not log them as an "Unexpected error".
 *
 * <p>{@link PayrollAdminController#processAll} only routes {@link IllegalArgumentException}
 * into its skip-and-continue path; anything else falls into a generic
 * "Unexpected error". This locks in the wiring between
 * {@link com.churchgeniuspro.payroll.service.PayrollService#processEmployee}
 * throwing exactly that type for a duplicate paystub and this controller's
 * existing (unchanged) handling of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PayrollAdminController#processAll — duplicate paystubs are skipped, not errored (H3)")
class PayrollAdminControllerProcessAllDuplicateTest {

    private static final String CLIENT = "CHR-ours";
    private static final Long RUN_ID = 55L;

    @Mock PayrollService payrollService;
    @Mock PayrollRunRepository runRepo;
    @Mock PayrollEmployeeRepository employeeRepo;

    private MockHttpServletRequest req() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "admin");
        s.setAttribute("church", true);
        s.setAttribute("clientId", CLIENT);
        r.setSession(s);
        return r;
    }

    private PayrollEmployee emp(long id, String name) {
        PayrollEmployee e = new PayrollEmployee();
        e.setId(id);
        e.setAppClientId(CLIENT);
        e.setFirstName(name);
        e.setLastName("");
        e.setActive(true);
        return e;
    }

    @Test
    @DisplayName("an employee who already has a paystub in this run is skipped with a reason, not reported as \"Unexpected error\"")
    void alreadyProcessedEmployeeIsSkippedCleanly() {
        PayrollRun run = new PayrollRun();
        run.setId(RUN_ID);
        run.setAppClientId(CLIENT);
        run.setStatus(PayrollRunStatus.DRAFT);
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

        PayrollEmployee already = emp(1L, "Already-Done");
        PayrollEmployee fresh = emp(2L, "Fresh");
        when(employeeRepo.findByAppClientIdAndActiveTrue(CLIENT)).thenReturn(List.of(already, fresh));

        when(payrollService.processEmployee(eq(RUN_ID), eq(1L), any(), anyString()))
                .thenThrow(new IllegalArgumentException("Already-Done already has a paystub in this run."));
        Paystub freshStub = new Paystub();
        freshStub.setId(900L);
        when(payrollService.processEmployee(eq(RUN_ID), eq(2L), any(), anyString()))
                .thenReturn(freshStub);

        PayrollAdminController controller = new PayrollAdminController(payrollService, runRepo, employeeRepo);
        ResponseEntity<Map<String, Object>> res = controller.processAll(RUN_ID, req());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = res.getBody();
        assertThat(body.get("processed")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) body.get("skipped");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).get("employeeId")).isEqualTo(1L);
        assertThat(skipped.get(0).get("reason")).isEqualTo("Already-Done already has a paystub in this run.");
        assertThat(skipped.get(0).get("reason")).isNotEqualTo("Unexpected error");
    }
}

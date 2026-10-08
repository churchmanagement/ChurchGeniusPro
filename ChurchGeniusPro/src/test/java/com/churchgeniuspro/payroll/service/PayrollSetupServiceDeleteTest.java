package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.repository.DeductionDefinitionRepository;
import com.churchgeniuspro.payroll.repository.EmployeeDeductionRepository;
import com.churchgeniuspro.payroll.repository.PayrollAuditLogRepository;
import com.churchgeniuspro.payroll.repository.PayrollEmployeeRepository;
import com.churchgeniuspro.payroll.repository.PayrollW4Repository;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database audit W3 reconciliation for {@code payroll_employee}. After W3 adds the
 * RESTRICT foreign keys {@code payroll_paystub/​payroll_w4/​payroll_employee_deduction →
 * payroll_employee}, a hard delete of an employee who has issued paystubs would raise a
 * constraint error. That is the correct behaviour — paystubs are payroll/tax records — so
 * {@link PayrollSetupService#deleteEmployee} must refuse it at the application level and
 * point the admin at deactivation instead, and must remove the (non-record) W-4 and
 * deduction children before the employee so RESTRICT is satisfied on a clean delete.
 */
@ExtendWith(MockitoExtension.class)
class PayrollSetupServiceDeleteTest {

    @Mock PayrollEmployeeRepository employeeRepo;
    @Mock PayrollW4Repository w4Repo;
    @Mock DeductionDefinitionRepository definitionRepo;
    @Mock EmployeeDeductionRepository employeeDeductionRepo;
    @Mock PayrollAuditLogRepository auditRepo;
    @Mock PaystubRepository paystubRepo;
    @Mock SsnCrypto ssnCrypto;

    @InjectMocks PayrollSetupService service;

    private PayrollEmployee employee(String clientId, Long id) {
        PayrollEmployee e = new PayrollEmployee();
        e.setId(id);
        e.setAppClientId(clientId);
        e.setFirstName("Pat");
        e.setLastName("Lee");
        return e;
    }

    @Test
    @DisplayName("an employee with payroll history cannot be hard-deleted — deactivate instead")
    void refusesDeleteWhenPaystubsExist() {
        when(employeeRepo.findById(700L)).thenReturn(Optional.of(employee("C", 700L)));
        when(paystubRepo.existsByAppClientIdAndEmployeeId("C", 700L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteEmployee("C", 700L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payroll history")
                .hasMessageContaining("Deactivate");

        verify(employeeRepo, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("with no payroll history, W-4 and deduction children are removed before the employee")
    void deletesChildrenBeforeEmployeeWhenClean() {
        PayrollEmployee e = employee("C", 701L);
        when(employeeRepo.findById(701L)).thenReturn(Optional.of(e));
        when(paystubRepo.existsByAppClientIdAndEmployeeId("C", 701L)).thenReturn(false);
        when(w4Repo.findByEmployeeIdOrderByEffectiveDateDesc(701L)).thenReturn(List.of());
        when(employeeDeductionRepo.findByEmployeeId(701L)).thenReturn(List.of());

        service.deleteEmployee("C", 701L, "admin");

        // The employee is removed only after its child lookups/removals have run.
        var order = inOrder(w4Repo, employeeDeductionRepo, employeeRepo);
        order.verify(w4Repo).findByEmployeeIdOrderByEffectiveDateDesc(701L);
        order.verify(employeeDeductionRepo).findByEmployeeId(701L);
        order.verify(employeeRepo).delete(e);
        verify(auditRepo).save(org.mockito.ArgumentMatchers.any());
    }
}

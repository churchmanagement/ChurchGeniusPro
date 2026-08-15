package com.churchgeniuspro.payroll.service;

import com.churchgeniuspro.payroll.controller.dto.AssignDeductionRequest;
import com.churchgeniuspro.payroll.controller.dto.DeductionDefinitionRequest;
import com.churchgeniuspro.payroll.controller.dto.EmployeeRequest;
import com.churchgeniuspro.payroll.controller.dto.W4Request;
import com.churchgeniuspro.payroll.entity.*;
import com.churchgeniuspro.payroll.model.DeductionScope;
import com.churchgeniuspro.payroll.model.FilingStatus;
import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayType;
import com.churchgeniuspro.payroll.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Employee-setup operations: employees, their Form W-4, the tenant's deduction
 * catalog, and per-employee deduction assignments. Every operation is enforced
 * to the caller's tenant ({@code appClientId}) and writes a {@link PayrollAuditLog}
 * row. Updates are partial (only non-null fields are applied), so callers can
 * PATCH a single field without wiping the rest.
 *
 * <p>Throws {@link IllegalArgumentException} for not-found / cross-tenant access
 * and invalid enum values; the controller maps these to 404/400.
 */
@Service
public class PayrollSetupService {

    private final PayrollEmployeeRepository employeeRepo;
    private final PayrollW4Repository w4Repo;
    private final DeductionDefinitionRepository definitionRepo;
    private final EmployeeDeductionRepository employeeDeductionRepo;
    private final PayrollAuditLogRepository auditRepo;
    private final PaystubRepository paystubRepo;
    private final SsnCrypto ssnCrypto;

    public PayrollSetupService(PayrollEmployeeRepository employeeRepo,
                               PayrollW4Repository w4Repo,
                               DeductionDefinitionRepository definitionRepo,
                               EmployeeDeductionRepository employeeDeductionRepo,
                               PayrollAuditLogRepository auditRepo,
                               PaystubRepository paystubRepo,
                               SsnCrypto ssnCrypto) {
        this.employeeRepo = employeeRepo;
        this.w4Repo = w4Repo;
        this.definitionRepo = definitionRepo;
        this.employeeDeductionRepo = employeeDeductionRepo;
        this.auditRepo = auditRepo;
        this.paystubRepo = paystubRepo;
        this.ssnCrypto = ssnCrypto;
    }

    // ── Employees ────────────────────────────────────────────────────────────

    public List<PayrollEmployee> listEmployees(String clientId) {
        return employeeRepo.findByAppClientId(clientId);
    }

    public PayrollEmployee getEmployee(String clientId, Long id) {
        return requireEmployee(clientId, id);
    }

    @Transactional
    public PayrollEmployee createEmployee(String clientId, EmployeeRequest req, String actor) {
        if (isBlank(req.getFirstName()) || isBlank(req.getLastName())) {
            throw new IllegalArgumentException("First and last name are required.");
        }
        PayrollEmployee e = new PayrollEmployee();
        e.setAppClientId(clientId);
        e.setActive(req.getActive() == null || req.getActive());
        applyEmployee(e, req);
        e = employeeRepo.save(e);
        audit(clientId, "PayrollEmployee", e.getId(), "CREATE", actor, "Created employee " + e.fullName());
        return e;
    }

    @Transactional
    public PayrollEmployee updateEmployee(String clientId, Long id, EmployeeRequest req, String actor) {
        PayrollEmployee e = requireEmployee(clientId, id);
        applyEmployee(e, req);
        if (req.getActive() != null) e.setActive(req.getActive());
        e = employeeRepo.save(e);
        audit(clientId, "PayrollEmployee", id, "UPDATE", actor, "Updated employee " + e.fullName());
        return e;
    }

    @Transactional
    public void deactivateEmployee(String clientId, Long id, String actor) {
        PayrollEmployee e = requireEmployee(clientId, id);
        e.setActive(false);
        employeeRepo.save(e);
        audit(clientId, "PayrollEmployee", id, "DEACTIVATE", actor, "Deactivated employee " + e.fullName());
    }

    /** Re-activate a previously deactivated/terminated employee so they can be
     *  included in new payroll runs again. Clears any termination date. */
    @Transactional
    public void activateEmployee(String clientId, Long id, String actor) {
        PayrollEmployee e = requireEmployee(clientId, id);
        e.setActive(true);
        e.setTerminationDate(null);
        employeeRepo.save(e);
        audit(clientId, "PayrollEmployee", id, "ACTIVATE", actor, "Re-activated employee " + e.fullName());
    }

    /** Hard-delete an employee. Blocked when payroll history (paystubs) exists —
     *  those employees must be deactivated instead so reports stay intact. */
    @Transactional
    public void deleteEmployee(String clientId, Long id, String actor) {
        PayrollEmployee e = requireEmployee(clientId, id);
        if (paystubRepo.existsByAppClientIdAndEmployeeId(clientId, id)) {
            throw new IllegalArgumentException(
                "This employee has payroll history and cannot be deleted. Deactivate the employee instead to preserve reports.");
        }
        // No payroll history → safe to remove. Clean up dependent W-4 + deduction rows.
        w4Repo.findByEmployeeIdOrderByEffectiveDateDesc(id).forEach(w4Repo::delete);
        employeeDeductionRepo.findByEmployeeId(id).forEach(employeeDeductionRepo::delete);
        String name = e.fullName();
        employeeRepo.delete(e);
        audit(clientId, "PayrollEmployee", id, "DELETE", actor, "Deleted employee " + name);
    }

    /** Apply only the non-null fields of the request onto the entity (partial update). */
    private void applyEmployee(PayrollEmployee e, EmployeeRequest r) {
        if (r.getEmployeeNumber() != null) e.setEmployeeNumber(r.getEmployeeNumber());
        if (r.getFirstName() != null) e.setFirstName(r.getFirstName());
        if (r.getLastName() != null) e.setLastName(r.getLastName());
        if (r.getEmail() != null) e.setEmail(r.getEmail());
        if (r.getPhone() != null) e.setPhone(r.getPhone());
        if (r.getSsnLast4() != null) {
            // SSN last-4 is sensitive: validate exactly 4 digits, then encrypt
            // (AES-GCM) before it ever reaches the database. Blank clears it.
            String raw = r.getSsnLast4().trim();
            if (raw.isEmpty()) {
                e.setSsnLast4(null);
            } else {
                if (!raw.matches("\\d{4}")) {
                    throw new IllegalArgumentException("SSN (last 4) must be exactly 4 digits.");
                }
                e.setSsnLast4(ssnCrypto.encrypt(raw));
            }
        }
        if (r.getAddressLine1() != null) e.setAddressLine1(r.getAddressLine1());
        if (r.getAddressLine2() != null) e.setAddressLine2(r.getAddressLine2());
        if (r.getCity() != null) e.setCity(r.getCity());
        if (r.getState() != null) e.setState(r.getState());
        if (r.getPostalCode() != null) e.setPostalCode(r.getPostalCode());
        if (r.getHireDate() != null) e.setHireDate(r.getHireDate());
        if (r.getTerminationDate() != null) e.setTerminationDate(r.getTerminationDate());
        if (r.getPayType() != null) e.setPayType(parsePayType(r.getPayType()));
        if (r.getHourlyRate() != null) e.setHourlyRate(r.getHourlyRate());
        if (r.getAnnualSalary() != null) e.setAnnualSalary(r.getAnnualSalary());
        if (r.getPayFrequency() != null) e.setPayFrequency(parseFrequency(r.getPayFrequency()));
        if (r.getPayPeriodsPerYearOverride() != null) e.setPayPeriodsPerYearOverride(r.getPayPeriodsPerYearOverride());
        if (r.getStateTaxCode() != null) e.setStateTaxCode(blankToNull(r.getStateTaxCode()));
        if (r.getBankName() != null) e.setBankName(r.getBankName());
        if (r.getDirectDepositAccountLast4() != null) e.setDirectDepositAccountLast4(last4(r.getDirectDepositAccountLast4()));
        if (r.getDirectDepositRoutingLast4() != null) e.setDirectDepositRoutingLast4(last4(r.getDirectDepositRoutingLast4()));
        if (r.getDirectDepositAccountType() != null) e.setDirectDepositAccountType(r.getDirectDepositAccountType());
    }

    // ── W-4 ──────────────────────────────────────────────────────────────────

    public Optional<PayrollW4> currentW4(String clientId, Long employeeId) {
        requireEmployee(clientId, employeeId);
        return w4Repo.findByEmployeeIdAndActiveTrue(employeeId);
    }

    /** Replace the employee's active W-4 (the prior one is kept but deactivated, preserving history). */
    @Transactional
    public PayrollW4 setW4(String clientId, Long employeeId, W4Request req, String actor) {
        requireEmployee(clientId, employeeId);
        w4Repo.findByEmployeeIdAndActiveTrue(employeeId).ifPresent(prev -> {
            prev.setActive(false);
            w4Repo.save(prev);
        });
        PayrollW4 w4 = new PayrollW4();
        w4.setAppClientId(clientId);
        w4.setEmployeeId(employeeId);
        w4.setFilingStatus(req.getFilingStatus() != null
                ? parseFilingStatus(req.getFilingStatus()) : FilingStatus.SINGLE);
        w4.setStep2Checked(req.isStep2Checked());
        if (req.getStep3AnnualCredits() != null) w4.setStep3AnnualCredits(req.getStep3AnnualCredits());
        if (req.getStep4aOtherIncome() != null) w4.setStep4aOtherIncome(req.getStep4aOtherIncome());
        if (req.getStep4bDeductions() != null) w4.setStep4bDeductions(req.getStep4bDeductions());
        if (req.getStep4cExtraPerPeriod() != null) w4.setStep4cExtraPerPeriod(req.getStep4cExtraPerPeriod());
        w4.setStateAllowances(req.getStateAllowances());
        if (req.getStateExtraPerPeriod() != null) w4.setStateExtraPerPeriod(req.getStateExtraPerPeriod());
        w4.setEffectiveDate(req.getEffectiveDate() != null ? req.getEffectiveDate() : LocalDate.now());
        w4.setActive(true);
        w4 = w4Repo.save(w4);
        audit(clientId, "PayrollW4", w4.getId(), "SET", actor, "Set W-4 for employee " + employeeId);
        return w4;
    }

    // ── Deduction definitions (tenant catalog) ───────────────────────────────

    public List<DeductionDefinition> listDefinitions(String clientId) {
        return definitionRepo.findByAppClientIdAndActiveTrue(clientId);
    }

    @Transactional
    public DeductionDefinition createDefinition(String clientId, DeductionDefinitionRequest req, String actor) {
        if (isBlank(req.getName())) throw new IllegalArgumentException("Deduction name is required.");
        DeductionDefinition d = new DeductionDefinition();
        d.setAppClientId(clientId);
        d.setActive(true);
        applyDefinition(d, req);
        d = definitionRepo.save(d);
        audit(clientId, "DeductionDefinition", d.getId(), "CREATE", actor, "Created deduction " + d.getName());
        return d;
    }

    @Transactional
    public DeductionDefinition updateDefinition(String clientId, Long id, DeductionDefinitionRequest req, String actor) {
        DeductionDefinition d = requireDefinition(clientId, id);
        applyDefinition(d, req);
        d = definitionRepo.save(d);
        audit(clientId, "DeductionDefinition", id, "UPDATE", actor, "Updated deduction " + d.getName());
        return d;
    }

    @Transactional
    public void deactivateDefinition(String clientId, Long id, String actor) {
        DeductionDefinition d = requireDefinition(clientId, id);
        d.setActive(false);
        definitionRepo.save(d);
        audit(clientId, "DeductionDefinition", id, "DEACTIVATE", actor, "Deactivated deduction " + d.getName());
    }

    private void applyDefinition(DeductionDefinition d, DeductionDefinitionRequest r) {
        if (r.getName() != null) d.setName(r.getName());
        if (r.getCode() != null) d.setCode(r.getCode());
        if (r.getScope() != null) d.setScope(parseScope(r.getScope()));
        d.setReducesFederalTaxable(r.isReducesFederalTaxable());
        d.setReducesStateTaxable(r.isReducesStateTaxable());
        d.setReducesFicaWages(r.isReducesFicaWages());
        d.setPercentageBased(r.isPercentageBased());
        if (r.getDefaultAmount() != null) d.setDefaultAmount(r.getDefaultAmount());
    }

    // ── Employee deduction assignments ───────────────────────────────────────

    public List<EmployeeDeduction> listAssignments(String clientId, Long employeeId) {
        requireEmployee(clientId, employeeId);
        return employeeDeductionRepo.findByEmployeeIdAndActiveTrue(employeeId);
    }

    @Transactional
    public EmployeeDeduction assignDeduction(String clientId, Long employeeId,
                                             AssignDeductionRequest req, String actor) {
        requireEmployee(clientId, employeeId);
        if (req.getDefinitionId() == null) throw new IllegalArgumentException("definitionId is required.");
        requireDefinition(clientId, req.getDefinitionId()); // tenant-check the definition
        EmployeeDeduction ed = new EmployeeDeduction();
        ed.setAppClientId(clientId);
        ed.setEmployeeId(employeeId);
        ed.setDefinitionId(req.getDefinitionId());
        ed.setAmountOrRate(req.getAmountOrRate());
        ed.setAnnualLimit(req.getAnnualLimit());
        ed.setActive(true);
        ed = employeeDeductionRepo.save(ed);
        audit(clientId, "EmployeeDeduction", ed.getId(), "ASSIGN", actor,
                "Assigned deduction " + req.getDefinitionId() + " to employee " + employeeId);
        return ed;
    }

    @Transactional
    public void removeAssignment(String clientId, Long employeeId, Long assignmentId, String actor) {
        EmployeeDeduction ed = employeeDeductionRepo.findById(assignmentId)
                .filter(x -> clientId.equals(x.getAppClientId()) && employeeId.equals(x.getEmployeeId()))
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        ed.setActive(false);
        employeeDeductionRepo.save(ed);
        audit(clientId, "EmployeeDeduction", assignmentId, "REMOVE", actor,
                "Removed deduction assignment " + assignmentId);
    }

    // ── Tenant-scoped loaders ────────────────────────────────────────────────

    private PayrollEmployee requireEmployee(String clientId, Long id) {
        return employeeRepo.findById(id)
                .filter(e -> clientId.equals(e.getAppClientId()))
                .orElseThrow(() -> new IllegalArgumentException("Employee not found."));
    }

    private DeductionDefinition requireDefinition(String clientId, Long id) {
        return definitionRepo.findById(id)
                .filter(d -> clientId.equals(d.getAppClientId()))
                .orElseThrow(() -> new IllegalArgumentException("Deduction definition not found."));
    }

    // ── Parsing / helpers ────────────────────────────────────────────────────

    private static PayType parsePayType(String s) {
        try { return PayType.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid payType: " + s + " (HOURLY or SALARY)."); }
    }

    private static PayFrequency parseFrequency(String s) {
        try { return PayFrequency.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid payFrequency: " + s + "."); }
    }

    private static FilingStatus parseFilingStatus(String s) {
        try { return FilingStatus.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid filingStatus: " + s + "."); }
    }

    private static DeductionScope parseScope(String s) {
        try { return DeductionScope.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid scope: " + s + " (PRE_TAX or POST_TAX)."); }
    }

    private static String last4(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    private void audit(String clientId, String entityType, Long entityId, String action, String actor, String details) {
        PayrollAuditLog row = new PayrollAuditLog();
        row.setAppClientId(clientId);
        row.setEntityType(entityType);
        row.setEntityId(entityId);
        row.setAction(action);
        row.setActor(actor);
        row.setDetails(details);
        auditRepo.save(row);
    }
}

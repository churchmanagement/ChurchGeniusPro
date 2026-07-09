package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.entity.Paystub;
import com.churchgeniuspro.payroll.pdf.EmployerInfo;
import com.churchgeniuspro.payroll.pdf.PaystubPdfService;
import com.churchgeniuspro.payroll.repository.PaystubRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Downloads a paystub as a PDF. Access is scoped to the logged-in tenant: the
 * stub's {@code app_client_id} must match the session's {@code clientId} so one
 * church can never fetch another church's paystubs.
 *
 * <p>NOTE: This reuses the app's existing session attributes ({@code username},
 * {@code clientId}, {@code churchName}). Tighten with the project's role/privilege
 * checks (e.g., a "Payroll" privilege) before production — payroll data is
 * sensitive. Staff (non-church) logins that don't carry a tenant {@code clientId}
 * in session are denied here; resolve their tenant via AppUser if such access is
 * required.
 */
@RestController
public class PayrollPaystubController {

    private final PaystubRepository paystubRepo;
    private final PaystubPdfService pdfService;

    public PayrollPaystubController(PaystubRepository paystubRepo, PaystubPdfService pdfService) {
        this.paystubRepo = paystubRepo;
        this.pdfService = pdfService;
    }

    @GetMapping("/api/payroll/paystubs/{id}/pdf")
    public ResponseEntity<byte[]> downloadPaystub(@PathVariable Long id,
                                                  @RequestParam(value = "download", defaultValue = "false") boolean download,
                                                  HttpServletRequest request) {
        PayrollAuth.Result auth = PayrollAuth.authorize(request);
        if (!auth.ok) return ResponseEntity.status(auth.status).build();
        String tenantClientId = auth.clientId;
        HttpSession session = request.getSession(false);

        Optional<Paystub> stubOpt = paystubRepo.findById(id);
        if (stubOpt.isEmpty()) return ResponseEntity.notFound().build();
        Paystub stub = stubOpt.get();
        // Tenant isolation: never serve a stub from another church.
        if (!tenantClientId.equals(stub.getAppClientId())) {
            return ResponseEntity.status(403).build();
        }

        String churchName = (String) session.getAttribute("churchName");
        EmployerInfo employer = EmployerInfo.ofName(churchName != null ? churchName : "Employer");

        byte[] pdf = pdfService.generate(id, employer);

        String filename = "paystub-" + id
                + (stub.getPayDate() != null ? "-" + stub.getPayDate() : "") + ".pdf";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                (download ? org.springframework.http.ContentDisposition.attachment()
                          : org.springframework.http.ContentDisposition.inline())
                        .filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(pdf);
    }
}

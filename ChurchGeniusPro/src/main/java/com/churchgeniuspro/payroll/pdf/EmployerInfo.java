package com.churchgeniuspro.payroll.pdf;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Employer (church) details printed on a paystub header. Kept as a small DTO so
 * the PDF service stays decoupled from how the tenant's name/address are sourced
 * (session, ServiceClient, ChurchRegistration, etc.).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmployerInfo {
    private String name;
    private String addressLine1;
    private String addressLine2;
    private String cityStateZip;
    private String ein;            // optional employer identification number

    public static EmployerInfo ofName(String name) {
        EmployerInfo e = new EmployerInfo();
        e.name = name;
        return e;
    }
}

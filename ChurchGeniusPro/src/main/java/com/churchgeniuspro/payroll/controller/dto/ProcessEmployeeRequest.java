package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body to process one employee within a run. {@code earnings} may be empty
 * for a salaried employee (the salary slice is derived automatically); supply
 * hourly lines for an hourly employee.
 */
@Data
public class ProcessEmployeeRequest {
    private List<EarningLineRequest> earnings = new ArrayList<>();
}

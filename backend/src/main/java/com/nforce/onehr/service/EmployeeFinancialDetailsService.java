package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.EmployeeFinancialDetailsResponse;
import com.nforce.onehr.dto.payroll.UpdateEmployeeFinancialDetailsRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Keka restyle (payroll module) — "Import Financial Information" (§3.4): a Payroll Admin editing
 * another employee's bank/PAN/UAN/ESI details. There was no admin write path for these fields at
 * all before this — only {@link ProfileService#update}, which is self-service only (resolved from
 * the caller's own JWT, never a path employeeId). Audited (unlike the similarly small
 * PayGroupFilingDetailsService/PayslipSettingsService): this is someone other than the record's
 * owner changing sensitive bank/statutory data, which is exactly the kind of change an audit
 * trail exists for.
 */
@Service
@RequiredArgsConstructor
public class EmployeeFinancialDetailsService {

    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional(readOnly = true)
    public EmployeeFinancialDetailsResponse get(UUID employeeId) {
        Employee employee = requireEmployee(employeeId);
        return toResponse(employee);
    }

    @Transactional
    public EmployeeFinancialDetailsResponse update(UUID employeeId, UpdateEmployeeFinancialDetailsRequest req, String actorEmail) {
        Employee employee = requireEmployee(employeeId);
        String before = auditSnapshot.toJson(snapshot(employee));

        if (req.getBankAccountNumber() != null) employee.setBankAccountNumber(req.getBankAccountNumber().trim());
        if (req.getBankName() != null) employee.setBankName(req.getBankName().trim());
        if (req.getBankIfsc() != null) employee.setBankIfsc(req.getBankIfsc().trim());
        if (req.getPanNumber() != null) employee.setPanNumber(req.getPanNumber().trim().toUpperCase());
        if (req.getUanNumber() != null) employee.setUanNumber(req.getUanNumber().trim());
        if (req.getEsiNumber() != null) employee.setEsiNumber(req.getEsiNumber().trim());
        employeeRepository.save(employee);

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        String after = auditSnapshot.toJson(snapshot(employee));
        // targetId is the employee's own id directly — AuditTargetResolver's default fallback
        // (no LEAVE_REQUEST_/EXPENSE_/ATTENDANCE_ prefix match) treats it as exactly that.
        auditService.log(actor.getId(), "EMPLOYEE_FINANCIAL_DETAILS_UPDATED", employeeId, before, after);

        return toResponse(employee);
    }

    private Employee requireEmployee(UUID employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeId));
    }

    private Map<String, Object> snapshot(Employee e) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bankAccountNumber", e.getBankAccountNumber());
        snapshot.put("bankName", e.getBankName());
        snapshot.put("bankIfsc", e.getBankIfsc());
        snapshot.put("panNumber", e.getPanNumber());
        snapshot.put("uanNumber", e.getUanNumber());
        snapshot.put("esiNumber", e.getEsiNumber());
        return snapshot;
    }

    private EmployeeFinancialDetailsResponse toResponse(Employee e) {
        return EmployeeFinancialDetailsResponse.builder()
                .employeeId(e.getUserId())
                .employeeCode(e.getEmployeeCode())
                .employeeName(e.getFullName())
                .bankAccountNumber(e.getBankAccountNumber())
                .bankName(e.getBankName())
                .bankIfsc(e.getBankIfsc())
                .panNumber(e.getPanNumber())
                .uanNumber(e.getUanNumber())
                .esiNumber(e.getEsiNumber())
                .build();
    }
}

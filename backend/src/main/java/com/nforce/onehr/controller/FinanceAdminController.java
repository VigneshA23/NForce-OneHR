package com.nforce.onehr.controller;

import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * US-F15 / F16 / F17 / F18 — Super Admin finance. Every route needs finance:CONFIGURE:ALL (Super Admin only
 * by default, migration V275), so Employee, Manager and HR Admin get 403 unless that permission is granted
 * explicitly. Routes that take an employee id are the only way to reach another person's finance data.
 */
@RestController
@RequestMapping("/api/admin/finances")
@PreAuthorize("hasAuthority('finance:CONFIGURE:ALL')")
@RequiredArgsConstructor
public class FinanceAdminController {

    public record WindowBody(LocalDate opensOn, LocalDate closesOn, boolean monthly) {}
    public record DecisionBody(String decision, BigDecimal acceptedAmount, String comment) {}
    public record DueDateBody(LocalDate dueDate) {}
    public record Person(UUID id, String code, String name) {}

    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final MySalaryService salaryService;
    private final IncomeTaxService incomeTaxService;
    private final PayslipService payslipService;
    private final TaxDeclarationService declarations;
    private final PreviousIncomeService previousIncome;
    private final TaxFormsService forms;
    private final TaxConfigService config;
    private final MyTaxProfileService taxProfile;

    private UUID actor(Principal p) {
        return userRepository.findByEmail(p.getName()).orElseThrow(() -> new IllegalStateException("Actor not found")).getId();
    }

    private String emailOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NoSuchElementException("Employee not found")).getEmail();
    }

    private static int fy(Integer fy) { return MyTaxController.fyOrCurrent(fy); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    @GetMapping("/employees")
    public List<Person> employees(@RequestParam(defaultValue = "") String q) {
        String like = "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("SELECT user_id, employee_code, full_name FROM employees WHERE lower(full_name) LIKE ? OR lower(employee_code) LIKE ? ORDER BY full_name LIMIT 50",
                (rs, i) -> new Person(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), like, like);
    }

    // ── view any employee (F15) ──
    @GetMapping("/{uid}/salary-timeline")
    public MySalaryService.Timeline salary(@PathVariable UUID uid) { return salaryService.timeline(emailOf(uid)); }

    @GetMapping("/{uid}/income-tax")
    public IncomeTaxService.Computation incomeTax(@PathVariable UUID uid, @RequestParam(required = false) Integer fy) { return incomeTaxService.compute(uid, fy); }

    @GetMapping("/{uid}/payslips")
    public List<String> payslips(@PathVariable UUID uid) { return payslipService.listMyPeriods(emailOf(uid)); }

    @GetMapping("/{uid}/payslips/{period}/preview")
    public PayslipService.PayslipPreview payslip(@PathVariable UUID uid, @PathVariable String period) { return payslipService.getMyPreview(emailOf(uid), period); }

    @GetMapping("/{uid}/tax-regime")
    public MyTaxProfileService.RegimeView regime(@PathVariable UUID uid) { return taxProfile.regime(emailOf(uid)); }

    @PostMapping("/{uid}/regime-unlock")
    public void unlock(@PathVariable UUID uid, Principal p) { taxProfile.unlock(actor(p), uid); }

    // ── declarations on behalf (F10) ──
    @GetMapping("/{uid}/declarations")
    public TaxDeclarationService.Overview declarations(@PathVariable UUID uid, @RequestParam(required = false) Integer fy) { return declarations.overview(uid, fy(fy)); }

    @PostMapping("/{uid}/declarations")
    public TaxDeclarationService.Overview addDeclaration(@PathVariable UUID uid, @RequestParam(required = false) Integer fy, @RequestBody TaxDeclarationService.Req b, Principal p) {
        return declarations.add(uid, fy(fy), b, actor(p), true);
    }

    @PutMapping("/{uid}/declarations/{id}")
    public TaxDeclarationService.Overview updateDeclaration(@PathVariable UUID uid, @PathVariable UUID id, @RequestParam(required = false) Integer fy, @RequestBody TaxDeclarationService.Req b, Principal p) {
        return declarations.update(uid, fy(fy), id, b, actor(p), true);
    }

    @DeleteMapping("/{uid}/declarations/{id}")
    public TaxDeclarationService.Overview deleteDeclaration(@PathVariable UUID uid, @PathVariable UUID id, @RequestParam(required = false) Integer fy, Principal p) {
        return declarations.delete(uid, fy(fy), id, actor(p), true);
    }

    // ── proof queue (F11) ──
    @GetMapping("/proofs")
    public List<TaxDeclarationService.QueueRow> queue(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) String category, @RequestParam(required = false) String status) {
        return declarations.queue(employeeId, blankToNull(category), blankToNull(status));
    }

    @GetMapping("/proofs/files/{proofId}")
    public ResponseEntity<byte[]> proofFile(@PathVariable UUID proofId) {
        TaxDeclarationService.ProofFile f = declarations.proofFile(proofId, null);
        return MyTaxController.file(f.data(), f.fileName(), f.contentType());
    }

    @PostMapping("/proofs/{declarationId}/decision")
    public void decide(@PathVariable UUID declarationId, @RequestBody DecisionBody b, Principal p) {
        declarations.decide(actor(p), declarationId, b.decision(), b.acceptedAmount(), b.comment());
    }

    // ── previous income (F12) ──
    @GetMapping("/{uid}/previous-income")
    public PreviousIncomeService.Grid previous(@PathVariable UUID uid, @RequestParam(required = false) Integer fy) { return previousIncome.grid(uid, fy(fy)); }

    @PutMapping("/{uid}/previous-income")
    public PreviousIncomeService.Grid savePrevious(@PathVariable UUID uid, @RequestParam(required = false) Integer fy, @RequestBody List<PreviousIncomeService.RowInput> rows, Principal p) {
        return previousIncome.save(uid, fy(fy), rows, actor(p), true);
    }

    @PostMapping("/{uid}/previous-income/approve")
    public PreviousIncomeService.Grid approvePrevious(@PathVariable UUID uid, @RequestParam(required = false) Integer fy, Principal p) {
        return previousIncome.approve(uid, fy(fy), actor(p));
    }

    // ── forms (F13) ──
    @GetMapping("/{uid}/form130")
    public ResponseEntity<byte[]> form130(@PathVariable UUID uid, @RequestParam(required = false) Integer fy) {
        TaxFormsService.Pdf f = forms.form130(uid, fy(fy));
        return MyTaxController.file(f.data(), f.fileName(), "application/pdf");
    }

    @GetMapping("/{uid}/form124")
    public ResponseEntity<byte[]> form124(@PathVariable UUID uid, @RequestParam(required = false) Integer fy) {
        TaxFormsService.Pdf f = forms.form124(uid, fy(fy));
        return MyTaxController.file(f.data(), f.fileName(), "application/pdf");
    }

    @GetMapping("/form130/status")
    public TaxFormsService.Form130Status form130Status(@RequestParam(required = false) Integer fy) { return forms.status(fy(fy)); }

    @PostMapping("/form130/publish")
    public TaxFormsService.Form130Status publish(@RequestParam(required = false) Integer fy, Principal p) { return forms.publish(fy(fy), actor(p)); }

    // ── configuration (F17) ──
    @GetMapping("/windows")
    public List<TaxDeclarationService.WindowView> windows() { return List.of(declarations.window("DECLARATION"), declarations.window("PROOF")); }

    @PutMapping("/windows/{kind}")
    public void setWindow(@PathVariable String kind, @RequestBody WindowBody b, Principal p) {
        declarations.setWindow(actor(p), kind.toUpperCase(), b.opensOn(), b.closesOn(), b.monthly());
    }

    @PutMapping("/tax-regime-window")
    public void setRegimeWindow(@RequestBody WindowBody b, Principal p) { taxProfile.setWindow(p.getName(), b.opensOn(), b.closesOn()); }

    @GetMapping("/partners")
    public List<TaxConfigService.Partner> partners() { return config.allPartners(); }

    @PostMapping("/partners")
    public TaxConfigService.Partner addPartner(@RequestBody TaxConfigService.Partner b, Principal p) { return config.savePartner(actor(p), null, b); }

    @PutMapping("/partners/{id}")
    public TaxConfigService.Partner updatePartner(@PathVariable UUID id, @RequestBody TaxConfigService.Partner b, Principal p) { return config.savePartner(actor(p), id, b); }

    @DeleteMapping("/partners/{id}")
    public void deletePartner(@PathVariable UUID id, Principal p) { config.deletePartner(actor(p), id); }

    @PutMapping("/filing/{fy}/due-date")
    public void dueDate(@PathVariable int fy, @RequestBody DueDateBody b, Principal p) { config.setDueDate(actor(p), fy, b.dueDate()); }

    @GetMapping("/rules")
    public TaxConfigService.Rules rules(@RequestParam int fy, @RequestParam String regime) { return config.rules(fy, regime); }

    @PutMapping("/rules")
    public TaxConfigService.Rules saveRules(@RequestBody TaxConfigService.Rules b, Principal p) { return config.saveRules(actor(p), b); }

    // ── finance audit log (F18) ──
    @GetMapping("/audit-log")
    public List<TaxConfigService.AuditRow> auditLog(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) String action,
                                                    @RequestParam(defaultValue = "200") int limit) {
        return config.auditLog(employeeId, blankToNull(action), limit);
    }

    @GetMapping("/audit-log/actions")
    public Map<String, List<String>> auditActions() { return Map.of("actions", TaxConfigService.FINANCE_ACTIONS); }
}

package com.nforce.onehr.controller;

import com.nforce.onehr.dto.expense.*;
import com.nforce.onehr.service.ExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    // ── Categories ────────────────────────────────────────

    @GetMapping("/categories")
    public List<ExpenseCategoryResponse> listCategories() {
        return expenseService.listCategories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseCategoryResponse createCategory(@Valid @RequestBody CreateExpenseCategoryRequest req,
                                                   Principal principal) {
        return expenseService.createCategory(req, principal.getName());
    }

    @PutMapping("/categories/{id}")
    public ExpenseCategoryResponse updateCategory(@PathVariable Integer id,
                                                   @Valid @RequestBody CreateExpenseCategoryRequest req,
                                                   Principal principal) {
        return expenseService.updateCategory(id, req, principal.getName());
    }

    // ── Employee: submit and view own claims ──────────────

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseClaimResponse submit(@Valid @RequestBody SubmitExpenseClaimRequest req,
                                       Principal principal) {
        return expenseService.submit(req, principal.getName());
    }

    @GetMapping("/claims/mine")
    public List<ExpenseClaimResponse> myClaims(Principal principal) {
        return expenseService.myClaims(principal.getName());
    }

    // ── Manager stage ─────────────────────────────────────
    // Decisions are made HERE — not on Team Assets page.

    @GetMapping("/claims/pending-manager")
    public List<ExpenseClaimResponse> pendingForManager(Principal principal) {
        return expenseService.pendingForManager(principal.getName());
    }

    @GetMapping("/claims/team-all")
    public List<ExpenseClaimResponse> allTeamClaims(Principal principal) {
        return expenseService.allTeamClaims(principal.getName());
    }

    @PostMapping("/claims/{id}/manager-approve")
    public ExpenseClaimResponse managerApprove(@PathVariable UUID id, Principal principal) {
        return expenseService.managerApprove(id, principal.getName());
    }

    @PostMapping("/claims/{id}/manager-reject")
    public ExpenseClaimResponse managerReject(@PathVariable UUID id,
                                               @Valid @RequestBody RejectExpenseClaimRequest req,
                                               Principal principal) {
        return expenseService.managerReject(id, req.getReason(), principal.getName());
    }

    // ── Final stage (HR Admin / Super Admin) ─────────────
    // Decisions are made HERE — not on HR Assets & Expenses page (which only shows summary).

    @GetMapping("/claims/pending-final")
    public List<ExpenseClaimResponse> pendingForFinalApprover(Principal principal) {
        return expenseService.pendingForFinalApprover(principal.getName());
    }

    @PostMapping("/claims/{id}/final-approve")
    public ExpenseClaimResponse finalApprove(@PathVariable UUID id, Principal principal) {
        return expenseService.finalApprove(id, principal.getName());
    }

    @PostMapping("/claims/{id}/final-reject")
    public ExpenseClaimResponse finalReject(@PathVariable UUID id,
                                             @Valid @RequestBody RejectExpenseClaimRequest req,
                                             Principal principal) {
        return expenseService.finalReject(id, req.getReason(), principal.getName());
    }

    // ── HR Admin: payroll-ready claims ────────────────────

    @GetMapping("/claims/cleared-for-payroll")
    public List<ExpenseClaimResponse> clearedForPayroll(Principal principal) {
        return expenseService.clearedForPayroll(principal.getName());
    }

    @PostMapping("/claims/{id}/mark-paid")
    public ExpenseClaimResponse markPaid(@PathVariable UUID id, Principal principal) {
        return expenseService.markPaid(id, principal.getName());
    }

    // ── Tile summaries ────────────────────────────────────

    @GetMapping("/tiles/employee")
    public Map<String, Object> employeeTiles(Principal principal) {
        return expenseService.employeeTileSummary(principal.getName());
    }

    @GetMapping("/tiles/manager")
    public Map<String, Object> managerTiles(Principal principal) {
        return expenseService.managerTileSummary(principal.getName());
    }

    @GetMapping("/tiles/hr")
    public Map<String, Object> hrTiles(Principal principal) {
        return expenseService.hrTileSummary(principal.getName());
    }
}

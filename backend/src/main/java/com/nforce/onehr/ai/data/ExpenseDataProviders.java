package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.service.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The caller's own expense claims.
 *
 * <p>This is the provider that answers the question that prompted the whole feature — "I made a
 * claim and it hasn't been accepted, what happened?" — with the stage the claim is actually at and
 * who it is sitting with, rather than a description of how the workflow works in general.
 */
public final class ExpenseDataProviders {

    private ExpenseDataProviders() {}

    /** Claims raised by the caller, newest first, with the stage each one has reached. */
    @Component
    @RequiredArgsConstructor
    public static class MyClaims implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final ExpenseService expenseService;

        @Override public String id() { return "expense.my-claims"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your recent expense claims"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("assets", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<ExpenseClaimResponse> claims = expenseService.myClaims(context.getActorEmail());
            if (claims == null || claims.isEmpty()) return Optional.empty();

            return Optional.of(LiveDataText.cappedList(claims, MAX_ROWS, "expense claim(s) raised by you", "most recent",
                    MyClaims::describe));
        }

        /**
         * One claim as a line, through both approval stages.
         *
         * <p>Names each decision-maker, because "who is it with" is the real question behind "why
         * hasn't it been paid". A rejection reason is included since that is the whole answer when a
         * claim was refused - from whichever stage refused it; an HR-stage rejection used to lose its
         * reason here entirely - but it is free text, so it reaches the prompt fenced like any other
         * data. The receipt (a base64 data URI) is never read.
         */
        private static String describe(ExpenseClaimResponse claim) {
            StringBuilder line = new StringBuilder("%s, %s on %s: %s".formatted(
                    claim.getCategoryName(), claim.getAmount(), claim.getExpenseDate(), claim.getStatus()));

            if (claim.getManagerDecidedByName() != null) {
                line.append(" (manager stage: ").append(claim.getManagerDecidedByName()).append(')');
            }
            if (claim.getManagerRejectionReason() != null && !claim.getManagerRejectionReason().isBlank()) {
                line.append(" - manager's reason: ").append(claim.getManagerRejectionReason().trim());
            }
            if (claim.getFinalDecidedByName() != null) {
                line.append(" (final stage: ").append(claim.getFinalDecidedByName()).append(')');
            }
            if (claim.getFinalRejectionReason() != null && !claim.getFinalRejectionReason().isBlank()) {
                line.append(" - final approver's reason: ").append(claim.getFinalRejectionReason().trim());
            }
            if (claim.getPaidAt() != null) {
                line.append(" - paid on ").append(claim.getPaidAt().atZone(java.time.ZoneOffset.UTC).toLocalDate());
            }
            return line.toString();
        }
    }

    /** Claims waiting on the caller to approve. Manager and above only. */
    @Component
    @RequiredArgsConstructor
    public static class PendingForManager implements AssistantDataProvider {

        private static final int MAX_ROWS = 5;

        private final ExpenseService expenseService;

        @Override public String id() { return "expense.pending-approvals"; }
        @Override public DataScope scope() { return DataScope.APPROVALS; }
        @Override public String title() { return "Expense claims waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("assets", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            // The same branch the Approval Center takes (ApprovalCenterService): an HR Admin or Super
            // Admin decides claims at both stages, organisation-wide, while a Manager sees only their
            // reports' SUBMITTED claims. Reading pendingForManager for everyone under-reported the
            // admin queue - it missed every claim already past the manager stage.
            boolean finalApprover = context.getAudiences().contains(AudienceBucket.HR)
                    || context.getAudiences().contains(AudienceBucket.ADMIN);
            List<ExpenseClaimResponse> pending = finalApprover
                    ? expenseService.pendingForFinalApprover(context.getActorEmail())
                    : expenseService.pendingForManager(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String header = "%d awaiting your decision, totalling %s.".formatted(pending.size(),
                    pending.stream().map(ExpenseClaimResponse::getAmount).filter(java.util.Objects::nonNull)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
            if (finalApprover) {
                long managerStage = pending.stream().filter(c -> "SUBMITTED".equals(c.getStatus())).count();
                header += " %d still at the manager stage (SUBMITTED), %d approved by a manager and awaiting final clearance (MANAGER_APPROVED)."
                        .formatted(managerStage, pending.size() - managerStage);
            }
            return Optional.of(header + "\n" + LiveDataText.cappedList(pending, MAX_ROWS, "claim(s)", "first",
                    c -> "%s: %s, %s on %s (%s)".formatted(c.getEmployeeName(), c.getCategoryName(), c.getAmount(),
                            c.getExpenseDate(), c.getStatus())));
        }
    }
}

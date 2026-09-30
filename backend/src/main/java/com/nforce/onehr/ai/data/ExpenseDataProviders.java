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
import java.util.stream.Collectors;

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
        @Override public String title() { return "Your recent expense claims"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("assets", "requests"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<ExpenseClaimResponse> claims = expenseService.myClaims(context.getActorEmail());
            if (claims == null || claims.isEmpty()) return Optional.empty();

            return Optional.of(claims.stream()
                    .limit(MAX_ROWS)
                    .map(MyClaims::describe)
                    .collect(Collectors.joining("\n")));
        }

        /**
         * One claim as a line.
         *
         * <p>Names the decision-maker where there is one, because "who is it with" is the real
         * question behind "why hasn't it been paid". A rejection reason is included since that is
         * the whole answer when a claim was refused, and the employee wrote it about their own
         * claim — but it is free text, so it reaches the prompt fenced like any other data.
         */
        private static String describe(ExpenseClaimResponse claim) {
            StringBuilder line = new StringBuilder("- %s, %s on %s: %s".formatted(
                    claim.getCategoryName(), claim.getAmount(), claim.getExpenseDate(), claim.getStatus()));

            if (claim.getManagerDecidedByName() != null) {
                line.append(" (manager: ").append(claim.getManagerDecidedByName()).append(')');
            }
            if (claim.getManagerRejectionReason() != null && !claim.getManagerRejectionReason().isBlank()) {
                line.append(" - reason given: ").append(claim.getManagerRejectionReason().trim());
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
        @Override public String title() { return "Expense claims waiting for your decision"; }

        @Override
        public Set<AudienceBucket> audiences() {
            return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        }

        @Override public Set<String> modules() { return Set.of("assets", "approvals"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<ExpenseClaimResponse> pending = expenseService.pendingForManager(context.getActorEmail());
            if (pending == null || pending.isEmpty()) return Optional.empty();

            String rows = pending.stream()
                    .limit(MAX_ROWS)
                    .map(c -> "- %s: %s, %s on %s".formatted(
                            c.getEmployeeName(), c.getCategoryName(), c.getAmount(), c.getExpenseDate()))
                    .collect(Collectors.joining("\n"));

            return Optional.of("%d awaiting your decision.\n%s".formatted(pending.size(), rows));
        }
    }
}

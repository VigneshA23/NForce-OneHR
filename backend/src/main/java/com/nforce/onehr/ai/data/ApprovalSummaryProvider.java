package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.asset.AssetRequestResponse;
import com.nforce.onehr.service.AssetService;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.ExpenseService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.OvertimeRequestService;
import com.nforce.onehr.service.RegularizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A precise total across every Approval Center queue, in one line per type.
 *
 * <p>This is a deliberate, narrow exception to {@link AssistantDataProvider}'s "one provider, one
 * service method" rule. The Approval Center aggregates six independently-approved request types
 * (Leave, Regularization, Expense, Asset Request, Work From Home/Partial Day, Overtime), each with
 * its own detail provider elsewhere in this package. A question like "how many things need my
 * approval" is not really about any one of those types — it is about the total, and the general
 * relevance-ranked provider selection in {@link AssistantDataService} can surface at most a handful
 * of the six per turn (by design - see its own Javadoc on {@code MAX_PROVIDERS_PER_TURN}), so no
 * combination of detail providers alone can ever guarantee a complete count. Without this provider,
 * the assistant either had to under-report a partial figure as if it were the whole one - which is
 * how this class of question broke before this provider existed, reporting Leave's count as the
 * total while Expense, Regularization and everything else went unmentioned - or refuse to answer at
 * all.
 *
 * <p>The trade against "one provider, one method" is deliberately narrow: this provider returns
 * <strong>counts only</strong>, never a row of somebody's request detail, so it does not duplicate
 * what the six detail providers already expose and does not spend the extra PII budget an additional
 * detail provider would. It counts what the caller could already see themselves by opening the
 * Approval Center this page's own knowledge points them to - the same six {@code
 * listPendingForApprover(actorEmail)}-shaped methods the detail providers call, none of them mutating
 * and none of them taking anyone other than the actor.
 */
@Component
@RequiredArgsConstructor
public class ApprovalSummaryProvider implements AssistantDataProvider {

    private static final String STATUS_PENDING = "PENDING";

    private final LeaveService leaveService;
    private final ExpenseService expenseService;
    private final RegularizationService regularizationService;
    private final AttendanceRequestService attendanceRequestService;
    private final OvertimeRequestService overtimeRequestService;
    private final AssetService assetService;

    @Override public String id() { return "approvals.summary"; }
    @Override public String title() { return "Total pending approvals across every request type"; }

    @Override
    public Set<AudienceBucket> audiences() {
        return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
    }

    @Override public Set<String> modules() { return Set.of("approvals"); }

    @Override
    public Optional<String> fetch(AssistantRequestContext context) {
        String actorEmail = context.getActorEmail();

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Leave", size(leaveService.listPendingApprovals(actorEmail)));
        counts.put("Regularization", size(regularizationService.listPendingForApprover(actorEmail)));
        counts.put("Expense", size(expenseService.pendingForManager(actorEmail)));
        counts.put("Asset Request", pendingAssetCount(actorEmail));
        counts.put("Work From Home / Partial Day", size(attendanceRequestService.listPendingForApprover(actorEmail)));
        counts.put("Overtime", size(overtimeRequestService.listPendingForApprover(actorEmail)));

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return Optional.empty();

        String rows = counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> "- %s: %d".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));

        return Optional.of("%d total awaiting your decision, across all request types.\n%s".formatted(total, rows));
    }

    /** Matches {@code AssetDataProviders.PendingAssetRequests}: HR/Admin's queue also includes
     * already-{@code APPROVED} items awaiting fulfilment, which are not "awaiting your decision". */
    private int pendingAssetCount(String actorEmail) {
        List<AssetRequestResponse> pending = assetService.listPendingForApprover(actorEmail);
        if (pending == null) return 0;
        return (int) pending.stream().filter(r -> STATUS_PENDING.equals(r.getStatus())).count();
    }

    private int size(List<?> list) {
        return list == null ? 0 : list.size();
    }
}

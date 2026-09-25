package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.ApprovalItemDto;
import com.nforce.onehr.service.ApprovalCenterService;
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
 * <p>A question like "how many things need my approval" is about the total, and the general
 * relevance-ranked provider selection in {@link AssistantDataService} can surface only a handful of
 * the per-type detail providers in one turn (by design - see its own Javadoc on
 * {@code MAX_PROVIDERS_PER_TURN}), so no combination of them can guarantee a complete count.
 *
 * <p>Reads {@link ApprovalCenterService#pendingApprovals} - the exact method behind the Approval
 * Center screen and the Super Admin dashboard's pending-approvals donut - so the figure here cannot
 * disagree with what the caller sees there. It previously re-assembled the six queues itself and had
 * drifted from the screen twice over: HR Admins and Super Admins had their expense claims counted
 * at the manager stage only (the screen counts both stages org-wide), and document reviews were not
 * counted at all.
 *
 * <p>Counts only, never a row of anybody's request detail - the per-type detail providers cover
 * that, and a total spends none of the extra personal-data budget another detail list would.
 */
@Component
@RequiredArgsConstructor
public class ApprovalSummaryProvider implements AssistantDataProvider {

    /** The Approval Center's own labels (DashboardPage.tsx APPROVAL_TYPE_LABELS), in its order. */
    private static final Map<String, String> TYPE_LABELS = labels();

    private final ApprovalCenterService approvalCenterService;

    @Override public String id() { return "approvals.summary"; }
    @Override public DataScope scope() { return DataScope.APPROVALS; }
    @Override public String title() { return "Total pending approvals across every request type"; }

    @Override
    public Set<AudienceBucket> audiences() {
        return Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
    }

    @Override public Set<String> modules() { return Set.of("approvals"); }

    @Override
    public Optional<String> fetch(AssistantRequestContext context) {
        List<ApprovalItemDto> pending = approvalCenterService.pendingApprovals(context.getActorEmail());
        // Stated, not omitted: "how many things need my approval" is a count question, and a missing
        // block reads as "not looked up", which is a different answer from "none".
        if (pending == null || pending.isEmpty()) {
            return Optional.of("0 items awaiting your decision in the Approval Center - it is empty right now.");
        }

        Map<String, Long> byType = pending.stream()
                .collect(Collectors.groupingBy(ApprovalItemDto::getRequestType, LinkedHashMap::new, Collectors.counting()));

        String rows = TYPE_LABELS.entrySet().stream()
                .filter(e -> byType.containsKey(e.getKey()))
                .map(e -> "- %s: %d".formatted(e.getValue(), byType.get(e.getKey())))
                .collect(Collectors.joining("\n"));
        // A type this list does not know yet still counts toward the total and is still shown.
        String unlabelled = byType.entrySet().stream()
                .filter(e -> !TYPE_LABELS.containsKey(e.getKey()))
                .map(e -> "- %s: %d".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));

        StringBuilder out = new StringBuilder("%d total awaiting your decision in the Approval Center, across all request types."
                .formatted(pending.size()));
        if (!rows.isEmpty()) out.append('\n').append(rows);
        if (!unlabelled.isEmpty()) out.append('\n').append(unlabelled);
        return Optional.of(out.toString());
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("LEAVE", "Leave");
        labels.put("REGULARIZATION", "Regularization");
        labels.put("EXPENSE", "Expense");
        labels.put("ASSET_REQUEST", "Asset Request");
        labels.put("WFH", "Work From Home");
        labels.put("PARTIAL_DAY", "Partial Day");
        labels.put("OVERTIME", "Overtime");
        labels.put("HELP_CONTENT", "Document Review (Help & Guidance content)");
        return labels;
    }
}

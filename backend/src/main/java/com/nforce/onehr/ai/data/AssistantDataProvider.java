package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;

import java.util.Optional;
import java.util.Set;

/**
 * One slice of live OneHR data the signed-in user is already permitted to see, made available to
 * the assistant.
 *
 * <p>This is what lets the assistant answer "how many leave days do I have left", "who in my team
 * is on leave today" or "how many active users are there" with a figure instead of directions to a
 * page. Each provider is deliberately the narrowest possible shape for its question.
 *
 * <p><strong>A provider returns exactly what the caller's own role can already see in the UI,
 * through the same code that shows it there</strong> - which is what {@link #scope()} is for, and
 * what {@code DataProviderSafetyTest} enforces against {@link #audiences()}:
 *
 * <ul>
 *   <li>{@link DataScope#SELF}, {@link DataScope#APPROVALS} and {@link DataScope#TEAM} call an
 *       existing actor-scoped service method and nothing else.
 *       {@code LeaveService.listMyBalances(actorEmail)}, {@code ExpenseService.myClaims(actorEmail)}
 *       and their siblings already resolve the caller and scope the result - a Manager sees their
 *       reports, an Employee sees only themselves - so these write no authorisation logic of their
 *       own.</li>
 *   <li>{@link DataScope#ORGANISATION} may reach an org-wide read, such as
 *       {@code LeaveService.listOrgLeave(from, to)}, because the service methods behind org-wide
 *       screens usually take no actor at all. That makes {@link #audiences()} the only gate, so an
 *       organisation provider's audiences must mirror the {@code @PreAuthorize} on the controller
 *       that serves the same data to the UI - never wider - and it should prefer counts and short,
 *       capped lists over row dumps.</li>
 * </ul>
 *
 * <p>Where a screen assembles its figures from several reads, reuse the service method the screen
 * itself uses rather than re-assembling them here - {@link ApprovalSummaryProvider} reads the
 * Approval Center's own queue method for exactly that reason, after a hand-kept copy of its role
 * branches drifted from what the screen showed.
 *
 * <p><strong>Reads only.</strong> A provider may hold a domain service that is capable of mutation
 * — {@code LeaveService} can approve leave — so the discipline is that a provider calls only read
 * methods and returns text. {@code DataProviderSafetyTest} asserts, against each provider's bytecode,
 * that none of them calls a mutating method name. A name is not proof, so before reusing a service
 * method check that it is genuinely a read: some list methods also write as they go
 * ({@code ExceptionService#getExceptionsForCaller} runs detection and can apply penalties;
 * {@code DocumentService#myDocuments} sends expiry reminders), and those must not be called here.
 *
 * <p><strong>Everything returned is untrusted.</strong> Leave reasons, expense purposes and
 * rejection comments are free text typed by employees, and they end up in a prompt. That makes this
 * the first place in the system where an ordinary user can put words in front of the model, so
 * output is fenced and labelled as data by {@code PromptBuilder}, exactly like retrieved knowledge.
 */
public interface AssistantDataProvider {

    /** Stable id, used in the interaction log and in tests. */
    String id();

    /** One line describing what this returns, for the prompt's own benefit. */
    String title();

    /**
     * Whose records this returns. No default on purpose: declaring it is the moment somebody has to
     * decide whether a new provider reads the caller's own data or other people's.
     */
    DataScope scope();

    /**
     * Which audience buckets may run this at all.
     *
     * <p>A second gate on top of the service's own scoping, not a replacement for it: "what is
     * waiting on me to approve" is meaningless for an Employee and should not run, even though
     * the underlying service would correctly return nothing.
     */
    Set<AudienceBucket> audiences();

    /**
     * Knowledge modules whose retrieval should trigger this provider.
     *
     * <p>Selection is driven by what retrieval already matched rather than by a second model call:
     * if the question pulled in leave knowledge, the leave figures are probably worth having. That
     * keeps provider selection free, deterministic and explainable, and means an unrelated question
     * never causes a database read or puts personal data in a prompt that did not need it.
     */
    Set<String> modules();

    /**
     * Fetches the data, already formatted as short plain-text lines.
     *
     * <p>Returns empty when there is nothing worth saying — no claims, no pending requests — so the
     * prompt is not padded with "none". Must never throw: a failure here degrades the answer to the
     * static-knowledge one, which is still useful.
     *
     * @param context the caller, resolved from the authenticated principal
     */
    Optional<String> fetch(AssistantRequestContext context);
}

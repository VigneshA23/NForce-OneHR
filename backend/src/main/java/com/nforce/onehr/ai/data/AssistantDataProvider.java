package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;

import java.util.Optional;
import java.util.Set;

/**
 * One slice of the signed-in user's own live OneHR data, made available to the assistant.
 *
 * <p>This is what lets the assistant answer "how many leave days do I have left" with a number
 * instead of directions to a page. It is deliberately the narrowest possible shape for that
 * capability.
 *
 * <p><strong>Every implementation must call an existing actor-scoped service method and nothing
 * else.</strong> {@code LeaveService.listMyBalances(actorEmail)},
 * {@code ExpenseService.myClaims(actorEmail)} and their siblings already resolve the caller and
 * scope the result — a Manager sees their reports, an Employee sees only themselves. Reusing those
 * methods means this feature writes no authorisation logic of its own, which is the single biggest
 * reason it is safe to build. A method that takes no actor, such as
 * {@code LeaveService.listOrgLeave(from, to)}, must never be reached from here.
 *
 * <p>{@link ApprovalSummaryProvider} is the one narrow, deliberate exception: it calls six such
 * methods, one per Approval Center request type, because a total across all of them cannot be
 * assembled any other way once {@code AssistantDataService}'s per-turn provider cap makes it
 * impossible for every type's own detail provider to run in the same turn. It still calls only
 * actor-scoped read methods, and returns counts only, never a row of anyone's request detail.
 *
 * <p><strong>Reads only.</strong> A provider may hold a domain service that is capable of mutation
 * — {@code LeaveService} can approve leave — so the discipline is that a provider calls exactly one
 * read method and returns text. {@code AssistantDataProviderTest} asserts that no provider's fetch
 * path touches a mutating method name.
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

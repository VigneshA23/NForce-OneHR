/**
 * Future AI-driven OneHR action execution: <strong>structurally present, operationally inert.</strong>
 *
 * <p>This package exists so a later release can add mutating actions without redesigning the
 * assistant core. In this release nothing in it can change a single row of OneHR data. That is not
 * enforced by a feature flag; it is enforced by what does and does not exist here:
 *
 * <ol>
 *   <li>{@link com.nforce.onehr.ai.action.ActionDefinition} is pure metadata with no execute
 *       method, so holding one gives a caller no way to run anything.</li>
 *   <li><strong>There are zero implementations of ActionDefinition in src/main.</strong> The
 *       contract is defined; nothing implements it.</li>
 *   <li>{@link com.nforce.onehr.ai.action.ActionRegistry} is final and holds an immutable empty
 *       map. It deliberately does not autowire a list of definitions, because bean collection
 *       would let any future component register itself silently.</li>
 *   <li>{@link com.nforce.onehr.ai.action.DisabledActionExecutor} is the only
 *       {@link com.nforce.onehr.ai.action.ActionExecutor} and throws unconditionally, with no
 *       enable flag, so mutation cannot be switched on by configuration.</li>
 *   <li><strong>No HTTP endpoint accepts an ActionRequest</strong>, not even a stub returning 501:
 *       an endpoint that looks real invites a client to start calling it.</li>
 *   <li>{@link com.nforce.onehr.ai.contract.AssistantResponse} has no action field and ignores
 *       unknown JSON properties, so a model that invents one has it dropped at parse time.</li>
 *   <li>Nothing in com.nforce.onehr.ai uses reflection, SpEL, dynamic bean lookup, or LLM-supplied
 *       SQL or URLs. Retrieval is parameterised JdbcTemplate only; the model contributes a query
 *       string that is embedded, never interpolated.</li>
 *   <li>The assistant reaches no write. Its only write paths are conversation logging and the
 *       knowledge index, both confined to ai_ tables. Note this guarantee is narrower than it was:
 *       since live data was added, the object graph reaches LeaveService, ExpenseService and
 *       AttendanceService through com.nforce.onehr.ai.data, and those can mutate. What holds it is
 *       that providers call read methods only, enforced by DataProviderSafetyTest rather than by
 *       the graph containing no opportunity.</li>
 * </ol>
 *
 * <p>ActionFrameworkDisabledTest asserts each of these, so re-enabling one by accident fails the
 * build rather than shipping.
 *
 * <p><strong>Intended future flow</strong>, none of which is wired up today: user request, intent
 * detection, structured {@link com.nforce.onehr.ai.action.ActionRequest}, backend authentication,
 * {@link com.nforce.onehr.ai.action.ActionAuthorization}, business-rule validation, explicit
 * {@link com.nforce.onehr.ai.action.ActionConfirmation} where required, an existing OneHR domain
 * service, {@link com.nforce.onehr.ai.action.ActionResult}, assistant response.
 *
 * <p>When actions are eventually enabled they must call the existing OneHR services (LeaveService,
 * RegularizationService and so on) rather than reimplement business rules, so authorization,
 * validation, transactions and the audit trail keep working exactly as they do for a human user.
 * The LLM may only ever propose an action; it can never authorise one, and role, permission and
 * user id are always re-derived server-side from the authenticated principal.
 */
package com.nforce.onehr.ai.action;

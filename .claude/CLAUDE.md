# HRM Portal Development Instructions

## Purpose

This repository is an HRM (Human Resource Management) application. Work as a senior
engineer who is joining an existing product. Understand the existing implementation
before changing it.

The priority is:

1. Correct business behavior
2. Consistency with the existing application
3. Data integrity and security
4. Testability
5. Simplicity
6. Maintainability

Do not optimize for impressive-looking code.

## Golden rule: inspect before implementing

Before making a non-trivial change:

- Find the relevant module, page, API, service, database model, component, and tests.
- Inspect nearby implementations.
- Identify existing reusable utilities/components.
- Understand the data flow.
- Check whether the requested behavior already exists somewhere else.
- Follow existing project conventions unless there is a concrete reason not to.

Do not invent architecture from scratch when the repository already has one.

## Avoid AI slop

Do not:
- over-engineer simple requirements
- add abstractions without demonstrated reuse
- create unnecessary wrappers/helpers
- add dependencies for small problems
- rewrite working code unnecessarily
- add generic boilerplate
- add excessive comments
- introduce unrelated refactoring
- create "future-proof" complexity without a current requirement
- use generic best practices blindly

Prefer the smallest implementation that correctly satisfies the requirement.

Before introducing an abstraction, ask:
"Is this used or clearly going to be reused, and does it make the current code easier to understand?"

If not, keep it local and simple.

## HRM business-domain rules

Treat HRM functionality as business-critical.

Be especially careful with:

- Employee identity and profile data
- Employee IDs
- Joining/exit dates
- Employment status
- Departments, teams, locations, designations
- Attendance and punch records
- Shift and work schedules
- Leave balances and leave transactions
- Holidays
- Regularization/corrections
- Approvals and approval hierarchy
- Payroll-related data if present
- Manager/subordinate relationships
- Role-based permissions
- Audit history
- Notifications
- Bulk uploads/imports

Never silently change business rules.

When a requirement is ambiguous, inspect existing behavior and tests first. If ambiguity
could affect data, permissions, attendance, leave, payroll, or approvals, flag it instead
of guessing.

## Data integrity

For state-changing operations:

- Validate inputs at the appropriate boundary.
- Prevent duplicate operations where the business rule requires uniqueness.
- Consider race conditions for balances, approvals, attendance, and concurrent updates.
- Preserve auditability where the system supports it.
- Use transactions when multiple related writes must succeed or fail together.
- Do not "fix" bad data by silently deleting or overwriting records unless explicitly required.

For date/time behavior:

- Identify the application's timezone convention.
- Do not mix local time and UTC casually.
- Be careful around midnight, date boundaries, DST if relevant, and overnight shifts.
- Preserve exact timestamps when the domain requires them.

## Permissions and security

Assume HRM data is sensitive.

Check authorization for:
- viewing employee data
- editing employee data
- attendance changes
- leave changes
- approvals
- administrative actions
- exports/downloads
- bulk operations

Do not rely solely on UI hiding for authorization.

Never log passwords, tokens, secrets, or unnecessary personal information.

Do not expose more employee information through an API than the caller is authorized to see.

## API/backend changes

When modifying an API:

- Inspect request/response conventions.
- Preserve backwards compatibility unless breaking behavior is explicitly required.
- Validate input.
- Check authorization.
- Handle expected failure cases.
- Return errors consistent with the project.
- Consider idempotency for operations that may be retried.
- Check whether frontend consumers or other services depend on the response shape.

## Frontend changes

Follow the existing design system.

Do not automatically introduce:
- gradients
- glassmorphism
- excessive rounded cards
- decorative dashboards
- unnecessary animations
- badge/pill spam
- giant hero sections
- repetitive card grids

HRM interfaces should prioritize clarity, density where useful, accessibility, and
efficient workflows.

For forms:
- show validation close to the relevant field
- preserve entered values when possible after validation errors
- distinguish required from optional fields
- prevent accidental duplicate submissions
- make loading/disabled states clear

For tables:
- preserve sorting/filtering/pagination conventions already used by the product
- avoid unnecessary client-side loading of large employee datasets
- handle empty, loading, error, and partial-data states

## Testing

Every functional change should have appropriate verification.

Prefer:
- existing unit tests
- integration/API tests
- component tests
- end-to-end tests where appropriate

For bug fixes, add or update a regression test when practical.

Test important HRM edge cases such as:
- duplicate submissions
- boundary dates
- missing punches
- overnight shifts
- leave balance limits
- approval transitions
- unauthorized access
- empty datasets
- invalid IDs
- concurrent or repeated requests

Do not claim tests passed unless they were actually run.

## Scope control

A task should not become an excuse to refactor the repository.

If unrelated problems are discovered:
- do not silently fix them
- mention them separately if they materially matter

Keep the diff focused.

## Final self-check

Before finishing:

- Did I inspect existing code before changing it?
- Did I preserve existing conventions?
- Did I implement the actual business requirement?
- Did I consider authorization?
- Did I consider data integrity?
- Did I consider date/time behavior?
- Did I add appropriate tests?
- Did I avoid unnecessary abstraction?
- Did I modify unrelated files?
- Did I actually run the checks I report?

If any answer is no, correct it when practical before finishing.

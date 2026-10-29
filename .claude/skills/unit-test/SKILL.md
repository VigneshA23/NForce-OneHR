---
name: unit-test
description: Analyze, create, improve, and verify meaningful unit tests for existing application code. Use when testing a specific class, service, component, method, bug fix, or changed implementation.
---

# Unit Test Engineering Skill

## Objective

Produce reliable, maintainable unit tests that verify actual application behavior and catch regressions. Do not optimize for test count or coverage percentage alone.

## 1. Inspect Before Editing

Before writing or modifying tests:

1. Identify the target implementation and its dependencies.
2. Read the complete relevant implementation, not just the method named in the request.
3. Inspect existing tests for the same class, neighboring classes, and relevant test utilities.
4. Identify the project's actual test framework, mocking library, naming conventions, and execution commands.
5. Check the current Git diff and preserve unrelated work.
6. Determine which behaviors are already tested and which meaningful cases are missing.

Do not assume dependencies, test libraries, file paths, or build commands. Discover them from the repository.

## 2. Build a Test Plan

Identify test cases before implementation. Consider:

- Normal successful behavior.
- Boundary values and empty inputs.
- Null or missing values where permitted.
- Invalid inputs and validation failures.
- Business-rule enforcement.
- Dependency failures and exceptions.
- Authorization and role-based restrictions, where relevant.
- State changes and persistence interactions.
- Regression cases for the reported defect.
- Duplicate requests, repeated calls, and idempotency where relevant.

For each case, define the input, expected outcome, and important side effects.

Avoid redundant tests that exercise identical behavior without adding meaningful protection.

## 3. Write Behavior-Focused Tests

- Follow existing project conventions.
- Use descriptive test names that communicate the scenario and expected behavior.
- Keep Arrange, Act, and Assert sections clear.
- Assert observable outcomes, not implementation details unnecessarily.
- Verify important interactions only when they form part of the behavior or contract.
- Mock external dependencies when appropriate, but do not mock the unit's own logic.
- Keep tests independent, deterministic, and repeatable.
- Use realistic fixtures and existing test builders where available.
- Avoid unnecessary sleeps, network calls, real external services, and environment-dependent assumptions.
- Do not weaken assertions just to make a test pass.
- Do not change production code solely to accommodate a poorly designed test.

## 4. Java and Spring Boot Rules

When testing Java backend code:

- Use the test framework and versions already configured in the project.
- Follow existing JUnit and Mockito conventions.
- Prefer direct unit tests for business logic, with dependencies mocked at appropriate boundaries.
- Use Spring test contexts only when Spring wiring, configuration, security filters, or framework integration actually needs verification.
- Test service-layer business rules, validation, authorization decisions, exception handling, and repository interactions where relevant.
- Verify important persistence arguments and state transitions without over-specifying incidental implementation details.
- Use parameterized tests when multiple inputs exercise the same rule clearly.
- Never use a real production database or external service for an ordinary unit test.
- Do not create permanent test users, database records, or test-only application configuration.
- Preserve existing APIs, database schemas, migrations, and business behavior unless the user explicitly requests a change.

## 5. React and Frontend Rules

When testing frontend code:

- Discover and use the existing test runner and libraries.
- Follow established React Testing Library and Vitest/Jest conventions where applicable.
- Test user-visible behavior, accessible roles, labels, and interactions.
- Cover loading, empty, success, error, and permission states where relevant.
- Mock API boundaries rather than duplicating application logic inside tests.
- Avoid brittle assertions against internal component state, implementation-specific CSS, or incidental DOM structure.
- Verify that changes do not break existing navigation, forms, or neighboring functionality.

## 6. Verify Test Quality

After writing the tests:

1. Run the smallest relevant test selection first.
2. Fix genuine test failures and investigate unexpected results.
3. Run related test suites to detect regressions.
4. Run the broader backend or frontend suite when the scope warrants it.
5. Review assertions for false positives, missing negative cases, and excessive mocking.
6. Check the final diff for unrelated changes and accidental modifications to production behavior.
7. Report the exact commands executed and their actual outcomes.

If a test fails, distinguish between:
- A defect in the implementation.
- An incorrect test expectation.
- A test setup or environment problem.
- An unrelated pre-existing failure.

Never change a test expectation simply to obtain a green build.

## 7. Coverage and Regression Analysis

Use coverage reports when available and relevant. Prioritize untested business branches, exception paths, boundary conditions, and defect-prone logic.

Do not introduce new coverage tooling without a clear need. Do not claim that coverage is complete unless supported by evidence.

For a bug fix, create a regression test that would fail under the original defective behavior whenever practical.

## 8. Safety and Scope

- Do not modify unrelated files.
- Do not delete existing tests merely because they fail.
- Do not suppress warnings, disable assertions, or broadly relax validation to achieve green tests.
- Do not modify migrations, schemas, APIs, or production configuration as part of routine test creation.
- Ask before making consequential production-code changes that fall outside the requested scope.
- Do not commit or push changes unless explicitly requested.

## 9. Final Report

Provide a concise report containing:

1. Files inspected and tests added or modified.
2. Important scenarios covered.
3. Test commands executed.
4. Passed, failed, and skipped test counts when available.
5. Any remaining risks, limitations, or unverified behavior.
6. Production files changed, with a reason for each change.

Clearly distinguish verified results from assumptions. If execution was blocked, say so explicitly.

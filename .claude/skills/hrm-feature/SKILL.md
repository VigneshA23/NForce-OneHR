---
name: hrm-feature
description: A disciplined workflow for implementing HRM portal features from requirement to verification.
---

# /hrm-feature

Use this workflow for substantial HRM features.

## Phase 1 — Understand

Inspect:
- requirement
- existing module
- data model
- API/service flow
- UI flow
- authorization
- related tests

Identify the smallest set of files that should change.

## Phase 2 — Plan

Before editing, state briefly:
- current behavior
- required behavior
- files/components likely to change
- business rules
- risks
- tests needed

Do not create a large speculative architecture.

## Phase 3 — Implement

Implement the smallest correct change.

Reuse existing patterns.

## Phase 4 — Verify

Run:
- relevant tests
- type checks/lint/build where applicable

Then run:
- /review
- /security when the change involves protected HRM data or permissions
- /anti-slop

Fix meaningful findings.

## Phase 5 — Report

Provide:
- what changed
- business behavior affected
- tests/checks run
- any remaining concern

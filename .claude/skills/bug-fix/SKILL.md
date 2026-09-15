---
name: bug-fix
description: A disciplined workflow for fixing HRM bugs with regression protection.
---

# /bug-fix

## 1. Reproduce

Inspect the reported behavior and locate the actual execution path.

Do not immediately patch the first suspicious line.

## 2. Find root cause

Trace:
- input
- validation
- business logic
- persistence
- API response
- UI behavior

Determine why the bug occurs.

## 3. Fix minimally

Avoid unrelated refactoring.

## 4. Regression test

Add a test that would fail before the fix and pass after it.

Include boundary/edge cases when relevant.

## 5. Verify

Run the regression test and relevant surrounding tests.

Then run /review and /anti-slop.

## Final report

State:
- root cause
- fix
- regression test
- verification

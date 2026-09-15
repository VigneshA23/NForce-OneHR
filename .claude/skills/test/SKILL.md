---
name: test
description: Add or improve focused regression tests for the current HRM change.
---

# /test

Use this when a feature or bug fix needs stronger test coverage.

1. Inspect existing test conventions.
2. Identify the behavior changed.
3. Add focused tests for the happy path and important edge cases.
4. Include authorization/validation tests when relevant.
5. Run the smallest relevant test command first.
6. Run broader tests when practical.

Do not create tests that simply mirror implementation details.

Report exactly which tests were added and run.

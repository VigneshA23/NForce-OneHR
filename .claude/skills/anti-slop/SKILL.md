---
name: anti-slop
description: Review and clean the current implementation for AI slop, unnecessary complexity, generic UI, and scope creep.
---

# /anti-slop

Use this after implementing a feature, bug fix, or UI change.

## Steps

1. Inspect git diff.
2. Inspect surrounding code.
3. Review using the anti-slop-reviewer.
4. If meaningful issues are found, fix only those issues.
5. Re-run relevant tests/checks.
6. Review the final diff again.

Do not turn this into a general refactor.

Report:
- slop found
- changes made
- checks run

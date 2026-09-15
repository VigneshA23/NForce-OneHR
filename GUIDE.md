# Claude Code HRM Development Kit — Guide

## What this kit is

This is a reusable Claude Code setup for developing and maintaining an HRM portal.

It is designed to reduce two common problems:

1. **AI slop** — generic, over-engineered, boilerplate-heavy code and UI.
2. **AI mistakes** — code that looks plausible but misses business rules, permissions,
   data integrity, regressions, or important tests.

The setup gives Claude persistent project rules plus specialist reviewers.

---

# Files and what they do

## `CLAUDE.md`

This is the most important file.

Claude Code reads it as project instructions. It establishes the baseline behavior for
development in the repository.

It tells Claude to:
- inspect existing code before changing it
- keep changes focused
- avoid unnecessary abstractions
- respect the existing architecture
- treat HRM data as sensitive
- consider permissions and data integrity
- be careful with dates/times
- test business behavior
- avoid generic AI-generated UI

### When it runs

It is intended to influence normal Claude Code work automatically.

---

# Agents

Agents are specialist reviewers/workers.

## `.claude/agents/anti-slop-reviewer.md`

### Purpose
Finds unnecessary AI-style implementation choices.

It looks for:
- over-engineering
- generic boilerplate
- unnecessary abstractions
- UI slop
- scope creep
- unnecessary dependencies

### Use when
A feature has been implemented and you want a quality/simplicity pass.

The `/anti-slop` skill calls this reviewer.

---

## `.claude/agents/code-reviewer.md`

### Purpose
Acts like a senior production code reviewer.

It checks:
- correctness
- regression risk
- HRM business rules
- authorization
- data integrity
- tests
- maintainability

### Use when
A feature or bug fix is ready for review.

Use `/review`.

---

## `.claude/agents/test-engineer.md`

### Purpose
Designs and implements focused regression tests.

It pays special attention to HRM cases such as:
- duplicate submissions
- leave balances
- attendance boundaries
- missing punches
- approval transitions
- authorization
- invalid IDs

### Use when
A change needs tests or a bug needs a regression test.

Use `/test`.

---

## `.claude/agents/security-reviewer.md`

### Purpose
Looks specifically for realistic security problems.

Especially useful for:
- employee data
- APIs
- permissions
- exports
- file uploads
- approvals
- attendance/leave changes

### Use when
A change can expose or modify protected HRM information.

Use `/security`.

---

## `.claude/agents/simplifier.md`

### Purpose
Finds ways to reduce unnecessary complexity after correctness has been established.

It does NOT blindly shorten code.

It must preserve:
- security
- validation
- transactions
- business rules
- tests
- required error handling

### Use when
Claude produced a large or complicated implementation and you want to see if it can
be safely simplified.

Use `/simplify`.

---

# Skills / slash commands

## `/anti-slop`

Checks the current implementation for:
- AI slop
- unnecessary complexity
- generic UI
- scope creep

It can fix meaningful issues and then re-check the diff.

### Typical use

After Claude finishes a feature:

    /anti-slop

---

## `/review`

Runs a production-oriented review.

Checks:
- correctness
- regressions
- HRM business rules
- authorization
- data integrity
- tests

### Typical use

    /review

Use this before considering a significant task complete.

---

## `/test`

Adds or improves focused regression tests.

### Typical use

    /test

Good for:
- new features
- bug fixes
- edge cases
- missing authorization tests

---

## `/security`

Runs a focused security review.

### Typical use

    /security

Especially useful for:
- employee profile changes
- APIs
- admin features
- attendance
- leave
- approvals
- exports
- file uploads

---

## `/simplify`

Looks for safe ways to simplify an implementation.

### Typical use

    /simplify

Run this AFTER correctness/testing, not before.

---

## `/hrm-feature`

Provides a full feature-development workflow:

    understand
        ↓
    plan
        ↓
    implement
        ↓
    test
        ↓
    review
        ↓
    security check (when relevant)
        ↓
    anti-slop
        ↓
    final report

### Typical use

    /hrm-feature

For a substantial feature, this is the recommended starting point.

---

## `/bug-fix`

Provides a bug-fixing workflow:

    reproduce
        ↓
    find root cause
        ↓
    minimal fix
        ↓
    regression test
        ↓
    verify
        ↓
    review + anti-slop

### Typical use

    /bug-fix

This is preferable to simply asking Claude to "fix this" for important HRM bugs.

---

# Recommended workflows

## Small change

Example:
"Change the label on the attendance screen."

Use normal Claude Code.

You probably do NOT need every reviewer.

---

## Normal feature

Example:
"Add employee attendance regularization."

Recommended:

    /hrm-feature

Then, if needed:

    /review
    /security
    /anti-slop

---

## Bug fix

Example:
"Employees can submit leave even when their balance is insufficient."

Use:

    /bug-fix

The workflow should create a regression test and verify the fix.

---

## UI-only change

Use normal development first, then:

    /anti-slop

This is especially useful if Claude created a new screen from scratch.

---

## Permission-sensitive change

For anything involving who can view/change/approve employee information:

    /review
    /security
    /test

---

# Important: don't run everything every time

More agents does not automatically mean better development.

For a tiny change, running five reviews creates noise.

A useful rule:

### Small
Normal Claude Code.

### Medium
`/review` + `/anti-slop`

### HRM business logic
`/test` + `/review` + `/anti-slop`

### Sensitive/permission change
`/test` + `/review` + `/security` + `/anti-slop`

### Major feature
`/hrm-feature`

---

# How to install this into a project

Copy the contents of this kit into the root of your Claude Code project.

You should end up with:

    your-project/
    ├── CLAUDE.md
    └── .claude/
        ├── agents/
        │   ├── anti-slop-reviewer.md
        │   ├── code-reviewer.md
        │   ├── test-engineer.md
        │   ├── security-reviewer.md
        │   └── simplifier.md
        └── skills/
            ├── anti-slop/
            │   └── SKILL.md
            ├── review/
            │   └── SKILL.md
            ├── test/
            │   └── SKILL.md
            ├── security/
            │   └── SKILL.md
            ├── simplify/
            │   └── SKILL.md
            ├── hrm-feature/
            │   └── SKILL.md
            └── bug-fix/
                └── SKILL.md

Do not put `GUIDE.md` inside `.claude`; it is documentation for you.

---

# Important customization

This kit intentionally does NOT assume your exact:
- frontend framework
- backend framework
- database
- authentication provider
- test framework
- HRM business rules
- folder structure

Claude should inspect your actual repository and adapt to it.

If your organization has specific rules, add them to `CLAUDE.md`.

Examples:

- "All APIs use service classes."
- "Use Playwright for E2E tests."
- "Attendance timestamps are stored in UTC."
- "Managers can approve only direct reports."
- "Do not modify generated files."
- "All database changes require migrations."

The more accurate the project-specific rules, the more useful Claude becomes.

---

# One important principle

Do not try to eliminate every abstraction or every polished UI element just because
it could be called "AI slop."

The goal is not:

"Make Claude's code look less like AI."

The real goal is:

"Make Claude produce code that looks like it belongs in THIS codebase and correctly
implements THIS HRM product."

That distinction is important.

---
name: run-local
description: Safely start and verify the current local state of the project after code changes, pulls, merges, or dependency/configuration changes.
---

# Run Local Application

Safely prepare, start, and verify the current local state of the project.

This skill is generic to the current repository. It must work regardless of which feature, module, or branch is currently being developed.

## Core Rules

- Work with the current checkout and working tree.
- Do not commit.
- Do not push.
- Do not merge.
- Do not rebase.
- Do not switch branches.
- Do not reset or clean the repository.
- Never discard or overwrite developer changes.
- Do not modify business logic just to make the application start.
- Do not modify database migrations.
- Do not recreate or reset the database.
- Do not expose passwords, tokens, API keys, or database credentials.
- Avoid unnecessary dependency or configuration changes.

## 1. Inspect the Project

Before running anything:

- Inspect the repository structure.
- Identify backend and frontend applications.
- Identify runtime versions, package managers, and build tools.
- Inspect existing development/startup configuration.
- Check the current Git branch.
- Check `git status`.
- Preserve all existing local changes.

For this repository, likely technologies include:

- Java / Spring Boot / Maven
- React / Vite / npm
- PostgreSQL

However, verify the current repository before assuming these remain unchanged.

## 2. Check Configuration

Inspect existing configuration files such as:

- `application.yml`
- `application.properties`
- `application-local.yml`
- `.env`
- `.env.local`
- `pom.xml`
- `package.json`
- Vite configuration
- API/proxy configuration

Do not print secrets.

If required configuration is missing, report it before making changes.

## 3. Check Existing Processes

Before starting applications:

- Check whether the backend is already running.
- Check whether the frontend is already running.
- Check the configured ports.

For the current OneHR project, the expected development ports are:

- Backend: `8081`
- Frontend: `5180`

These are defaults only. Verify the current configuration first.

If a port is occupied:

1. Identify the process.
2. Determine whether it belongs to the current project.
3. If it is a healthy existing instance of this project, reuse it.
4. If it is clearly an old instance of this project, it may be stopped.
5. If it belongs to another application, do not terminate it. Ask before taking action or use an appropriate alternate port.

## 4. Start Backend

Use the repository's existing development command.

Verify:

- compilation/build succeeds
- Spring Boot starts successfully
- database connection succeeds
- Flyway migrations complete successfully
- application reaches a healthy state

For OneHR, if available, use the existing health endpoint:

`/actuator/health`

Do not change backend code merely to bypass an application error.

## 5. Start Frontend

Use the repository's existing development command.

Verify:

- dependencies are available
- frontend starts successfully
- Vite/dev server reaches a healthy state
- configured frontend URL is available
- API proxy configuration is working
- frontend can communicate with the backend

For OneHR, the expected frontend development URL is:

`http://localhost:5180`

Verify rather than blindly assuming it.

## 6. Diagnose Failures

If startup fails:

1. Read the relevant logs.
2. Identify the root cause.
3. Determine whether it is:
   - environment/configuration
   - missing dependency
   - port/process issue
   - database/migration issue
   - compilation error
   - application/business-logic error

Only make changes when they are clearly necessary for local startup.

If the problem is caused by newly developed application logic:

- do not silently rewrite the feature
- report the error
- explain the likely cause
- wait for explicit instruction before changing business logic

If a safe environment/startup fix is made, report exactly what changed.

## 7. Re-run Verification

After resolving a startup issue:

- rebuild/restart only what is necessary
- verify backend health again
- verify frontend again
- confirm the applications are communicating correctly

If applications are already healthy, do not restart them unnecessarily.

## Final Report

Return a concise report containing:

### Git
- Current branch
- Working-tree status
- Confirmation that no Git operations changed developer work

### Backend
- Status
- URL
- Port
- Startup command
- Health status

### Frontend
- Status
- URL
- Port
- Startup command

### Environment
- Java/runtime version
- Maven version
- Node/npm version
- Database status
- Migration status

### Issues
- Errors
- Warnings
- Missing configuration

### Changes Made
- Files modified, if any
- Reason for each modification

If no files were modified, explicitly state:

`No project files were modified.`
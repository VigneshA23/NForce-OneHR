# Permission catalogue and Super Admin recovery

## How permissions are defined

Every permission is `<node>:<ACTION>:<SCOPE>` (for example `leave:APPROVE:TEAM`). Only **leaf nodes** hold grants; Modules and Submodules
only group them, so access to a module never implies access to anything beneath it.

`backend/src/main/resources/permission-catalogue.yml` is the single definition:

* `modules:` the Module > Submodule > Section tree, the actions each leaf supports, the **scope range** per action
  (`OWN < TEAM < ALL`; `TEAM..ALL`, `ALL`, `OWN`) and which nodes are `sensitivity: HIGH`.
* `routes:` the pages that need a permission (any one of `requires`). Pages not listed are baseline self-service,
  open to every signed-in user. The backend serves this at `GET /api/me/access-catalogue`; the sidebar, route guard
  and AI assistant navigation all read it. There is no role-based menu.

Flyway still seeds the database. `PermissionCatalogueParityTest` fails the build if the YAML and the migrations disagree,
if any authority string in the backend is not a known permission at a supported scope, or if a seeded default grant is
outside its range. `MigrationNoExpansionTest` fails if a migration changes any system role's default permissions except
the deliberate ones listed in it. `EveryEndpointGatedTest` fails if an endpoint has neither `@PreAuthorize` nor a reasoned
entry in `backend/src/test/resources/endpoint-gating-allowlist.txt`.

## Adding a permission

1. Add the leaf (or a new parent) to `permission-catalogue.yml` with its actions and scope ranges.
2. Add a migration: insert the `modules` / `permissions` rows, the default grants (as `('ROLE','module','ACTION','SCOPE')`
   tuples so the seed matrix can read them), and the hierarchy / scope-range tuples (copy the format from V278 / V279).
   Take the next free version after the highest of `origin/dev`, your branch and the shared database's
   `flyway_schema_history`. Keep it idempotent (`ON CONFLICT DO NOTHING`, `IF NOT EXISTS`).
3. If the migration changes any system role's defaults, add the deliberate change to `MigrationNoExpansionTest`.
4. Gate the endpoint with `@PreAuthorize("hasAuthority('node:ACTION:SCOPE')")`; use `ApprovalGuards.assertCanDecide`
   for approvals (permission AND reporting-line eligibility, never your own request).

## Super Admin recovery

The application refuses to remove the last active Super Admin. If every Super Admin is nevertheless gone, start the
backend once with `ONEHR_RECOVERY_SUPER_ADMIN_EMAIL=<email of an existing active user>`. Only when no active Super Admin
exists is that user granted the role (audited as `ROLE_SUPER_ADMIN_RECOVERED`). Remove the variable afterwards.
Without the application, the equivalent SQL is: insert `(user_id, role_id)` into `user_roles` for the `SUPER_ADMIN` role
and bump `users.token_version`.

## Rolling out the 401 change

Unauthenticated requests now return **401** (previously an empty 403). The frontend treats an empty 403 and a 401 the
same way, so deploy the frontend first or together with the backend. Authorization failures for signed-in users are still
403 with a JSON body.

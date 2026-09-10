-- NForce OneHR — Flyway Migration V138
-- (Originally authored as V134 — renumbered to V138: the shared dev DB had already advanced to
-- schema version 137 via other branches' migrations by the time this was applied, same
-- collision documented in the V95/V96/V97/V102 headers. The original V134 file's SQL never
-- actually ran — Flyway saw a different migration already recorded under version 134 and
-- silently skipped this one, since validate-on-migrate is off. That's what broke Add FAQ/Add
-- Guide: help_content_audience never existed even though startup showed no error.)
--
-- Audience targeting for Help & Guidance content, chosen at PUBLISH time (not on the Add/Edit
-- form — publishing is an authorization/visibility decision, editing is a content decision).
-- A content row can be published to any combination of four audiences: EMPLOYEE, MANAGER, HR,
-- ADMIN — mirroring the same 4-bucket collapse the frontend's toShellRole()/nav.config.ts
-- already uses for the 7 real Role codes (HR_ADMIN -> HR, SUPER_ADMIN -> ADMIN; LEADERSHIP/
-- FINANCE/DELIVERY fold into EMPLOYEE, same as nav). See RoleUtils.audienceBuckets.
--
-- Modeled as a child table (one row per selected audience), not a delimited string or a
-- Postgres array on help_content itself — this is a genuine one-to-many relationship, and an
-- EXISTS-correlated-subquery predicate composes cleanly with this codebase's existing
-- Specification<HelpContent>.allOf(...) filter chain (HelpContentSpecifications), the same way
-- help_content_attachment already does for the other one-to-many relationship on this entity.
-- No rows for a content_id means "visible to everyone" — the same as today's unfiltered
-- behavior, so nothing that's already published becomes invisible the moment this ships.
--
-- The old help_content.audience VARCHAR(40) column (added in V94, defaulted to 'ALL', never
-- read or filtered on by any code — confirmed by inspection before writing this migration) is
-- dropped here since this table finally implements what it was reserved for.

CREATE TABLE help_content_audience (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id UUID         NOT NULL REFERENCES help_content(id) ON DELETE CASCADE,
    audience   VARCHAR(20)  NOT NULL,
    CONSTRAINT chk_help_content_audience CHECK (audience IN ('EMPLOYEE', 'MANAGER', 'HR', 'ADMIN')),
    CONSTRAINT uq_help_content_audience UNIQUE (content_id, audience)
);
CREATE INDEX idx_help_content_audience_content ON help_content_audience(content_id);

ALTER TABLE help_content DROP COLUMN audience;

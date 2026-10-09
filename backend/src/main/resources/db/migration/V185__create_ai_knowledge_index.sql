-- NForce OneHR — Flyway Migration V185
--
-- Numbered 185, not 184, deliberately. The local migration folder stops at V183, but the shared
-- dev Neon DB has already applied a V184 ("add avatar url to employees") from another branch whose
-- file is not in this checkout — the same cross-branch drift documented in the V95/V96/V97/V102/
-- V138/V139 headers. 185 is max(applied)+1 as of writing. Flyway here runs with
-- validate-on-migrate: false and ignore-migration-patterns "*:missing,*:future,*:pending,*:ignored",
-- so the absent V184 file is tolerated rather than failing startup.
--
-- Out-of-order application is NOT enabled, so this takes the next free number rather than
-- reserving a high block (e.g. V190+): claiming 190 would leave 185-189 free, and a later branch
-- filling one of those would have its migration skipped as out-of-order after this one lands.
-- Re-check max(version) before merging — another branch may have taken 185 in the meantime.
--
-- Storage for the AI assistant's knowledge index. Nothing here touches an existing OneHR table;
-- the assistant's only write paths are these ai_* tables.
--
-- pgvector availability was verified against this database before writing (extension `vector`
-- 0.8.6 available; CREATE EXTENSION, a vector(1024) column, the <=> cosine operator and an HNSW
-- index with vector_cosine_ops were all exercised in a rolled-back transaction as neondb_owner).
-- `CREATE EXTENSION IF NOT EXISTS` is already the established pattern here — citext and pgcrypto
-- in V1, btree_gist in V154. vector is the fourth.

CREATE EXTENSION IF NOT EXISTS vector;

-- One row per indexable knowledge chunk.
--
-- Deliberately has NO JPA entity, and must not acquire one. The backend test profile runs
-- Hibernate with ddl-auto: create-drop against H2 (Flyway disabled there), so a mapped
-- `vector(1024)` column would fail H2 schema generation and break OneHrApplicationTests.contextLoads
-- along with every other @SpringBootTest. All access goes through JdbcTemplate with parameterised
-- native SQL — see PgVectorKnowledgeIndexRepository.
CREATE TABLE ai_knowledge_chunk (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stable authored id of the owning knowledge unit, e.g. 'action.leave.apply'.
    knowledge_id   VARCHAR(120) NOT NULL,
    -- 0-based position of this chunk within that unit.
    chunk_ordinal  INTEGER      NOT NULL,

    -- FOUNDATION|ROLE|MODULE|PAGE|ACTION|WORKFLOW|ERROR|FAQ|TERM. Stored as VARCHAR rather than a
    -- DB enum, matching how help_content.type and help_content.status already work here.
    knowledge_type VARCHAR(30)  NOT NULL,

    module         VARCHAR(60),
    page_id        VARCHAR(60),

    -- Provenance: a YAML path under ai-knowledge/, or 'help_content:<uuid>'. Also what lets a
    -- re-index retire units whose whole source disappeared (deleted file, unpublished article),
    -- which a per-id upsert loop cannot detect on its own.
    source_ref     VARCHAR(200) NOT NULL,

    version        INTEGER      NOT NULL DEFAULT 1,

    -- SHA-256 of the embedded text, so a re-index can skip unchanged chunks instead of paying for
    -- an embedding API call per unit on every run.
    content_hash   VARCHAR(64)  NOT NULL,

    title          VARCHAR(300) NOT NULL,
    body           TEXT         NOT NULL,
    metadata       JSONB,

    -- 1024 dimensions = mistral-embed. Changing embedding model means changing this width and
    -- fully re-indexing; EmbeddingProvider.dimensions() is checked against it at startup so a
    -- mismatch surfaces then rather than on the first query.
    embedding      vector(1024) NOT NULL,

    indexed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_ai_knowledge_chunk UNIQUE (knowledge_id, chunk_ordinal),
    CONSTRAINT chk_ai_knowledge_chunk_ordinal CHECK (chunk_ordinal >= 0),
    CONSTRAINT chk_ai_knowledge_chunk_type CHECK (knowledge_type IN
        ('FOUNDATION','ROLE','MODULE','PAGE','ACTION','WORKFLOW','ERROR','FAQ','TERM'))
);

-- Who may retrieve a chunk.
--
-- Note this is FAIL-CLOSED and so differs from help_content_audience on purpose: there, a content
-- row with no audience rows means "visible to everyone", a backward-compatible default chosen so
-- nothing already published became invisible. Here, no rows means visible to NOBODY. Authored
-- knowledge is new, has no legacy rows to protect, and an un-tagged unit silently readable by every
-- role is the wrong way for a mistake to fail. The YAML schema validator requires at least one
-- audience per unit, so this constraint should never actually be the thing that catches it.
CREATE TABLE ai_knowledge_chunk_audience (
    chunk_id UUID        NOT NULL REFERENCES ai_knowledge_chunk(id) ON DELETE CASCADE,
    audience VARCHAR(20) NOT NULL,

    CONSTRAINT pk_ai_knowledge_chunk_audience PRIMARY KEY (chunk_id, audience),
    -- Same four buckets as help_content_audience and RoleUtils.audienceBuckets.
    CONSTRAINT chk_ai_knowledge_chunk_audience CHECK (audience IN ('EMPLOYEE','MANAGER','HR','ADMIN'))
);

-- Approximate-nearest-neighbour index for cosine similarity.
--
-- HNSW rather than IVFFlat: IVFFlat needs a representative sample of rows to build meaningful
-- lists, so it degrades badly when built on an empty or tiny table — which is exactly the state
-- this table is in at migration time. HNSW builds incrementally and needs no training data.
CREATE INDEX idx_ai_knowledge_chunk_embedding ON ai_knowledge_chunk
    USING hnsw (embedding vector_cosine_ops);

-- Re-index and invalidation lookups.
CREATE INDEX idx_ai_knowledge_chunk_knowledge ON ai_knowledge_chunk (knowledge_id);
CREATE INDEX idx_ai_knowledge_chunk_source ON ai_knowledge_chunk (source_ref);

-- Supports the optional module/page retrieval bias from the user's current page.
CREATE INDEX idx_ai_knowledge_chunk_type_module ON ai_knowledge_chunk (knowledge_type, module);

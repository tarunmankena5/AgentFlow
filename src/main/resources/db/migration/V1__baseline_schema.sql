-- AgentFlow baseline schema. UUID keys, FK constraints, tenant column on every tenant-owned table.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ===== Identity =====
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    email_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    super_admin     BOOLEAN NOT NULL DEFAULT FALSE,
    failed_logins   INT NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));

CREATE TABLE organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name  VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE permissions (
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code  VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE role_permissions (
    role_id        UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE organization_memberships (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id          UUID NOT NULL REFERENCES roles(id),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);
CREATE INDEX ix_memberships_user ON organization_memberships (user_id);

CREATE TABLE refresh_tokens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id         UUID NOT NULL,                -- rotation family; reuse of a revoked token revokes the family
    token_hash        CHAR(64) NOT NULL UNIQUE,     -- SHA-256 of the opaque token; raw token is never stored
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ,
    replaced_by_id    UUID REFERENCES refresh_tokens(id),
    user_agent        VARCHAR(300),
    ip_address        VARCHAR(45),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE one_time_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(30) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION','PASSWORD_RESET')),
    token_hash  CHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===== Agents =====
CREATE TABLE agents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','PAUSED','ARCHIVED')),
    current_version_id  UUID,
    created_by          UUID NOT NULL REFERENCES users(id),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);
CREATE INDEX ix_agents_org_status ON agents (organization_id, status);

CREATE TABLE agent_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id          UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version_number    INT NOT NULL,
    system_prompt     TEXT NOT NULL,
    llm_provider      VARCHAR(50) NOT NULL,
    model             VARCHAR(100) NOT NULL,
    temperature       NUMERIC(3,2) NOT NULL CHECK (temperature BETWEEN 0 AND 2),
    max_steps         INT NOT NULL CHECK (max_steps > 0),
    max_token_budget  INT NOT NULL CHECK (max_token_budget > 0),
    timeout_seconds   INT NOT NULL CHECK (timeout_seconds > 0),
    memory_config     JSONB NOT NULL DEFAULT '{}'::jsonb,
    generation_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by        UUID NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, version_number)            -- versions are immutable once written
);
ALTER TABLE agents ADD CONSTRAINT fk_agents_current_version
    FOREIGN KEY (current_version_id) REFERENCES agent_versions(id);

-- ===== Tools =====
CREATE TABLE tools (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID REFERENCES organizations(id) ON DELETE CASCADE,  -- NULL = platform built-in
    name                  VARCHAR(100) NOT NULL,
    description           TEXT NOT NULL,
    input_schema          JSONB NOT NULL,
    output_schema         JSONB NOT NULL,
    required_permission   VARCHAR(100) NOT NULL,
    timeout_seconds       INT NOT NULL CHECK (timeout_seconds > 0),
    requires_approval     BOOLEAN NOT NULL DEFAULT FALSE,
    config                JSONB NOT NULL DEFAULT '{}'::jsonb,                   -- e.g. HTTP host allowlist
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tools_scope_name ON tools (COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), name);

CREATE TABLE agent_version_tools (
    agent_version_id  UUID NOT NULL REFERENCES agent_versions(id) ON DELETE CASCADE,
    tool_id           UUID NOT NULL REFERENCES tools(id),
    PRIMARY KEY (agent_version_id, tool_id)
);

-- ===== Workflows =====
CREATE TABLE workflows (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    trigger_type        VARCHAR(20) NOT NULL CHECK (trigger_type IN ('MANUAL','API','SCHEDULED','EVENT')),
    status              VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','DEACTIVATED')),
    current_version_id  UUID,
    created_by          UUID NOT NULL REFERENCES users(id),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

CREATE TABLE workflow_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id       UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version_number    INT NOT NULL,
    input_schema      JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_mapping    JSONB NOT NULL DEFAULT '{}'::jsonb,
    timeout_seconds   INT NOT NULL CHECK (timeout_seconds > 0),
    published         BOOLEAN NOT NULL DEFAULT FALSE,
    created_by        UUID NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, version_number)
);
ALTER TABLE workflows ADD CONSTRAINT fk_workflows_current_version
    FOREIGN KEY (current_version_id) REFERENCES workflow_versions(id);

CREATE TABLE workflow_steps (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_version_id   UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE CASCADE,
    step_key              VARCHAR(100) NOT NULL,
    step_type             VARCHAR(20) NOT NULL CHECK (step_type IN ('AGENT','TOOL','APPROVAL','CONDITION')),
    agent_version_id      UUID REFERENCES agent_versions(id),
    tool_id               UUID REFERENCES tools(id),
    config                JSONB NOT NULL DEFAULT '{}'::jsonb,
    condition_expr        TEXT,
    retry_max_attempts    INT NOT NULL DEFAULT 1 CHECK (retry_max_attempts >= 1),
    retry_backoff_ms      INT NOT NULL DEFAULT 1000,
    timeout_seconds       INT NOT NULL CHECK (timeout_seconds > 0),
    requires_approval     BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (workflow_version_id, step_key)
);

CREATE TABLE workflow_step_dependencies (
    step_id             UUID NOT NULL REFERENCES workflow_steps(id) ON DELETE CASCADE,
    depends_on_step_id  UUID NOT NULL REFERENCES workflow_steps(id) ON DELETE CASCADE,
    PRIMARY KEY (step_id, depends_on_step_id),
    CHECK (step_id <> depends_on_step_id)
);

-- ===== Executions =====
CREATE TABLE workflow_executions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    workflow_id           UUID NOT NULL REFERENCES workflows(id),
    workflow_version_id   UUID NOT NULL REFERENCES workflow_versions(id),
    status                VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','QUEUED','RUNNING','WAITING_FOR_APPROVAL','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    input                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    output                JSONB,
    error_code            VARCHAR(100),
    error_summary         TEXT,
    idempotency_key       VARCHAR(100),
    correlation_id        VARCHAR(64),
    triggered_by          UUID REFERENCES users(id),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    deadline_at           TIMESTAMPTZ,
    version               BIGINT NOT NULL DEFAULT 0,       -- optimistic lock on state transitions
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_exec_org_status ON workflow_executions (organization_id, status, created_at DESC);
CREATE INDEX ix_exec_deadline ON workflow_executions (deadline_at) WHERE status IN ('QUEUED','RUNNING','WAITING_FOR_APPROVAL');
CREATE UNIQUE INDEX ux_exec_idempotency ON workflow_executions (organization_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE execution_steps (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id      UUID NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    step_id           UUID NOT NULL REFERENCES workflow_steps(id),
    status            VARCHAR(30) NOT NULL CHECK (status IN
        ('PENDING','QUEUED','RUNNING','WAITING_FOR_APPROVAL','COMPLETED','FAILED','CANCELLED','TIMED_OUT','SKIPPED')),
    attempt           INT NOT NULL DEFAULT 0,
    input             JSONB,
    output            JSONB,
    summary           TEXT,                                  -- concise operational summary, never raw reasoning
    error_code        VARCHAR(100),
    error_summary     TEXT,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (execution_id, step_id)
);
CREATE INDEX ix_exec_steps_exec ON execution_steps (execution_id);

CREATE TABLE tool_invocations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_step_id   UUID NOT NULL REFERENCES execution_steps(id) ON DELETE CASCADE,
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    tool_id             UUID NOT NULL REFERENCES tools(id),
    status              VARCHAR(20) NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','REJECTED','TIMED_OUT')),
    input_redacted      JSONB,
    output_redacted     JSONB,
    error_summary       TEXT,
    duration_ms         BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_tool_inv_step ON tool_invocations (execution_step_id);

-- Idempotent Kafka consumption: a (consumer, event) pair may be processed once.
CREATE TABLE processed_events (
    consumer    VARCHAR(100) NOT NULL,
    event_id    UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- ===== Approvals =====
CREATE TABLE approval_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    execution_id        UUID NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    execution_step_id   UUID NOT NULL REFERENCES execution_steps(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','ESCALATED')),
    required_role       VARCHAR(50) NOT NULL,
    payload_summary     TEXT,
    deadline_at         TIMESTAMPTZ NOT NULL,
    escalated_at        TIMESTAMPTZ,
    decided_by          UUID REFERENCES users(id),
    decided_at          TIMESTAMPTZ,
    reviewer_comment    TEXT,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_approvals_queue ON approval_requests (organization_id, status, deadline_at);
-- At most one open approval per step.
CREATE UNIQUE INDEX ux_approval_open_per_step ON approval_requests (execution_step_id) WHERE status IN ('PENDING','ESCALATED');

-- ===== Audit / usage / notifications =====
CREATE TABLE audit_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID REFERENCES organizations(id) ON DELETE SET NULL,
    actor_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action           VARCHAR(100) NOT NULL,
    resource_type    VARCHAR(50) NOT NULL,
    resource_id      UUID,
    outcome          VARCHAR(20) NOT NULL,
    details          JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id   VARCHAR(64),
    ip_address       VARCHAR(45),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_org_time ON audit_events (organization_id, created_at DESC);

CREATE TABLE usage_records (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    execution_id        UUID NOT NULL REFERENCES workflow_executions(id) ON DELETE CASCADE,
    execution_step_id   UUID REFERENCES execution_steps(id) ON DELETE SET NULL,
    agent_version_id    UUID REFERENCES agent_versions(id),
    provider            VARCHAR(50) NOT NULL,
    model               VARCHAR(100) NOT NULL,
    prompt_tokens       INT NOT NULL,
    completion_tokens   INT NOT NULL,
    estimated_cost_usd  NUMERIC(14,6) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_usage_org_time ON usage_records (organization_id, created_at DESC);

CREATE TABLE notifications (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type             VARCHAR(50) NOT NULL,
    title            VARCHAR(200) NOT NULL,
    body             TEXT,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notifications (user_id, read_at, created_at DESC);

-- ===== Document search (pgvector) =====
CREATE TABLE document_chunks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    document_id      UUID NOT NULL,
    chunk_index      INT NOT NULL,
    content          TEXT NOT NULL,
    embedding        vector(1536) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX ix_chunks_org ON document_chunks (organization_id);
CREATE INDEX ix_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- ===== Seed reference data =====
INSERT INTO roles (name) VALUES
    ('SUPER_ADMIN'),('ORGANIZATION_ADMIN'),('WORKSPACE_MANAGER'),('DEVELOPER'),('OPERATOR'),('VIEWER');

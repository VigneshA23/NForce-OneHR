CREATE TABLE policies (
    id           BIGSERIAL    PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    version      VARCHAR(20)  NOT NULL,
    description  TEXT         NOT NULL,
    scope        VARCHAR(100) NOT NULL DEFAULT 'All Employees',
    required     BOOLEAN      NOT NULL DEFAULT TRUE,
    published_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_by UUID REFERENCES users(id),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE policy_acknowledgments (
    id               BIGSERIAL PRIMARY KEY,
    policy_id        BIGINT NOT NULL REFERENCES policies(id),
    employee_user_id UUID   NOT NULL REFERENCES users(id),
    acknowledged_at  TIMESTAMPTZ,
    UNIQUE(policy_id, employee_user_id)
);

-- Seed 2 starter policies
INSERT INTO policies (title, version, description, scope, required, published_at) VALUES
(
  'Employee Code of Conduct',
  '1.0',
  'All employees are expected to maintain professional conduct, treat colleagues with respect, protect company data, and comply with applicable laws and internal policies. Violations may result in disciplinary action up to and including termination.',
  'All Employees',
  true,
  now()
),
(
  'IT Security & Acceptable Use Policy',
  '1.0',
  'Company systems and data must be used only for legitimate business purposes. Employees must not share passwords, install unauthorized software, or access data beyond their assigned role. Security incidents must be reported to IT immediately.',
  'All Employees',
  true,
  now()
);

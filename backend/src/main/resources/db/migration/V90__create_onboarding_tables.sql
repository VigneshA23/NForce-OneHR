-- Onboarding checklist: orchestration layer over Employee, Documents and Assets.
-- Only manually-tracked items are stored here. "Laptop assigned", "Access card assigned"
-- and "Required documents verified" are derived live from asset_assignments /
-- employee_documents at read time — never persisted — so the checklist can't drift
-- out of sync with the real document/asset state.

CREATE TABLE onboarding_checklists (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_by       UUID         NOT NULL REFERENCES users(id),
    status           VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS', -- IN_PROGRESS | COMPLETED
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id)
);

CREATE TABLE onboarding_checklist_items (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    checklist_id   UUID         NOT NULL REFERENCES onboarding_checklists(id) ON DELETE CASCADE,
    category       VARCHAR(20)  NOT NULL, -- PRE_BOARDING | SETUP
    item_key       VARCHAR(50)  NOT NULL,
    label          VARCHAR(200) NOT NULL,
    due_date       DATE,
    done           BOOLEAN      NOT NULL DEFAULT false,
    done_at        TIMESTAMPTZ,
    done_by        UUID REFERENCES users(id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_onboarding_checklists_status ON onboarding_checklists(status);
CREATE INDEX idx_onboarding_checklist_items_checklist ON onboarding_checklist_items(checklist_id);

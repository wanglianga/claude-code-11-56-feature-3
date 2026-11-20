-- =====================================================================
-- Spare-part substitution graph, part lifecycle, historical work-order
-- recommendations and warehouse stock-contention confirmations.
--
-- Design choice: edges are stored explicitly and traversed forward at query
-- time (no precomputed concept->part closure). The catalog changes frequently,
-- so paying the traversal cost per diagnosis keeps every path explainable and
-- avoids invalidating/rebuilding large closure indexes on each catalog edit.
-- Every edge carries the exact conditions (model, temperature, interface,
-- validity window, engineering confirmation) that the traversal must verify.
-- =====================================================================

-- Directed, condition-bearing substitution edge: from_part_id -> to_part_id.
-- Rows are versioned inside a catalog version and never silently rewritten;
-- a retraction flips state and keeps the historical edge on record.
CREATE TABLE part_substitution (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_version_id           UUID NOT NULL REFERENCES catalog_version (id),
    from_part_id                 UUID NOT NULL REFERENCES part (id),
    to_part_id                   UUID NOT NULL REFERENCES part (id),
    substitution_kind            VARCHAR(16) NOT NULL
        CHECK (substitution_kind IN ('DIRECT', 'CONDITIONAL', 'RETROFIT')),
    scope_model_id               UUID REFERENCES turbine_model (id),
    temp_min_c                   NUMERIC(5, 2),
    temp_max_c                   NUMERIC(5, 2),
    interface_requirement        VARCHAR(128),
    requires_engineering_confirm BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from                   TIMESTAMPTZ NOT NULL,
    valid_to                     TIMESTAMPTZ,
    state                        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE', 'RETRACTED')),
    approval_ref                 VARCHAR(200),
    approved_by                  VARCHAR(128),
    approved_at                  TIMESTAMPTZ,
    notes                        TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_substitution_parts    CHECK (from_part_id <> to_part_id),
    CONSTRAINT ck_substitution_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_substitution_temp     CHECK (temp_min_c IS NULL OR temp_max_c IS NULL OR temp_min_c <= temp_max_c),
    CONSTRAINT uq_substitution UNIQUE (catalog_version_id, from_part_id, to_part_id)
);
CREATE INDEX idx_sub_from    ON part_substitution (from_part_id, state);
CREATE INDEX idx_sub_catalog ON part_substitution (catalog_version_id);

-- Explicit part lifecycle per catalog. A part missing from a catalog's entries
-- is implicitly absent; an OBSOLETE row is an explicit retirement fact with the
-- reason and the designated successor, usable even when the part is still
-- referenced by historical dispositions and work orders.
CREATE TABLE part_lifecycle (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_version_id     UUID NOT NULL REFERENCES catalog_version (id),
    part_id                UUID NOT NULL REFERENCES part (id),
    state                  VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'OBSOLETE')),
    obsolete_reason        TEXT,
    superseded_by_part_id  UUID REFERENCES part (id),
    decided_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by            VARCHAR(128),
    approval_ref           VARCHAR(200),
    CONSTRAINT uq_part_lifecycle UNIQUE (catalog_version_id, part_id)
);
CREATE INDEX idx_part_lifecycle_part ON part_lifecycle (part_id);

-- What was recommended (or actually issued) for a historical work order at the
-- time it was opened. The row is immutable: later catalog revisions and
-- substitution edges never rewrite it; forward substitution links are computed
-- separately at query time so today's issuable parts cannot be backfilled into
-- a historical recommendation.
CREATE TABLE work_order_part_recommendation (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_order_id         UUID NOT NULL REFERENCES work_order (id),
    concept_code          VARCHAR(64),
    part_id               UUID REFERENCES part (id),
    disposition_code      VARCHAR(64),
    catalog_version_label VARCHAR(64),
    quantity              INTEGER NOT NULL DEFAULT 1,
    recommendation_status VARCHAR(24) NOT NULL CHECK (recommendation_status IN
                              ('RECOMMENDED', 'ISSUED', 'NOT_AVAILABLE', 'SUBSTITUTED')),
    reason                TEXT,
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by           VARCHAR(128) NOT NULL,
    approval_ref          VARCHAR(200)
);
CREATE INDEX idx_wopr_work_order ON work_order_part_recommendation (work_order_id);
CREATE INDEX idx_wopr_part       ON work_order_part_recommendation (part_id);

-- Warehouse-side confirmation of contention for a candidate part at an
-- inventory point in time. Diagnosis may only flag that a suggestion may be
-- invalid; the warehouse owns the final allocation decision.
--   CLAIM_PENDING      : competing demand declared, allocation not yet decided
--   CONFIRMED_ALLOCATED: warehouse confirmed the stock goes to the other demand
--   RELEASED           : contention cleared, stock is available again
CREATE TABLE stock_contention (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_snapshot_id  UUID NOT NULL REFERENCES inventory_snapshot (id),
    part_id                UUID NOT NULL REFERENCES part (id),
    warehouse              VARCHAR(128),
    status                 VARCHAR(24) NOT NULL
        CHECK (status IN ('CLAIM_PENDING', 'CONFIRMED_ALLOCATED', 'RELEASED')),
    competing_demand       VARCHAR(256) NOT NULL,
    confirmed_by           VARCHAR(128),
    confirmed_at           TIMESTAMPTZ,
    notes                  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_contention ON stock_contention (inventory_snapshot_id, part_id, status);

-- ---------------------------------------------------------------------
-- Frozen release counterparts. SNAPSHOT-mode diagnosis must be able to
-- traverse the substitution graph and replay historical recommendations
-- using only release_* rows.
-- ---------------------------------------------------------------------
CREATE TABLE release_part_substitution (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id                   UUID NOT NULL REFERENCES release (id),
    from_part_number             VARCHAR(128) NOT NULL,
    to_part_number               VARCHAR(128) NOT NULL,
    substitution_kind            VARCHAR(16) NOT NULL,
    model_code                   VARCHAR(64),
    temp_min_c                   NUMERIC(5, 2),
    temp_max_c                   NUMERIC(5, 2),
    interface_requirement        VARCHAR(128),
    requires_engineering_confirm BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from                   TIMESTAMPTZ NOT NULL,
    valid_to                     TIMESTAMPTZ,
    state                        VARCHAR(16) NOT NULL,
    approval_ref                 VARCHAR(200),
    approved_by                  VARCHAR(128),
    approved_at                  TIMESTAMPTZ,
    notes                        TEXT
);
CREATE INDEX idx_release_sub ON release_part_substitution (release_id, from_part_number);

CREATE TABLE release_part_lifecycle (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id                 UUID NOT NULL REFERENCES release (id),
    part_number                VARCHAR(128) NOT NULL,
    state                      VARCHAR(16) NOT NULL,
    obsolete_reason            TEXT,
    superseded_by_part_number  VARCHAR(128),
    decided_at                 TIMESTAMPTZ NOT NULL,
    approved_by                VARCHAR(128),
    approval_ref               VARCHAR(200)
);
CREATE INDEX idx_release_life ON release_part_lifecycle (release_id, part_number);

CREATE TABLE release_work_order_recommendation (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id            UUID NOT NULL REFERENCES release (id),
    work_order_external_id VARCHAR(128) NOT NULL,
    concept_code          VARCHAR(64),
    part_number           VARCHAR(128),
    disposition_code      VARCHAR(64),
    catalog_version_label VARCHAR(64),
    quantity              INTEGER NOT NULL DEFAULT 1,
    recommendation_status VARCHAR(24) NOT NULL,
    reason                TEXT,
    recorded_at           TIMESTAMPTZ NOT NULL,
    recorded_by           VARCHAR(128),
    approval_ref          VARCHAR(200)
);
CREATE INDEX idx_release_wopr ON release_work_order_recommendation (release_id, work_order_external_id);

CREATE TABLE release_stock_contention (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id             UUID NOT NULL REFERENCES release (id),
    part_number            VARCHAR(128) NOT NULL,
    warehouse              VARCHAR(128),
    status                 VARCHAR(24) NOT NULL,
    competing_demand       VARCHAR(256) NOT NULL,
    confirmed_by           VARCHAR(128),
    confirmed_at           TIMESTAMPTZ,
    notes                  TEXT,
    created_at             TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_release_contention ON release_stock_contention (release_id, part_number, status);

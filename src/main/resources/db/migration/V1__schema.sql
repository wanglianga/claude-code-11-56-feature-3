-- =====================================================================
-- Offshore wind fault-code reference mapping & diagnosis replay service
-- Schema: reference data, mapping assertions, release snapshots,
--         alarms/work orders, diagnosis runs and traceable findings.
-- =====================================================================

CREATE TABLE manufacturer (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE turbine_model (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manufacturer_id  UUID NOT NULL REFERENCES manufacturer (id),
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_code UNIQUE (manufacturer_id, code)
);

CREATE TABLE firmware_version (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id     UUID NOT NULL REFERENCES turbine_model (id),
    version      VARCHAR(64) NOT NULL,
    sequence     INTEGER     NOT NULL,
    released_on  DATE,
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_firmware UNIQUE (model_id, version),
    CONSTRAINT uq_firmware_seq UNIQUE (model_id, sequence)
);

CREATE TABLE unit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    model_id        UUID NOT NULL REFERENCES turbine_model (id),
    commissioned_on DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE unit_firmware_install (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    unit_id             UUID NOT NULL REFERENCES unit (id),
    firmware_version_id UUID NOT NULL REFERENCES firmware_version (id),
    installed_from      TIMESTAMPTZ NOT NULL,
    installed_to        TIMESTAMPTZ
);
CREATE INDEX idx_ufi_unit ON unit_firmware_install (unit_id, installed_from);

-- ---------------------------------------------------------------------
-- Sources: controller codes and vendor natural-language terminology
-- ---------------------------------------------------------------------
CREATE TABLE source_system (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(32)  NOT NULL CHECK (kind IN ('CONTROLLER', 'VENDOR_TERM')),
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE source_code (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system_id UUID NOT NULL REFERENCES source_system (id),
    code             VARCHAR(128) NOT NULL,
    raw_text         TEXT         NOT NULL,
    description      TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_source_code UNIQUE (source_system_id, code)
);

CREATE TABLE term_attachment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system_id UUID REFERENCES source_system (id),
    vendor        VARCHAR(128) NOT NULL,
    term_text     TEXT         NOT NULL,
    language      VARCHAR(16)  NOT NULL DEFAULT 'zh-CN',
    attachment_ref TEXT,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_term UNIQUE (vendor, term_text)
);

-- ---------------------------------------------------------------------
-- Unified diagnostic concepts (versioned definitions)
-- ---------------------------------------------------------------------
CREATE TABLE concept (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(64)  NOT NULL UNIQUE,
    name               VARCHAR(200) NOT NULL,
    category           VARCHAR(64)  NOT NULL,
    severity           VARCHAR(32)  NOT NULL,
    current_definition_version INTEGER NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE concept_definition_version (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concept_id    UUID NOT NULL REFERENCES concept (id),
    version       INTEGER NOT NULL,
    definition    TEXT NOT NULL,
    approved_by   VARCHAR(128) NOT NULL,
    approval_ref  VARCHAR(200) NOT NULL,
    approved_at   TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_concept_def_version UNIQUE (concept_id, version)
);

-- ---------------------------------------------------------------------
-- History model A: time-validity mapping assertions.
-- Rows are never rewritten when a contradictory mapping arrives; both
-- stay ACTIVE and the overlap surfaces as a preserved conflict.
-- ---------------------------------------------------------------------
CREATE TABLE mapping_assertion (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_code_id        UUID REFERENCES source_code (id),
    term_attachment_id    UUID REFERENCES term_attachment (id),
    concept_id            UUID NOT NULL REFERENCES concept (id),
    definition_version    INTEGER NOT NULL,
    scope_manufacturer_id UUID REFERENCES manufacturer (id),
    scope_model_id        UUID REFERENCES turbine_model (id),
    firmware_from         VARCHAR(64),
    firmware_to           VARCHAR(64),
    firmware_from_seq     INTEGER,
    firmware_to_seq       INTEGER,
    qualifiers            JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence            NUMERIC(4, 3),
    state                 VARCHAR(16) NOT NULL CHECK (state IN ('PROPOSED', 'ACTIVE', 'RETRACTED', 'SUPERSEDED')),
    valid_from            TIMESTAMPTZ NOT NULL,
    valid_to              TIMESTAMPTZ,
    recorded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    supersedes_id         UUID REFERENCES mapping_assertion (id),
    approval_ref          VARCHAR(200),
    approved_by           VARCHAR(128),
    approved_at           TIMESTAMPTZ,
    notes                 TEXT,
    CONSTRAINT ck_assertion_source CHECK ((source_code_id IS NOT NULL) <> (term_attachment_id IS NOT NULL)),
    CONSTRAINT ck_assertion_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_assertion_fw_scope CHECK (
        (firmware_from IS NULL AND firmware_to IS NULL)
        OR scope_model_id IS NOT NULL
    ),
    CONSTRAINT ck_assertion_fw_seq CHECK (
        (firmware_from IS NULL) = (firmware_from_seq IS NULL)
        AND (firmware_to IS NULL) = (firmware_to_seq IS NULL)
    )
);
CREATE INDEX idx_assertion_source_code ON mapping_assertion (source_code_id, state);
CREATE INDEX idx_assertion_term ON mapping_assertion (term_attachment_id, state);
CREATE INDEX idx_assertion_validity ON mapping_assertion (valid_from, valid_to);

-- Explicit engineer adjudication of a preserved mapping conflict.
-- This never mutates the conflicting assertions; it is an additional,
-- time-valid decision record that diagnosis evaluates alongside them.
CREATE TABLE mapping_adjudication (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_code_id      UUID REFERENCES source_code (id),
    term_attachment_id  UUID REFERENCES term_attachment (id),
    concept_a_id        UUID NOT NULL REFERENCES concept (id),
    concept_b_id        UUID NOT NULL REFERENCES concept (id),
    chosen_concept_id   UUID NOT NULL REFERENCES concept (id),
    scope_model_id      UUID REFERENCES turbine_model (id),
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_to            TIMESTAMPTZ,
    decision            TEXT NOT NULL,
    adjudicated_by      VARCHAR(128) NOT NULL,
    adjudicated_at      TIMESTAMPTZ  NOT NULL,
    approval_ref        VARCHAR(200) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_adjudication_source CHECK ((source_code_id IS NOT NULL) <> (term_attachment_id IS NOT NULL)),
    CONSTRAINT ck_adjudication_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);

-- ---------------------------------------------------------------------
-- Spare-part catalog versions, compatibility, inventory, dispositions
-- ---------------------------------------------------------------------
CREATE TABLE catalog_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_label   VARCHAR(64) NOT NULL UNIQUE,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    effective_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    approved_by     VARCHAR(128),
    approval_ref    VARCHAR(200),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE part (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    part_number  VARCHAR(128) NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    category     VARCHAR(64)  NOT NULL,
    manufacturer VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE catalog_entry (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_version_id UUID NOT NULL REFERENCES catalog_version (id),
    part_id           UUID NOT NULL REFERENCES part (id),
    description       TEXT,
    attributes        JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_catalog_entry UNIQUE (catalog_version_id, part_id)
);

CREATE TABLE catalog_entry_compat (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_entry_id  UUID NOT NULL REFERENCES catalog_entry (id),
    model_id          UUID REFERENCES turbine_model (id),
    firmware_from     VARCHAR(64),
    firmware_to       VARCHAR(64),
    firmware_from_seq INTEGER,
    firmware_to_seq   INTEGER
);
CREATE INDEX idx_compat_entry ON catalog_entry_compat (catalog_entry_id);

CREATE TABLE inventory_snapshot (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    catalog_version_id UUID REFERENCES catalog_version (id),
    label              VARCHAR(128) NOT NULL UNIQUE,
    taken_at           TIMESTAMPTZ NOT NULL,
    source             VARCHAR(128),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory_level (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_snapshot_id UUID NOT NULL REFERENCES inventory_snapshot (id),
    part_id             UUID NOT NULL REFERENCES part (id),
    warehouse           VARCHAR(128) NOT NULL,
    qty_available       INTEGER NOT NULL,
    CONSTRAINT uq_inventory_level UNIQUE (inventory_snapshot_id, part_id, warehouse)
);

CREATE TABLE disposition_option (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    concept_id         UUID NOT NULL REFERENCES concept (id),
    catalog_version_id UUID NOT NULL REFERENCES catalog_version (id),
    code               VARCHAR(64) NOT NULL,
    action             VARCHAR(32) NOT NULL CHECK (action IN ('REPLACE', 'REPAIR', 'INSPECT', 'ADJUST')),
    description        TEXT NOT NULL,
    part_id            UUID REFERENCES part (id),
    quantity           INTEGER NOT NULL DEFAULT 1,
    exclusive_group    VARCHAR(64),
    requires_stock     BOOLEAN NOT NULL DEFAULT TRUE,
    min_stock          INTEGER NOT NULL DEFAULT 1,
    valid_from         TIMESTAMPTZ NOT NULL,
    valid_to           TIMESTAMPTZ,
    approved_by        VARCHAR(128),
    approval_ref       VARCHAR(200),
    approved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_disposition UNIQUE (catalog_version_id, code)
);

-- ---------------------------------------------------------------------
-- Evidence: alarms (controller + vendor term) and vendor work orders
-- ---------------------------------------------------------------------
CREATE TABLE alarm (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id         VARCHAR(128) NOT NULL UNIQUE,
    unit_id             UUID NOT NULL REFERENCES unit (id),
    source_system_id    UUID NOT NULL REFERENCES source_system (id),
    source_code_id      UUID REFERENCES source_code (id),
    term_attachment_id  UUID REFERENCES term_attachment (id),
    raw_code            VARCHAR(256) NOT NULL,
    firmware_version_id UUID REFERENCES firmware_version (id),
    occurred_at         TIMESTAMPTZ NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL,
    ingest_type         VARCHAR(16) NOT NULL CHECK (ingest_type IN ('REALTIME', 'OFFLINE_BACKFILL')),
    attributes          JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_payload         JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_alarm_source CHECK ((source_code_id IS NOT NULL) OR (term_attachment_id IS NOT NULL)),
    CONSTRAINT ck_alarm_times CHECK (received_at >= occurred_at)
);
CREATE INDEX idx_alarm_occurred ON alarm (occurred_at);
CREATE INDEX idx_alarm_unit_time ON alarm (unit_id, occurred_at);

CREATE TABLE work_order (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id        VARCHAR(128) NOT NULL UNIQUE,
    unit_id            UUID NOT NULL REFERENCES unit (id),
    vendor             VARCHAR(128) NOT NULL,
    reported_term      VARCHAR(256) NOT NULL,
    term_attachment_id UUID REFERENCES term_attachment (id),
    opened_at          TIMESTAMPTZ NOT NULL,
    closed_at          TIMESTAMPTZ,
    status             VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
    symptom            TEXT,
    raw_payload        JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wo_unit_time ON work_order (unit_id, opened_at);

-- ---------------------------------------------------------------------
-- History model B: release snapshot header (children defined below).
-- Declared before diagnosis_run because runs reference it.
-- ---------------------------------------------------------------------
CREATE TABLE release (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_label    VARCHAR(64) NOT NULL UNIQUE,
    status           VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    effective_from   TIMESTAMPTZ NOT NULL,
    catalog_version_label VARCHAR(64),
    inventory_snapshot_label VARCHAR(128),
    content_hash     VARCHAR(64),
    approved_by      VARCHAR(128),
    approval_ref     VARCHAR(200),
    published_at     TIMESTAMPTZ,
    created_by       VARCHAR(128) NOT NULL,
    notes            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_release_status_effective ON release (status, effective_from);

-- ---------------------------------------------------------------------
-- Diagnosis runs (each result pinned to one catalog/release context)
-- ---------------------------------------------------------------------
CREATE TABLE diagnosis_run (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mode                        VARCHAR(16) NOT NULL CHECK (mode IN ('ASSERTION', 'SNAPSHOT')),
    basis                       VARCHAR(16) NOT NULL CHECK (basis IN ('LATEST', 'AS_OF', 'RELEASE')),
    release_id                  UUID REFERENCES release (id),
    as_of                       TIMESTAMPTZ,
    pinned_catalog_version_id   UUID REFERENCES catalog_version (id),
    pinned_inventory_snapshot_id UUID REFERENCES inventory_snapshot (id),
    evaluation_time            TIMESTAMPTZ NOT NULL,
    knowledge_cutoff           TIMESTAMPTZ,
    evaluated_at               TIMESTAMPTZ NOT NULL,
    status                     VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    created_by                 VARCHAR(128) NOT NULL,
    request                    JSONB,
    summary                    JSONB,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at                TIMESTAMPTZ
);
CREATE INDEX idx_run_created ON diagnosis_run (created_at);

CREATE TABLE diagnosis_result (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL REFERENCES diagnosis_run (id),
    alarm_id            UUID NOT NULL REFERENCES alarm (id),
    status              VARCHAR(24) NOT NULL CHECK (status IN ('RESOLVED', 'CONFLICT', 'PENDING_ENGINEER', 'NO_MAPPING', 'NO_DISPOSITION')),
    finding_count       INTEGER NOT NULL DEFAULT 0,
    primary_concept_id  UUID REFERENCES concept (id),
    adjudicated         BOOLEAN NOT NULL DEFAULT FALSE,
    explanation         TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_result_run_alarm UNIQUE (run_id, alarm_id)
);
CREATE INDEX idx_result_run_status ON diagnosis_result (run_id, status);
CREATE INDEX idx_result_concept ON diagnosis_result (primary_concept_id);

CREATE TABLE diagnosis_finding (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    result_id             UUID NOT NULL REFERENCES diagnosis_result (id),
    concept_id            UUID NOT NULL REFERENCES concept (id),
    concept_code          VARCHAR(64) NOT NULL,
    concept_name          VARCHAR(200) NOT NULL,
    definition_version    INTEGER NOT NULL,
    definition_text       TEXT NOT NULL,
    confidence            NUMERIC(4, 3),
    disposition_status    VARCHAR(24) NOT NULL CHECK (disposition_status IN ('CONFIRMED', 'PENDING_ENGINEER', 'NO_DISPOSITION')),
    disposition           JSONB NOT NULL DEFAULT '{}'::jsonb,
    mapping_chain         JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_assertion_ids UUID[],
    evidence_release_entry_ids UUID[],
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_result ON diagnosis_finding (result_id);

-- ---------------------------------------------------------------------
-- History model B: immutable release snapshots.
-- A published release freezes codes, terms, concepts/definitions,
-- firmware timeline, catalog, stock, dispositions and adjudications.
-- Diagnosis in SNAPSHOT mode reads only these tables.
-- ---------------------------------------------------------------------
CREATE TABLE release_code_entry (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id           UUID NOT NULL REFERENCES release (id),
    source_system_code   VARCHAR(64) NOT NULL,
    source_system_kind   VARCHAR(32) NOT NULL,
    source_code          VARCHAR(128) NOT NULL,
    raw_text             TEXT NOT NULL,
    manufacturer_code    VARCHAR(64),
    model_code           VARCHAR(64),
    firmware_from        VARCHAR(64),
    firmware_to          VARCHAR(64),
    firmware_from_seq    INTEGER,
    firmware_to_seq      INTEGER,
    qualifiers           JSONB NOT NULL DEFAULT '{}'::jsonb,
    concept_code         VARCHAR(64) NOT NULL,
    concept_name         VARCHAR(200) NOT NULL,
    concept_category     VARCHAR(64) NOT NULL,
    concept_severity     VARCHAR(32) NOT NULL,
    definition_version   INTEGER NOT NULL,
    definition_text      TEXT NOT NULL,
    confidence           NUMERIC(4, 3),
    assertion_id         UUID,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    approval_ref         VARCHAR(200),
    approved_by          VARCHAR(128),
    approved_at          TIMESTAMPTZ
);
CREATE INDEX idx_release_code_lookup ON release_code_entry (release_id, source_system_code, source_code);

CREATE TABLE release_term_entry (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id           UUID NOT NULL REFERENCES release (id),
    vendor               VARCHAR(128) NOT NULL,
    term_text            TEXT NOT NULL,
    manufacturer_code    VARCHAR(64),
    model_code           VARCHAR(64),
    firmware_from        VARCHAR(64),
    firmware_to          VARCHAR(64),
    firmware_from_seq    INTEGER,
    firmware_to_seq      INTEGER,
    qualifiers           JSONB NOT NULL DEFAULT '{}'::jsonb,
    concept_code         VARCHAR(64) NOT NULL,
    concept_name         VARCHAR(200) NOT NULL,
    concept_category     VARCHAR(64) NOT NULL,
    concept_severity     VARCHAR(32) NOT NULL,
    definition_version   INTEGER NOT NULL,
    definition_text      TEXT NOT NULL,
    confidence           NUMERIC(4, 3),
    assertion_id         UUID,
    valid_from           TIMESTAMPTZ NOT NULL,
    valid_to             TIMESTAMPTZ,
    approval_ref         VARCHAR(200),
    approved_by          VARCHAR(128),
    approved_at          TIMESTAMPTZ
);
CREATE INDEX idx_release_term_lookup ON release_term_entry (release_id, vendor, term_text);

CREATE TABLE release_concept (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    concept_code       VARCHAR(64) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    category           VARCHAR(64) NOT NULL,
    severity           VARCHAR(32) NOT NULL,
    definition_version INTEGER NOT NULL,
    definition_text    TEXT NOT NULL,
    approved_by        VARCHAR(128) NOT NULL,
    approval_ref       VARCHAR(200) NOT NULL,
    approved_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_release_concept UNIQUE (release_id, concept_code, definition_version)
);

CREATE TABLE release_firmware (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    manufacturer_code  VARCHAR(64) NOT NULL,
    model_code         VARCHAR(64) NOT NULL,
    version            VARCHAR(64) NOT NULL,
    sequence           INTEGER NOT NULL,
    released_on        DATE
);

CREATE TABLE release_part (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    part_number        VARCHAR(128) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    category           VARCHAR(64) NOT NULL,
    description        TEXT,
    attributes         JSONB NOT NULL DEFAULT '{}'::jsonb,
    catalog_version_label VARCHAR(64) NOT NULL
);
CREATE INDEX idx_release_part_lookup ON release_part (release_id, part_number);

CREATE TABLE release_part_compat (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_part_id    UUID NOT NULL REFERENCES release_part (id),
    manufacturer_code  VARCHAR(64),
    model_code         VARCHAR(64),
    firmware_from      VARCHAR(64),
    firmware_to        VARCHAR(64),
    firmware_from_seq  INTEGER,
    firmware_to_seq    INTEGER
);

CREATE TABLE release_inventory (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    part_number        VARCHAR(128) NOT NULL,
    warehouse          VARCHAR(128) NOT NULL,
    qty_available      INTEGER NOT NULL
);

CREATE TABLE release_disposition (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    concept_code       VARCHAR(64) NOT NULL,
    code               VARCHAR(64) NOT NULL,
    action             VARCHAR(32) NOT NULL,
    description        TEXT NOT NULL,
    part_number        VARCHAR(128),
    quantity           INTEGER NOT NULL DEFAULT 1,
    exclusive_group    VARCHAR(64),
    requires_stock     BOOLEAN NOT NULL DEFAULT TRUE,
    min_stock          INTEGER NOT NULL DEFAULT 1,
    catalog_version_label VARCHAR(64) NOT NULL,
    valid_from         TIMESTAMPTZ NOT NULL,
    valid_to           TIMESTAMPTZ,
    approved_by        VARCHAR(128),
    approval_ref       VARCHAR(200)
);

CREATE TABLE release_adjudication (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    source_kind        VARCHAR(16) NOT NULL CHECK (source_kind IN ('CODE', 'TERM')),
    source_system_code VARCHAR(64),
    source_code        VARCHAR(128),
    vendor             VARCHAR(128),
    term_text          TEXT,
    concept_a_code     VARCHAR(64) NOT NULL,
    concept_b_code     VARCHAR(64) NOT NULL,
    chosen_concept_code VARCHAR(64) NOT NULL,
    model_code         VARCHAR(64),
    valid_from         TIMESTAMPTZ NOT NULL,
    valid_to           TIMESTAMPTZ,
    decision           TEXT NOT NULL,
    adjudicated_by     VARCHAR(128) NOT NULL,
    adjudicated_at     TIMESTAMPTZ NOT NULL,
    approval_ref       VARCHAR(200) NOT NULL
);

CREATE TABLE release_source_code (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    source_system_code VARCHAR(64) NOT NULL,
    code               VARCHAR(128) NOT NULL,
    raw_text           TEXT NOT NULL,
    description        TEXT
);

CREATE TABLE release_term (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id         UUID NOT NULL REFERENCES release (id),
    source_system_code VARCHAR(64),
    vendor             VARCHAR(128) NOT NULL,
    term_text          TEXT NOT NULL,
    attachment_ref     TEXT,
    notes              TEXT
);

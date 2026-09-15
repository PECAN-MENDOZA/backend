-- Lotes ciegos de anotacion humana (ortografia / seguridad semantica).
-- La correspondencia muestra -> ejecucion/sesion vive solo en annotation_items; el CSV exportado
-- nunca contiene condicion, seudonimo, orden, version del modelo ni resultado esperado.
CREATE TABLE annotation_batches (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES research_studies(id),
    kind VARCHAR(20) NOT NULL,
    row_count INTEGER NOT NULL,
    -- VARCHAR y no CHAR: Hibernate (ddl-auto=validate) rechaza bpchar para una propiedad String.
    export_sha256 VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_annotation_kind CHECK (kind IN ('ORTHOGRAPHY', 'SEMANTIC')),
    CONSTRAINT ck_annotation_row_count CHECK (row_count > 0),
    CONSTRAINT ck_annotation_export_hash CHECK (export_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE annotation_items (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES annotation_batches(id) ON DELETE CASCADE,
    sample_code VARCHAR(24) NOT NULL CONSTRAINT uk_annotation_sample_code UNIQUE,
    -- Orden aleatorio fijado al crear el lote: la exportacion se reproduce byte a byte (export_sha256).
    position INTEGER NOT NULL,
    run_id UUID NOT NULL REFERENCES experiment_runs(id),
    correction_session_id UUID REFERENCES correction_sessions(id),
    suggestion_index INTEGER,
    rater_1_score INTEGER,
    rater_2_score INTEGER,
    adjudicated_score INTEGER,
    CONSTRAINT uk_annotation_item_position UNIQUE (batch_id, position),
    CONSTRAINT ck_annotation_source CHECK (
        (correction_session_id IS NULL AND suggestion_index IS NULL)
        OR (correction_session_id IS NOT NULL AND suggestion_index >= 0)
    ),
    CONSTRAINT ck_annotation_scores CHECK (
        (rater_1_score IS NULL OR rater_1_score >= 0)
        AND (rater_2_score IS NULL OR rater_2_score >= 0)
        AND (adjudicated_score IS NULL OR adjudicated_score >= 0)
    )
);

-- Cada importacion se conserva (autor, fecha, lote, hash, version y contenido); una sustitucion
-- marca superseded_at en la anterior y nunca la borra.
CREATE TABLE annotation_imports (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES annotation_batches(id),
    slot VARCHAR(20) NOT NULL,
    version INTEGER NOT NULL,
    rater VARCHAR(80) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    imported_by UUID NOT NULL REFERENCES researcher_users(id),
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    superseded_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_annotation_import UNIQUE (batch_id, slot, file_sha256),
    CONSTRAINT uk_annotation_import_version UNIQUE (batch_id, slot, version),
    CONSTRAINT ck_annotation_slot CHECK (slot IN ('RATER_1', 'RATER_2', 'ADJUDICATED')),
    CONSTRAINT ck_annotation_import_version CHECK (version >= 1),
    CONSTRAINT ck_annotation_import_hash CHECK (file_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_annotation_batches_study ON annotation_batches(study_id, created_at DESC);
CREATE INDEX idx_annotation_items_batch ON annotation_items(batch_id, position);
CREATE INDEX idx_annotation_items_run ON annotation_items(run_id);
CREATE INDEX idx_annotation_imports_batch ON annotation_imports(batch_id, slot, version DESC);

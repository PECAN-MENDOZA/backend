-- Evaluacion tecnica independiente (Precision, Recall, F0.5) sobre un conjunto reservado y versionado.
-- Nunca se mezcla con los resultados con participantes: no referencia estudios ni ejecuciones.
CREATE TABLE technical_evaluations (
    id UUID PRIMARY KEY,
    model_version VARCHAR(160) NOT NULL,
    -- VARCHAR y no CHAR: Hibernate (ddl-auto=validate) rechaza bpchar para una propiedad String.
    dataset_sha256 VARCHAR(64) NOT NULL,
    scorer_version VARCHAR(80) NOT NULL,
    precision_value DOUBLE PRECISION NOT NULL,
    recall_value DOUBLE PRECISION NOT NULL,
    f_zero_five DOUBLE PRECISION NOT NULL,
    true_positives INTEGER NOT NULL,
    false_positives INTEGER NOT NULL,
    false_negatives INTEGER NOT NULL,
    created_by UUID NOT NULL REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_technical_eval_dataset_hash CHECK (dataset_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_technical_eval_rates CHECK (
        precision_value BETWEEN 0 AND 1 AND recall_value BETWEEN 0 AND 1 AND f_zero_five BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_technical_eval_counts CHECK (
        true_positives >= 0 AND false_positives >= 0 AND false_negatives >= 0
    )
);

CREATE INDEX idx_technical_evaluations_owner ON technical_evaluations(created_by, created_at DESC);

-- Desglose opcional por categoria de error (spec §11.4): lista JSON de
-- {category, tp, fp, fn, precision, recall, f05}, validada con las mismas reglas que el vector global.
ALTER TABLE technical_evaluations ADD COLUMN categories_json TEXT;

-- Pruebas de oraciones: reemplazan al experimento anterior (estudios, protocolos, participantes,
-- codigos, lotes ciegos y evaluaciones tecnicas). Nada de lo nuevo se borra fisicamente.

ALTER TABLE correction_sessions DROP COLUMN IF EXISTS experiment_run_id;

DROP TABLE IF EXISTS annotation_imports;
DROP TABLE IF EXISTS annotation_items;
DROP TABLE IF EXISTS annotation_batches;
DROP TABLE IF EXISTS technical_evaluations;
DROP TABLE IF EXISTS experiment_incidents;
DROP TABLE IF EXISTS experiment_runs;
DROP TABLE IF EXISTS study_participants;
DROP TABLE IF EXISTS protocol_tasks;
DROP TABLE IF EXISTS study_protocols;
DROP TABLE IF EXISTS research_audit_events;
DROP TABLE IF EXISTS research_studies;

CREATE TABLE sentence_tests (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    title VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    notes TEXT,
    created_by UUID NOT NULL REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_sentence_test_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED'))
);

CREATE TABLE test_sentences (
    id UUID PRIMARY KEY,
    test_id UUID NOT NULL REFERENCES sentence_tests(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    kind VARCHAR(20) NOT NULL,
    reference_text TEXT NOT NULL,
    assistance VARCHAR(20) NOT NULL,
    CONSTRAINT uk_test_sentence_position UNIQUE (test_id, position),
    CONSTRAINT ck_test_sentence_kind CHECK (kind IN ('DICTATED', 'FREE')),
    CONSTRAINT ck_test_sentence_assistance CHECK (assistance IN ('ASSISTED', 'UNASSISTED'))
);

CREATE TABLE test_assignments (
    id UUID PRIMARY KEY,
    test_id UUID NOT NULL REFERENCES sentence_tests(id),
    student_id UUID NOT NULL REFERENCES student_users(id),
    classroom_id UUID REFERENCES classrooms(id),
    assigned_by UUID NOT NULL REFERENCES researcher_users(id),
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_test_assignment UNIQUE (test_id, student_id)
);

CREATE TABLE test_attempts (
    id UUID PRIMARY KEY,
    test_id UUID NOT NULL REFERENCES sentence_tests(id),
    student_id UUID NOT NULL REFERENCES student_users(id),
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    cancel_reason VARCHAR(30),
    app_version VARCHAR(80),
    backend_version VARCHAR(80),
    model_version VARCHAR(160),
    incident_count INTEGER NOT NULL DEFAULT 0,
    excluded_at TIMESTAMP WITH TIME ZONE,
    exclusion_reason VARCHAR(500),
    excluded_by UUID REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_attempt_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_attempt_cancel_reason CHECK (cancel_reason IS NULL OR cancel_reason IN ('ABANDONED', 'TECHNICAL_PROBLEM', 'INTERRUPTED'))
);

-- Un solo intento en curso por alumno, en cualquier prueba.
CREATE UNIQUE INDEX uk_attempt_in_progress_per_student ON test_attempts(student_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_attempts_test ON test_attempts(test_id);

CREATE TABLE test_responses (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES test_attempts(id) ON DELETE CASCADE,
    sentence_id UUID NOT NULL REFERENCES test_sentences(id),
    position INTEGER NOT NULL,
    pressed_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    first_key_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    duration_from_first_key_ms BIGINT,
    duration_from_start_ms BIGINT,
    final_text TEXT,
    skipped BOOLEAN NOT NULL DEFAULT FALSE,
    suggestions_offered INTEGER NOT NULL DEFAULT 0,
    suggestions_accepted INTEGER NOT NULL DEFAULT 0,
    suggestions_rejected INTEGER NOT NULL DEFAULT 0,
    suggestions_undone INTEGER NOT NULL DEFAULT 0,
    auto_error_count INTEGER,
    auto_error_detail TEXT,
    annotated_error_count INTEGER,
    annotated_by UUID REFERENCES researcher_users(id),
    annotated_at TIMESTAMP WITH TIME ZONE,
    completion_key UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_response_attempt_position UNIQUE (attempt_id, position),
    CONSTRAINT uk_response_completion_key UNIQUE (completion_key),
    CONSTRAINT ck_response_first_key_duration CHECK (duration_from_first_key_ms IS NULL OR duration_from_first_key_ms > 0)
);

ALTER TABLE correction_sessions ADD COLUMN test_response_id UUID REFERENCES test_responses(id) ON DELETE SET NULL;
CREATE INDEX idx_correction_sessions_test_response ON correction_sessions(test_response_id);

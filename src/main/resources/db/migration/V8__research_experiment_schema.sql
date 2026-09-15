CREATE TABLE research_studies (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL CONSTRAINT uk_study_code UNIQUE,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    next_participant_number INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_study_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED'))
);

CREATE TABLE study_protocols (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES research_studies(id),
    version INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_protocol_version UNIQUE (study_id, version),
    CONSTRAINT ck_protocol_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE protocol_tasks (
    id UUID PRIMARY KEY,
    protocol_id UUID NOT NULL REFERENCES study_protocols(id) ON DELETE CASCADE,
    variant VARCHAR(20) NOT NULL,
    prompt_text TEXT NOT NULL,
    CONSTRAINT uk_protocol_variant UNIQUE (protocol_id, variant),
    CONSTRAINT ck_task_variant CHECK (variant IN ('TASK_A', 'TASK_B'))
);

CREATE TABLE study_participants (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES research_studies(id),
    student_id UUID REFERENCES student_users(id),
    pseudonym VARCHAR(20) NOT NULL,
    sequence VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_study_pseudonym UNIQUE (study_id, pseudonym),
    CONSTRAINT uk_study_student UNIQUE (study_id, student_id),
    CONSTRAINT ck_participant_sequence CHECK (sequence IN ('ASSISTED_FIRST', 'UNASSISTED_FIRST'))
);

CREATE TABLE experiment_runs (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES study_participants(id),
    protocol_id UUID NOT NULL REFERENCES study_protocols(id),
    task_id UUID NOT NULL REFERENCES protocol_tasks(id),
    condition VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    access_code_hash VARCHAR(64),
    access_code_expires_at TIMESTAMP WITH TIME ZONE,
    redeemed_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    final_text TEXT,
    completion_key UUID CONSTRAINT uk_runs_completion_key UNIQUE,
    app_version VARCHAR(80),
    backend_version VARCHAR(80),
    model_version VARCHAR(160),
    incident_count INTEGER NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500),
    excluded_at TIMESTAMP WITH TIME ZONE,
    exclusion_reason VARCHAR(500),
    excluded_by UUID REFERENCES researcher_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_run_condition CHECK (condition IN ('ASSISTED', 'UNASSISTED')),
    CONSTRAINT ck_run_status CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'TECHNICAL_FAILURE')),
    CONSTRAINT ck_run_duration CHECK (duration_ms IS NULL OR duration_ms > 0)
);

CREATE TABLE research_audit_events (
    id UUID PRIMARY KEY,
    researcher_id UUID NOT NULL REFERENCES researcher_users(id),
    study_id UUID REFERENCES research_studies(id),
    action VARCHAR(80) NOT NULL,
    target_id UUID,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE correction_sessions
    ADD COLUMN experiment_run_id UUID REFERENCES experiment_runs(id) ON DELETE SET NULL;

CREATE INDEX idx_protocols_study ON study_protocols(study_id, version DESC);
CREATE INDEX idx_participants_study ON study_participants(study_id, pseudonym);
CREATE INDEX idx_runs_participant ON experiment_runs(participant_id, created_at);
CREATE UNIQUE INDEX uk_runs_access_code_hash ON experiment_runs(access_code_hash) WHERE access_code_hash IS NOT NULL;
CREATE UNIQUE INDEX uk_runs_one_open_per_participant ON experiment_runs(participant_id) WHERE status IN ('PENDING', 'ACTIVE');
CREATE INDEX idx_correction_experiment ON correction_sessions(experiment_run_id);
CREATE INDEX idx_research_audit_study ON research_audit_events(study_id, created_at DESC);

CREATE TABLE student_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    institution VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE teacher_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    email VARCHAR(160) NOT NULL UNIQUE,
    phone VARCHAR(30),
    institution VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE teacher_student_links (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL REFERENCES teacher_users(id),
    student_id UUID NOT NULL REFERENCES student_users(id),
    encrypted_student_real_name TEXT NOT NULL,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    last_access_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_teacher_student UNIQUE (teacher_id, student_id)
);

CREATE TABLE correction_sessions (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL REFERENCES student_users(id),
    original_text TEXT NOT NULL,
    corrected_text TEXT,
    corrections_count INTEGER NOT NULL DEFAULT 0,
    suggestions_json TEXT,
    confidence DOUBLE PRECISION,
    selected_suggestion TEXT,
    accepted_correction BOOLEAN,
    response_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE word_corrections (
    id UUID PRIMARY KEY,
    correction_session_id UUID NOT NULL REFERENCES correction_sessions(id) ON DELETE CASCADE,
    original_word VARCHAR(160) NOT NULL,
    corrected_word VARCHAR(160) NOT NULL,
    error_type VARCHAR(30) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    start_position INTEGER NOT NULL,
    end_position INTEGER NOT NULL
);

CREATE TABLE monthly_reports (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL REFERENCES student_users(id),
    month DATE NOT NULL,
    total_submissions INTEGER NOT NULL,
    total_accepted INTEGER NOT NULL,
    acceptance_rate DOUBLE PRECISION NOT NULL,
    frequent_errors_json TEXT,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_monthly_report UNIQUE (student_id, month)
);

CREATE TABLE privacy_consents (
    id UUID PRIMARY KEY,
    student_id UUID REFERENCES student_users(id),
    teacher_id UUID REFERENCES teacher_users(id),
    consent_type VARCHAR(80) NOT NULL,
    accepted BOOLEAN NOT NULL,
    document_version VARCHAR(40) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_ip VARCHAR(64),
    user_agent VARCHAR(255),
    CONSTRAINT ck_privacy_consent_user CHECK (
        (student_id IS NOT NULL AND teacher_id IS NULL)
        OR (student_id IS NULL AND teacher_id IS NOT NULL)
    )
);

CREATE INDEX idx_sessions_student_date ON correction_sessions(student_id, created_at DESC);
CREATE INDEX idx_sessions_student_month ON correction_sessions(student_id, (DATE_TRUNC('month', created_at AT TIME ZONE 'UTC')));
CREATE INDEX idx_word_corrections_session ON word_corrections(correction_session_id);
CREATE INDEX idx_word_corrections_type ON word_corrections(error_type);
CREATE INDEX idx_teacher_student_teacher ON teacher_student_links(teacher_id) WHERE deleted_at IS NULL;

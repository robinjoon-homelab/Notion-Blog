CREATE TABLE migration_job (
    job_id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    data_source_id TEXT NOT NULL,
    title_property_id TEXT NOT NULL,
    summary_property_id TEXT NOT NULL,
    tags_property_id TEXT NOT NULL,
    authored_at_property_id TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    paused BOOLEAN NOT NULL,
    leased_position INTEGER,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    CHECK (
        (leased_position IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (leased_position IS NOT NULL AND leased_position >= 0 AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE TABLE migration_job_item (
    job_id UUID NOT NULL REFERENCES migration_job(job_id),
    position INTEGER NOT NULL CHECK (position >= 0),
    source_id TEXT NOT NULL,
    external_id TEXT NOT NULL,
    source_title TEXT NOT NULL,
    changed_version BIGINT NOT NULL DEFAULT 0 CHECK (changed_version >= 0),
    result_kind TEXT,
    result_post_id UUID,
    result_failure_kind TEXT,
    result_diagnostics TEXT,
    PRIMARY KEY (job_id, position),
    UNIQUE (job_id, source_id, external_id),
    CHECK (
        (result_kind IS NULL AND result_post_id IS NULL AND result_failure_kind IS NULL AND result_diagnostics IS NULL)
        OR (result_kind IS NOT NULL AND (
            (result_kind = 'PARTIAL' AND result_post_id IS NOT NULL AND result_failure_kind IS NULL AND result_diagnostics IS NOT NULL)
            OR (result_kind IN ('IMPORTED', 'SKIPPED') AND result_post_id IS NOT NULL AND result_failure_kind IS NULL AND result_diagnostics IS NULL)
            OR (result_kind = 'FAILED' AND result_post_id IS NULL AND result_failure_kind IS NOT NULL)
        ))
    )
);

CREATE INDEX migration_job_recent_idx ON migration_job (created_at, job_id);
CREATE INDEX migration_job_expired_pause_idx ON migration_job (lease_expires_at) WHERE paused AND lease_expires_at IS NOT NULL;
CREATE INDEX migration_job_pending_idx ON migration_job_item (job_id, position) WHERE result_kind IS NULL;
CREATE INDEX migration_job_item_changed_idx ON migration_job_item (job_id, changed_version);
CREATE INDEX migration_job_item_kind_idx ON migration_job_item (job_id, result_kind);

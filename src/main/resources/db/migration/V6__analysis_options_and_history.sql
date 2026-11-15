-- Additive: V1-V5 and historical results remain unchanged.
CREATE TABLE surface_candidate_result (
    analysis_run_id uuid NOT NULL REFERENCES analysis_run(analysis_run_id),
    candidate_index integer NOT NULL CHECK (candidate_index > 0),
    candidate_code text NOT NULL UNIQUE,
    proposed_landing_at timestamptz NOT NULL,
    surface_finished_at timestamptz NOT NULL,
    window_start_at timestamptz NOT NULL,
    window_end_at timestamptz NOT NULL,
    status text NOT NULL,
    surface_status text NOT NULL,
    launch_at timestamptz,
    tli_at timestamptz,
    lunar_arrival_at timestamptz,
    transfer_duration_hours double precision,
    solver_status text NOT NULL,
    position_residual_km double precision,
    model text NOT NULL,
    reasons jsonb NOT NULL,
    solver_details jsonb,
    PRIMARY KEY (analysis_run_id, candidate_index),
    CHECK (surface_finished_at > proposed_landing_at),
    CHECK (window_end_at >= window_start_at)
);

CREATE TABLE launch_option_batch (
    analysis_run_id uuid PRIMARY KEY REFERENCES analysis_run(analysis_run_id),
    calculated_at timestamptz NOT NULL,
    engine_sha256 char(64) NOT NULL,
    model text NOT NULL,
    assumptions jsonb NOT NULL,
    touchdown_at timestamptz,
    status text NOT NULL CHECK (status IN ('READY', 'NO_WINDOW', 'DATA_UNAVAILABLE'))
);
CREATE TABLE launch_option_result (
    analysis_run_id uuid NOT NULL REFERENCES launch_option_batch(analysis_run_id),
    launch_site_id bigint NOT NULL,
    site_code text NOT NULL,
    latitude_degrees double precision NOT NULL,
    longitude_degrees_east double precision NOT NULL,
    status text NOT NULL CHECK (status IN ('GEOMETRIC_ONLY', 'NO_CANDIDATE')),
    candidate_code text,
    launch_at timestamptz,
    tli_at timestamptz,
    lunar_arrival_at timestamptz,
    proposed_landing_at timestamptz,
    PRIMARY KEY (analysis_run_id, launch_site_id),
    UNIQUE (analysis_run_id, candidate_code),
    CHECK ((status = 'NO_CANDIDATE' AND candidate_code IS NULL AND launch_at IS NULL)
        OR (status = 'GEOMETRIC_ONLY' AND candidate_code IS NOT NULL AND launch_at IS NOT NULL
            AND tli_at IS NOT NULL AND lunar_arrival_at IS NOT NULL AND proposed_landing_at IS NOT NULL))
);

CREATE TABLE trajectory_run (
    trajectory_run_id uuid PRIMARY KEY,
    analysis_run_id uuid NOT NULL REFERENCES analysis_run(analysis_run_id),
    launch_site_id bigint NOT NULL,
    candidate_code text NOT NULL,
    execution_status text NOT NULL CHECK (execution_status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    created_at timestamptz NOT NULL,
    finished_at timestamptz,
    expires_at timestamptz NOT NULL,
    engine_sha256 char(64) NOT NULL,
    input_snapshot jsonb NOT NULL,
    status text,
    reason text,
    solver_status text,
    model text,
    frame text,
    origin text,
    segment text,
    arrival_target text,
    nominal_sample_step_seconds integer,
    position_residual_km double precision,
    tli_delta_v_km_s double precision,
    source_records jsonb NOT NULL DEFAULT '[]',
    FOREIGN KEY (analysis_run_id, candidate_code) REFERENCES launch_option_result(analysis_run_id, candidate_code)
);
CREATE INDEX trajectory_run_analysis_created_idx ON trajectory_run(analysis_run_id, created_at);
CREATE INDEX trajectory_run_expiry_idx ON trajectory_run(expires_at) WHERE execution_status = 'RUNNING';
CREATE TABLE trajectory_sample (
    trajectory_run_id uuid NOT NULL REFERENCES trajectory_run(trajectory_run_id),
    sample_index integer NOT NULL CHECK (sample_index >= 0),
    observed_at timestamptz NOT NULL,
    elapsed_seconds bigint NOT NULL CHECK (elapsed_seconds >= 0),
    position_x_km double precision NOT NULL,
    position_y_km double precision NOT NULL,
    position_z_km double precision NOT NULL,
    velocity_x_km_s double precision NOT NULL,
    velocity_y_km_s double precision NOT NULL,
    velocity_z_km_s double precision NOT NULL,
    PRIMARY KEY (trajectory_run_id, sample_index),
    UNIQUE (trajectory_run_id, observed_at)
);

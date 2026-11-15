-- The empty key identifies the original first-touchdown batch and keeps all existing candidate IDs.
ALTER TABLE launch_option_result DROP CONSTRAINT launch_option_result_analysis_run_id_fkey;
ALTER TABLE launch_option_batch
    ADD COLUMN selection_key text NOT NULL DEFAULT '',
    ADD COLUMN surface_candidate_id text,
    ADD COLUMN surface_end_at timestamptz,
    DROP CONSTRAINT launch_option_batch_pkey,
    ADD PRIMARY KEY (analysis_run_id, selection_key),
    ADD UNIQUE (analysis_run_id, surface_candidate_id),
    ADD CHECK (selection_key = '' OR (surface_candidate_id IS NOT NULL AND selection_key = surface_candidate_id)),
    ADD CHECK ((surface_candidate_id IS NULL AND surface_end_at IS NULL) OR
               (surface_candidate_id IS NOT NULL AND touchdown_at IS NOT NULL AND surface_end_at IS NOT NULL AND surface_end_at > touchdown_at));

ALTER TABLE launch_option_result
    ADD COLUMN selection_key text NOT NULL DEFAULT '',
    DROP CONSTRAINT launch_option_result_pkey,
    ADD PRIMARY KEY (analysis_run_id, selection_key, launch_site_id),
    ADD FOREIGN KEY (analysis_run_id, selection_key) REFERENCES launch_option_batch (analysis_run_id, selection_key);

CREATE TABLE launch_touchdown_date (
    analysis_run_id uuid NOT NULL,
    touchdown_date date NOT NULL,
    selection_key text NOT NULL,
    PRIMARY KEY (analysis_run_id, touchdown_date),
    FOREIGN KEY (analysis_run_id, selection_key) REFERENCES launch_option_batch (analysis_run_id, selection_key)
);

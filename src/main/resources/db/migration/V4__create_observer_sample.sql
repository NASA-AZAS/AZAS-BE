CREATE TABLE observer_sample (
    landing_site_id bigint NOT NULL REFERENCES landing_site (landing_site_id) ON DELETE RESTRICT,
    observed_at timestamptz NOT NULL,
    sun_azimuth_degrees double precision NOT NULL CHECK (sun_azimuth_degrees BETWEEN 0 AND 360),
    sun_elevation_degrees double precision NOT NULL CHECK (sun_elevation_degrees BETWEEN -90 AND 90),
    earth_azimuth_degrees double precision NOT NULL CHECK (earth_azimuth_degrees BETWEEN 0 AND 360),
    earth_elevation_degrees double precision NOT NULL CHECK (earth_elevation_degrees BETWEEN -90 AND 90),
    PRIMARY KEY (landing_site_id, observed_at),
    CONSTRAINT observer_sample_utc_grid CHECK (
        isfinite(observed_at) AND
        observed_at = date_bin(interval '10 minutes', observed_at, timestamptz '2000-01-01 00:00:00+00')
    )
);

COMMENT ON TABLE observer_sample IS 'ver2 후보지별 10분 UTC 격자 태양·지구 중심의 현지 방위각 및 고도각(도)';

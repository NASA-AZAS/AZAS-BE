-- Run in DataGrip after importing all three CSVs into an empty terrain dataset.
-- Explicit CSV IDs do not advance PostgreSQL identity sequences.
SELECT setval(pg_get_serial_sequence('public.terrain_dataset', 'terrain_dataset_id'),
              (SELECT max(terrain_dataset_id) FROM public.terrain_dataset));
SELECT setval(pg_get_serial_sequence('public.landing_site', 'landing_site_id'),
              (SELECT max(landing_site_id) FROM public.landing_site));

SELECT (SELECT count(*) FROM public.terrain_dataset) AS datasets,
       (SELECT count(*) FROM public.landing_site) AS sites,
       (SELECT count(*) FROM public.horizon_sample) AS samples;
-- Expected: 1 | 27 | 97200

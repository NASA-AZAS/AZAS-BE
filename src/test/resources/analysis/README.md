# LLSN regression fixtures

These are test-only extracts of the user-supplied `LLSN-Java21` bundle.
They do not establish an independent scientific benchmark and are not used as a runtime cache.

- `llsn-golden.json`: both scenarios (578 epochs) from `results/analysis/analysis_result.json`, retaining upstream angles, horizons, clearances, states and metrics. The original result SHA-256 is embedded in the file.
- `sun-observer.json` and `earth-observer.json`: original observer responses for 2026-12-01 through 2026-12-03, 10-minute cadence. `fixture-provenance.json` retains original filenames, queries and hashes.
- Terrain samples come from the already versioned landing-screen resource bundle; no duplicate DEM or terrain seed is included here.

Upstream source SHA-256 at integration:

| File | SHA-256 |
| --- | --- |
| Analysis.java | `0a9784a8fc8a033305196a8bef314bef1286c9a9648b83871449f1e1f9ca6d85` |
| Terrain.java | `cbd0ac889eb426c06298845e1a0468171498e1cc1aa8bf986b73cd58344927b1` |
| Horizons.java | `450a060b9a0f7ea294daa231a367a6e5461a8a70e42c9cf87d4b264abaf8c34c` |

Only the visibility calculation is integrated. File/CLI orchestration and Horizons cache handling were replaced by the backend entry adapter and HTTP client. UTC daily aggregation is an integration addition and is checked against upstream per-epoch states.

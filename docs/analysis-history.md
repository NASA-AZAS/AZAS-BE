# Analysis options and saved runs

The [API guide](analysis-api.md) follows a mission from landing site selection to a saved trajectory. This reference covers the optional search inputs, what each run preserves and how older runs are read. [Database diagram source](erd.dbml)

## Search options

Send `POST /api/v1/landing-sites/{id}/date-opportunities` for one lunar site. The site ID comes from the URL. The search starts at `2027-01-01T00:00:00Z`. The start is fixed, so passing a later `searchStartUtc` to select a landing day returns HTTP 400. Use `missionDeadlineUtc` to set the end. A date alone means 23:59 UTC on that date.

| Option | Default and allowed values | Effect |
| --- | --- | --- |
| `surfaceDurationHours` | 48 hours | Required continuous time on the surface. The query parameter, when supplied, takes precedence over the JSON value. |
| `surfaceCriteria.sun` and `surfaceCriteria.earth` | Both default to `VISIBLE`. Each accepts `ANY`, `VISIBLE` or `OCCULTED`. | Find continuous runs of observations that satisfy the requested view of the Sun and Earth. |
| `surfaceCriteria.unknownPolicy` | `RECORD_UNKNOWN` by default, or `EXCLUDE_WITH_RECORD` | Sets how to handle uncertain samples. The current archive only stores finite angles. A missing sample fails the run. |
| `timeStepMinutes` | 10 by default, from 1 to 1,440 | Time between candidate touchdown evaluations. The underlying observations remain ten minutes apart. |
| `maxCandidates` | 50 by default, from 1 to 10,000 | Maximum number of individually evaluated candidates. All landing windows remain available. |
| `refinementPolicy` | `minStepMinutes=min(10,timeStepMinutes)` and `maxDepth=0` | When neighbouring candidates have different outcomes or solver states, evaluate their midpoint on a whole-minute grid. |
| `networkMode` | `ONLINE` by default, or `CACHE_ONLY` | Surface conditions use stored observations. Online transfer calculations can request external vectors. |

The minimum refinement step must be at least one minute and no longer than the candidate step. Maximum depth ranges from 0 to 12. Unsupported keys, invalid enum values and incomplete transfer inputs return HTTP 400 before a job is accepted. The HTTP API identifies the site in the path. The offline CLI's site lists, per-site deadlines and file options do not apply here.

This request finds up to seven touchdown candidates for one hour of activity with Earth below the horizon. The Sun may be visible or hidden.

```json
{
  "missionDeadlineUtc": "2027-01-31",
  "surfaceDurationHours": 1,
  "surfaceCriteria": {
    "sun": "ANY",
    "earth": "OCCULTED",
    "unknownPolicy": "RECORD_UNKNOWN"
  },
  "timeStepMinutes": 60,
  "maxCandidates": 7,
  "refinementPolicy": {"minStepMinutes": 10, "maxDepth": 2}
}
```

Daily Sun and Earth visibility fractions always describe what was visible, regardless of the search criteria. Evaluating candidates more frequently cannot increase the resolution of the archived observations or verify a short event between samples.

## Optional transfer inputs

To assess a transfer for an individual touchdown candidate, supply all five options below. Time ranges use `min`, `max` and `step` in hours. Each value must be expressible in whole minutes. A range supports up to 100 intervals and includes the end value.

| Option | Input |
| --- | --- |
| `departureState` | Departure epoch (`epochUtc`), `frame="ICRF"`, `center="EARTH"`, three-component position in km (`positionKm`), velocity in km/s (`velocityKmS`) and Earth's gravitational parameter (`earthMuKm3S2`). |
| `arrivalTargetRule` | `type="MOON_RELATIVE_ICRF_OFFSET"`, a three-component `offsetKm` and `minimumAltitudeKm`. |
| `transferDurationHours` | Positive flight time from departure toward the Moon to lunar arrival. |
| `preLandingDelayHours` | Non-negative time between lunar arrival and touchdown. |
| `lambert` | `branch="SHORT_WAY"` or `"LONG_WAY"` and `positionToleranceKm` greater than zero and at most 10 km. |

Optionally include `launchToTliDelayHours` with the transfer inputs. If it is omitted, `launchUtc` is null. Translunar injection (TLI) is the departure burn from Earth orbit and occurs after launch. The initial position must be outside Earth's radius, and the arrival offset must clear the lunar radius plus the minimum altitude. The departure epoch must be a whole-second UTC time within 366 days of the search start.

A full candidate evaluation requests Earth (399) and Moon (301) vectors at both TLI and lunar arrival, four lookups in total. It uses Earth-only propagation and a zero-revolution Lambert solution. The model returns `trajectoryVerified=false`. In `CACHE_ONLY` mode there is no local transfer vector archive, so the result is `UNKNOWN / SOURCE_OR_NUMERICAL_ERROR` with reason `VECTOR_ARCHIVE_UNAVAILABLE` and no external request. A departure before the search start is `EXCLUDED` before any vector lookup.

Each candidate records touchdown and activity end, its assessment, launch/TLI/arrival times when available, transfer duration, solver state and residual, model and reasons. This optional assessment uses the supplied departure state. The separate launch-site and trajectory endpoints assume a fixed parking orbit and use the selected touchdown. Launch options default to the first qualifying touchdown.

## What a run saves

| Table | Contents |
| --- | --- |
| `analysis_run` | Run ID, effective request including defaults, input and engine fingerprints, source records and state. |
| `landing_opportunity_result` | Every touchdown window, independent of the candidate limit. |
| `surface_candidate_result` | Individual evaluated touchdown times, transfer assessments and numerical results. |
| `launch_option_batch` | Model, assumptions, engine fingerprint, touchdown and calculation time. |
| `launch_touchdown_date` | The launch batch selected for a given analysis run and UTC date. |
| `launch_option_result` | A snapshot of each launch site's coordinates and candidate, including sites with no candidate. |
| `trajectory_run` | A new ID for each calculation, selected inputs, engine fingerprint, state, residual, velocity change and sources. |
| `trajectory_sample` | Sample order, UTC time, elapsed time and XYZ position and velocity for one trajectory. |

A newly saved qualifying window records `SURFACE_CRITERIA_SATISFIED` in its decision reasons. Read `windows[].surfaceStatus` for the window verdict. Individual candidates have their own reasons. For example, missing transfer inputs produce `TRANSFER_INPUTS_MISSING`. Historical reasons from before change #34 are preserved.

New analyses save launch options in the same transaction that completes the analysis. Updating a launch site's coordinates later leaves that snapshot intact. If the launch catalogue was empty, the surface analysis can succeed while the launch batch records `DATA_UNAVAILABLE`. Load the catalogue and start a new analysis to use it.

In schema version 7, the first touchdown batch has an empty `selection_key`. Selecting a different surface candidate uses the run's saved launch site coordinates and stores the batch under a separate key. A date selection and its batch are saved in the same transaction. Each saved trajectory reads `surfaceCandidateId` from its input snapshot. Trajectories saved before version 7 return `null` for that field.

Runs from before schema version 6 reconstruct their surface candidates from the saved windows. The first launch-options lookup calculates and saves a snapshot using the catalogue and calculation time at that first lookup. Subsequent lookups reuse it. `calculatedAt` and `engineSha256` identify when and how the options were calculated. Historical trajectories exist only when a calculation was saved.

## Retrieve a saved flight path

```text
POST /api/v1/analyses/{runId}/launch-options/{candidateId}/trajectory
GET  /api/v1/analyses/{runId}/trajectories
GET  /api/v1/analyses/{runId}/trajectories/{trajectoryId}
```

The POST returns synchronously with HTTP 200. Each calculation has a `trajectoryRunId` and tracks submission, start and finish separately in `createdAt`, `startedAt` and `finishedAt`. `PENDING` means queued and `RUNNING` means the work has started. `engineSha256` identifies the implementation.

The list returns the latest 100 calculations. The detail GET reads stored results and samples without contacting Horizons. A new POST recalculates the trajectory. A failed external request leaves a record with an empty sample list. `SUCCEEDED` means the calculation finished. Read `status`, `lambertStatus` and the verification flags to interpret the outcome. [Trajectory response examples](analysis-api.md#calculate-and-reopen-a-trajectory)

Surface analysis and trajectory jobs share one worker, two queue slots and a 120-second deadline that includes queue time. Expired workers cannot overwrite later results. Jobs interrupted by a restart are marked as failed after expiry.

## Database compatibility and verification

Flyway V6 adds tables and indexes while preserving V1–V5 checksums, imported CSVs and earlier results. New result rows come from API requests. The migration uses the existing manual CSV import and Compose setup and requires no new environment variables, libraries or deployment files. Pull requests run CI. Production deployment follows the [release workflow](deployment.md).

CI uses recorded fixtures and a loopback HTTP server. The tests compare all 13 launch sites, the Kennedy trajectory's 701 samples, short- and long-way transfers, a custom gravitational parameter and backward propagation. If the original CLI is available locally, set its path to compare the three surface criteria and variations in candidate spacing and limits.

```powershell
$env:LLSN_VER2_HOME = 'C:\path\to\LLSN-Java21-ver2'
.\gradlew.bat --no-daemon check bootJar
```

These checks measure agreement with the reference implementation. Independent validation of the physical model is a separate task.

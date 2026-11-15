# Lunar landing and visibility API

Use this API to choose a lunar site, find a time to land, look up possible launches from Earth and explore the sky after touchdown. All times are in UTC. The examples below use the common `/api/v1` prefix. You can try the requests and inspect their response schemas in Swagger. [Live API explorer](https://api.azas.n-e.kr/docs) · [Local explorer](http://127.0.0.1:8000/docs)

## Choose a site

| Request | What you get |
| --- | --- |
| `GET /api/v1/landing-sites` | Summaries of the 27 sites in the default terrain dataset. Pass `terrainDatasetId` to choose a different dataset. |
| `GET /api/v1/landing-sites/{id}` | Coordinates, surface elevation, viewing height, terrain screening status, dataset version and average visibility for one site. |

Use the numeric `landingSiteId` from the list as `{id}`. A code such as `Site01` is the name used in the source data. The default dataset is `landing-screen / 2026-11-14-v1`. The server keeps the 3,600 measurements of each site's horizon in the database.

You can preview visibility before submitting an analysis. `meanDailyVisibility` gives the average percentage of each UTC day that the Sun and Earth are visible, calculated separately across all 731 days in 2027 and 2028. The site preview uses the site's reference viewing height and agrees with the post-landing view at that height. If the observations or horizon data are incomplete, the preview returns `null` for this field.

## Find landing windows before a deadline

Submit `POST /api/v1/landing-sites/{id}/date-opportunities`. The body below uses the default requirement of 48 consecutive hours with the Sun and Earth both in view. The frontend can use `2029-12-31` as the deadline to search through the end of 2029.

```json
{
  "missionDeadlineUtc": "2027-01-31",
  "surfaceDurationHours": 48
}
```

The search begins at `2027-01-01T00:00:00Z`. Give a deadline in 2027–2029 as a date or an ISO UTC timestamp with whole-second precision. A date alone means 23:59 UTC that day. The server reads observations every ten minutes. A missing observation in the requested period fails the run with `OBSERVER_DATA_INCOMPLETE`.

At each observation LAMP compares the centre of each body with the local terrain horizon in that direction. Above the horizon counts as visible. A touchdown qualifies if the requested conditions hold for the entire surface activity. Daily visibility always describes the observations, even when you search for a period when Earth or the Sun is hidden. The last day's statistics stop at the deadline. [Search options and limits](analysis-history.md#search-options)

The default viewing height is one metre. To change it or request a shorter stay, use the query parameters as shown here.

```http
POST /api/v1/landing-sites/19/date-opportunities?observerHeightAboveSurfaceMeters=2.5&surfaceDurationHours=24
Content-Type: application/json

{"missionDeadlineUtc":"2027-01-31"}
```

Height must be finite and at least zero. The query value for activity duration takes precedence over the JSON body. If both omit it, LAMP uses 48 hours. When the requested height differs from the reference height, the model adjusts the horizon angle using the horizontal distance to the original blocking terrain. It keeps that obstruction and the archived directions to the Sun and Earth fixed. The run diagnostics and post-landing model identify this approximation as `FIXED_BLOCKER_HEIGHT_APPROXIMATION`.

### Check progress and read the result

The POST returns HTTP 202. Follow its `Location` header or call `GET /api/v1/analyses/{runId}` using the returned analysis ID. Poll about every one to two seconds. `PENDING` is the state at acceptance. A worker may already have started by the time the response arrives. After `RUNNING`, the run finishes with `SUCCEEDED` or `FAILED`. Check `failureCode` on failure. Example messages in this guide are translated into English. Clients should use the stable `code` for program logic. [Response conventions](api-conventions.md#response-format)

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "Request succeeded.",
  "data": {
    "analysisRunId": "8b03b44d-c077-4e74-a6bb-bb24c50d7df0",
    "executionStatus": "PENDING"
  }
}
```

Once the run succeeds, call `GET /api/v1/analyses/{runId}/date-opportunities`.

| Field | How to read it |
| --- | --- |
| `windows` | Each window gives a surface verdict (`surfaceStatus`). Touchdown can occur from `startUtc` through `endUtc`, inclusive. The latest touchdown is the last supporting observation (`supportEndUtc`) minus the required activity duration. |
| `earliestTouchdownUtc` | Start of the first `SATISFIED` window, or `null` if none qualifies. |
| `candidates` | Individual touchdown times evaluated within qualifying windows. `proposedLandingUtc` is the time of a specific touchdown. `candidateId` selects its post-landing view. Read `status`, `lambertStatus` and `reasons` for the transfer assessment. |

A window can satisfy the surface conditions even when the request supplied no transfer inputs. In that case its candidate can have `status=ENVIRONMENT_ONLY`, `lambertStatus=NOT_EVALUATED` and reason `TRANSFER_INPUTS_MISSING`.

The default limit is 50 candidates. If the next evaluation would exceed `maxCandidates`, the overall result is `TRUNCATED`. You still get every landing window and the daily results. Before success, or after a failed run, the earliest touchdown is null and both lists are empty. Check the run state before interpreting an empty list.

## Get daily visibility

Use `GET /api/v1/analyses/{runId}/daily-results` for the evaluated days in chronological order, or add `?date=2027-01-19` for one UTC day. The response includes visibility ratios for the Sun, Earth and both together, how much of the period could be assessed, the longest occultations, quality flags and visible intervals. Unavailable numbers are `null`. A pending or failed run, or a day outside the evaluated period, returns an empty list.

For a separate, **hourly visibility analysis**, submit `POST /api/v1/landing-sites/{id}/analyses` with an inclusive range of 1–31 UTC dates. The example covers three days from midnight on 1 December up to, but excluding, midnight on 4 December. The final boundary observation has zero time weight. Use the same progress and daily-result endpoints.

```json
{
  "startDate": "2026-12-01",
  "endDate": "2026-12-03"
}
```

The hourly model treats a body at or below the measured skyline as `OCCULTED`. Above it, the result is `UNKNOWN` when distant terrain has not been verified. A successful run can therefore return `overallResultStatus=PARTIAL` with no confirmed visible windows. Visibility ratios use only the time for which the model can determine visibility and are null when that time is zero. Unknown samples break an occultation interval. Daily summaries split at midnight UTC. Visibility of Earth's centre alone does not establish a radio link.

## See the sky after touchdown

After a successful landing-window analysis, choose a `candidateId` from its touchdown candidates. Use that ID with the **same analysis**. This also works for candidates reconstructed from runs created before schema version 6.

```http
GET /api/v1/analyses/{runId}/surface-candidates/{candidateId}/post-landing
```

The response uses the structure of the [reference observation file](../src/test/resources/analysis/post_landing.json). `siteId` is the lunar site's source code. `touchdownUtc` and `surfaceEndUtc` bound the selected activity.

| Field | Meaning |
| --- | --- |
| `samples[].earth` and `samples[].sun` | The direction of each body's centre. Azimuth is measured clockwise from local north and elevation from the local horizontal, both in degrees. |
| `terrainHorizonDeg`, `visible` and `jointVisible` | A body is visible when its centre is strictly above the interpolated terrain horizon at its azimuth. Equality counts as blocked. Joint visibility requires both bodies. The reference-height model is `BODY_CENTER_LOCAL_TERRAIN`. |
| `samples[].utc`, `elapsedSeconds` and `sampleStepSeconds` | Observations on the original ten-minute UTC grid during the activity, including either boundary if it falls on the grid. Elapsed time starts at touchdown. The first sample can have a positive offset and a short activity can contain no observations. |
| `nextTerrainOccultationUtc` | For each body, the first blocked sample at or after touchdown, even if it occurs after the activity ends. Null if it stays visible through the archive boundary at 00:00 UTC on 1 January 2030. |
| `meanDailyVisibility` | The arithmetic mean of the 731 daily UTC visibility percentages in 2027–2028. The reference ends at 23:59 UTC on its last day, so that day's denominator is 86,340 seconds. |

In the Site01 reference, touchdown at 04:10 UTC on 19 January 2027 starts a 48-hour activity with 289 samples. Earth's first azimuth is 125.088221° and elevation is 5.844995°. Earth is first blocked on 23 January at 10:50 UTC and the Sun on 26 January at 17:30 UTC. Their long-term averages are 50.107440% and 16.269553%, respectively.

A missing run or candidate returns 404 (`ANALYSIS_NOT_FOUND` or `SURFACE_CANDIDATE_NOT_FOUND`). An incompatible analysis type or state returns 409, an invalid horizon returns 422 (`ANALYSIS_TERRAIN_INVALID`), and missing observations return 503 (`OBSERVER_DATA_INCOMPLETE`). This lookup reads stored data without contacting Horizons.

### Draw a smooth sky track

Build the function below once for each body. During playback, pass the seconds since touchdown, `(playbackUtcMs - Date.parse(data.touchdownUtc)) / 1000`. The function unwraps azimuth across 0/360 degrees and interpolates the displayed angles with a ninth-degree Lagrange polynomial through ten neighbouring samples.

```js
function makeSkyTrack(data, bodyKey) {
  const { samples, sampleStepSeconds } = data;
  const bodies = samples.map(sample => sample[bodyKey]);
  const n = bodies.length;
  const wrap = deg => ((deg % 360) + 360) % 360;
  const az = [];
  for (let i = 0; i < n; i++) {
    const raw = bodies[i].azimuthDeg;
    // Unwrap 359° to 1° as 359° to 361° before interpolating.
    az.push(i === 0 ? raw : az[i - 1] + (wrap(raw - bodies[i - 1].azimuthDeg + 180) - 180));
  }
  const el = bodies.map(body => body.elevationDeg);
  const weights = [1, -9, 36, -84, 126, -126, 84, -36, 9, -1];

  return function positionAt(elapsedSeconds) {
    if (n === 0) return null;
    const offset = (elapsedSeconds - samples[0].elapsedSeconds) / sampleStepSeconds;
    const x = Math.max(0, Math.min(n - 1, offset));
    const left = Math.floor(x);
    if (x === left) {
      return { azimuthDeg: bodies[left].azimuthDeg, elevationDeg: el[left] };
    }
    if (n < 10) {
      const f = x - left;
      return { azimuthDeg: wrap(az[left] + f * (az[left + 1] - az[left])),
               elevationDeg: el[left] + f * (el[left + 1] - el[left]) };
    }
    const start = Math.max(0, Math.min(left - 4, n - 10));
    let denominator = 0, azSum = 0, elSum = 0;
    for (let j = 0; j < 10; j++) {
      const index = start + j;
      const w = weights[j] / (x - index);
      denominator += w;
      azSum += w * az[index];
      elSum += w * el[index];
    }
    return { azimuthDeg: wrap(azSum / denominator), elevationDeg: elSum / denominator };
  };
}
```

With one sample, hold that position. With two to nine, interpolate linearly between neighbours. With no samples, omit the track. Outside the sampled interval, hold the nearest endpoint. Between observations, display the last observed `visible` and `jointVisible` values. Leave visibility undetermined before the first sample. Read the next occultation and the long-term averages from the response.

To check the interpolation, `makeSkyTrack(data, "earth")(2700)` gives an azimuth of **125.136829°** and an elevation of **5.825041°** for Site01 at 04:55 UTC on 19 January 2027. Use `"sun"` for the solar track.

## Compare launches for the earliest touchdown

Call `GET /api/v1/analyses/{runId}/launch-options`. The current API uses the **first qualifying touchdown** from that run, including when the result is truncated. It does not accept a different touchdown date or surface candidate. The response identifies the lunar site in `landingSiteId` and the selected instant in `touchdownUtc`.

`launchSites` is ordered by numeric launch site ID. Each entry includes its code, latitude, east-positive longitude and either zero candidates or the earliest launch candidate. Each candidate gives four UTC times: launch, translunar injection (departure from Earth orbit toward the Moon), lunar arrival and touchdown. Use the **launch** candidate ID from this response for the trajectory request.

The `ILLUSTRATIVE_FIXED_PARKING_PLANE` model assumes a 300 km parking orbit with inclination 51.6° and right ascension of the ascending node 0°. It places lunar arrival six hours before touchdown and departure toward the Moon two hours after launch. It looks for a crossing after the analysis starts with a transfer lasting 48–120 hours. For touchdown at `2027-01-19T04:10:00Z`, Kennedy has a candidate at `2027-01-13T23:32:00Z` (`Kennedy-1799883120`) and Vostochny has none. `GEOMETRIC_ONLY` and `launchVerified=false` identify this preliminary result.

| Run or catalogue condition | Response |
| --- | --- |
| Pending, running, failed, or succeeded with no qualifying window | 200 with the run state, `touchdownUtc=null` and `launchSites=[]` |
| Touchdown found and the launch catalogue is available | 200 with all registered sites. Sites without a launch option have `candidates=[]`. |
| Touchdown found but launch site data is missing | 503 `LAUNCH_SITE_DATA_INCOMPLETE` |
| Unknown run or wrong analysis type | 404 `ANALYSIS_NOT_FOUND` or 409 `ANALYSIS_TYPE_MISMATCH` |

## Calculate and reopen a trajectory

| Step | Request | What to use next |
| --- | --- | --- |
| 1 | `POST /api/v1/landing-sites/{id}/date-opportunities` | Get `data.analysisRunId` from the 202 response. Follow `Location` until the run succeeds. |
| 2 | `GET /api/v1/analyses/{runId}/launch-options` | Choose a launch site and take `candidateId` from that site's candidates. The touchdown candidate ID used for the sky view belongs to a different list. |
| 3 | `POST /api/v1/analyses/{runId}/launch-options/{candidateId}/trajectory` | Send an empty body. The server checks the candidate, site coordinates and touchdown, then calculates and saves the result before returning. Save `data.trajectoryRunId`. Each POST creates a new calculation. |
| 4 | `GET /api/v1/analyses/{runId}/trajectories/{trajectoryId}` | Use the saved trajectory ID to reopen the same path without recalculating it or calling Horizons. |

If you do not have the trajectory ID, `GET /api/v1/analyses/{runId}/trajectories` lists the latest 100 submissions, newest first by `createdAt`. Each summary identifies the candidate and trajectory and gives the state, timestamps and reason. A known analysis with no history returns 200 with an empty array. An unknown analysis returns 404 `ANALYSIS_NOT_FOUND`.

| Job state | Calculation outcome | What the client can display |
| --- | --- | --- |
| `PENDING` or `RUNNING` | `status=null` | Show progress and request the same detail URL again. Samples are empty. |
| `SUCCEEDED` | `PRELIMINARY` | Draw the XYZ positions in km from translunar injection (TLI) to arrival. Velocity is in km/s. Samples are 600 seconds apart and include the arrival instant. |
| `SUCCEEDED` | `NO_PRELIMINARY_SOLUTION` | No supported transfer was found. Samples are empty. See `lambertStatus` and `reason`. |
| `FAILED` | `FAILED` | Samples are empty. Read `reason` for the Horizons, validation or numerical failure. |

A calculation or lookup can return HTTP 200 even when the physical solution is unavailable or the calculation failed. On POST, an unknown run or launch candidate returns 404. Incompatible run type or state returns 409, a full queue returns 429 and missing launch site data returns 503. On detail GET, a trajectory outside the specified analysis returns 404 `TRAJECTORY_NOT_FOUND`.

The preliminary model uses JPL Horizons Earth and Moon vectors at lunar arrival. `sourceRecords` records the target, URL, retrieval time and SHA-256 of each response used. The target is 2,000 km from the Moon's centre toward Earth. `positionResidualKm` measures the propagated distance from the target. `tliDeltaVKmS` is the speed change needed to leave the parking orbit. Both `launchVerified` and `trajectoryVerified` are false. The response distinguishes the lunar landing site (`landingSiteId`) from the launch site on Earth (`launchSiteId`).

The reference Kennedy trajectory has 701 samples from `2027-01-14T01:32:00Z` to `2027-01-18T22:10:00Z`. Touchdown occurs later, at `2027-01-19T04:10:00Z`. Its first Earth-centred ICRF sample, rounded to six decimal places, is shown below.

```json
{
  "utc": "2027-01-14T01:32:00Z",
  "elapsedSeconds": 0,
  "positionKm": [-6047.927763, 1759.075112, 2219.400439],
  "velocityKmS": [-3.974492, 8.157424, 5.904744]
}
```

## Computation, limits and errors

```text
Controller → AnalysisJobs / AnalysisStore
               → AnalysisEngine.analyze(Input)
                   → LlsnAnalysisAdapter
                       → HorizonsClient + VisibilityCalculator
               ← AnalysisEngine.Output → save daily results and visibility windows
```

`AnalysisEngine` defines the input and result shared by the API and storage layers. `LlsnAnalysisAdapter` maps database inputs and engine outputs. `VisibilityCalculator` interpolates the horizon, classifies observations and aggregates them by UTC day. `HorizonsClient` handles HTTP and validates coordinates, times and frames. Internal representation changes belong in the adapter. A new metric or a change in meaning can also require changes to the contract and storage.

Each run records the engine version, hashes of the implementation and terrain bundle, and the source URL, retrieval time and response hash. Landing-window runs also record the count and hash of the observations. An hourly visibility request creates a new run each time and makes one fresh Horizons request for each body in normal operation. The server neither caches response bodies nor retries automatically.

Each application process runs one worker and queues two requests. The total time limit is 120 seconds including queue time. External requests have a 10-second connection timeout and a 45-second request timeout. The worker releases the database transaction during external calls, then saves daily results, windows and successful completion together. If saving fails, the transaction rolls back and a separate transaction records the failure.

Since schema version 3, each job has an owner and expiry. The application checks for expired jobs every 15 seconds. An expired job, or a worker that no longer owns it, cannot overwrite a result. A job interrupted by a restart becomes `ANALYSIS_TIMEOUT` after expiry. Worker logs carry the original request and analysis IDs.

| Condition | HTTP response or run outcome |
| --- | --- |
| Unknown site, dataset or analysis ID | HTTP 404 |
| Invalid site ID, dates, reversed range or hourly request over 31 days | HTTP 400 |
| Capacity reached before the terrain lookup | HTTP 429 `ANALYSIS_CAPACITY_EXCEEDED` |
| Upstream HTTP or communication failure | `FAILED / HORIZONS_UNAVAILABLE` |
| Horizons request timed out | `FAILED / HORIZONS_TIMEOUT` |
| Unexpected source frame, timestamps or row count | `FAILED / HORIZONS_INVALID_RESPONSE` |
| Run exceeded its deadline | `FAILED / ANALYSIS_TIMEOUT` |

A status lookup on a failed job returns HTTP 200 with the run state and failure code. Use the request ID to look up the underlying error in the [server logs](logging.md).

## Verification

```powershell
.\gradlew.bat --no-daemon check bootJar
python -m unittest discover -s scripts/ci -v
python scripts/ci/check_docs.py
```

The default tests use recorded observations and 578 timestamps from two reference scenarios. They cover interpolation, classification, daily summaries, HTTP parsing, database commits and rollback, queue limits, cancellation and expiry across restarts. With `LLSN_VER2_HOME` pointing to the original module, `AnalysisApiTests` compares the January 2027 Site01 windows, candidate times and 31 daily visibility results against its offline CLI.

To test a live connection, set `AZAS_LIVE_HORIZONS_TEST=true` and run `test --tests '*HorizonsLiveTests'`. It requests one hour of Sun and Earth observations for Site01. Default CI skips this network-dependent test. On Windows, if JPL fails with `PKIX path building failed` and Windows already trusts the required CA, use the system trust store for that local process.

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
.\scripts\run-local.ps1
```

See [analysis options and saved runs](analysis-history.md) for transfer inputs and compatibility with historical results, and the [deployment guide](deployment.md) for the production workflow.

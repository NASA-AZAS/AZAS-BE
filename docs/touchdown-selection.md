# 프론트 연동 가이드 · 도착 가능일 선택

여기서 도착일은 달 표면에 접지하는 UTC 날짜입니다. 아래 경로의 접두사는 `/api/v1`입니다. JSON 예시는 임시 PostgreSQL의 Site01 통합 테스트에서 화면 연결에 필요한 `data` 필드만 발췌했습니다. `SelectionTest`는 테스트용 지구 발사장입니다.

## 1. 후보지별 날짜 목록

달 후보지마다 한 번 분석을 접수하고, 응답의 `analysisRunId`를 해당 후보지에 보관합니다.

```http
POST /api/v1/landing-sites/{landingSiteId}/date-opportunities
Content-Type: application/json

{"missionDeadlineUtc":"2029-12-31"}
```

기본 조건은 태양과 지구가 동시에 보이는 연속 48시간입니다. HTTP 202를 받은 뒤 `GET /analyses/{runId}`의 `executionStatus`가 `SUCCEEDED`이면 `GET /analyses/{runId}/date-opportunities`를 호출합니다. 새 날짜를 선택할 때는 같은 실행을 사용합니다.

- **Select an Arrival Date 화면**은 `data.arrivalWindows[]`의 각 원소를 한 줄로 표시합니다. 모든 후보지에 같은 규칙을 적용합니다. 접지 후 연속 48시간 가시성을 만족하고, 접지 시각을 선택할 수 있는 구간의 폭도 48시간 이상이어야 합니다. 서버에서 이미 필터링하므로 빈 배열이면 선택할 시간대가 없습니다. 행을 선택하면 `startUtc` 전체를 `touchdownUtc`로 보냅니다. DM2의 첫 두 행은 `2027-01-11 03:30 UTC`, `2027-02-10 08:40 UTC`입니다. 두 번째 행의 요청은 `GET /api/v1/analyses/{runId}/launch-options?touchdownUtc=2027-02-10T08:40:00Z`입니다.
- **일별 달력**에서는 각 만족 구간의 `startUtc`부터 `endUtc`까지 포함되는 UTC 날짜를 모아 중복을 제거합니다. 끝이 정확히 자정이면 그 날짜도 포함합니다.
- 예를 들어 접지 구간이 1월 19일 밤부터 1월 21일 새벽까지라면 19일, 20일, 21일을 표시합니다. `supportEndUtc`는 접지 후 활동을 뒷받침하는 종료 시각입니다.
- `candidates[]`는 기본 최대 50개입니다. `TRUNCATED` 결과에서 목록 뒤쪽 날짜의 후보 ID가 없어도 날짜로 조회할 수 있습니다.

DM2의 `GET /api/v1/analyses/{runId}/date-opportunities` 응답에서 첫 두 행을 발췌했습니다.

```json
{
  "data": {
    "arrivalWindows": [
      {"startUtc":"2027-01-11T03:30:00Z","endUtc":"2027-01-22T08:10:00Z","supportEndUtc":"2027-01-24T08:10:00Z","surfaceStatus":"SATISFIED"},
      {"startUtc":"2027-02-10T08:40:00Z","endUtc":"2027-02-19T05:50:00Z","supportEndUtc":"2027-02-21T05:50:00Z","surfaceStatus":"SATISFIED"}
    ]
  }
}
```

## 2. 선택한 날짜의 발사장

```http
GET /api/v1/analyses/{runId}/launch-options?touchdownDateUtc=2027-01-20
```

```json
{
  "analysisRunId": "1f444a57-210c-4b3c-a716-6082b6b2c792",
  "executionStatus": "SUCCEEDED",
  "touchdownUtc": "2027-01-20T00:00:00Z",
  "surfaceCandidateId": "1f444a57-210c-4b3c-a716-6082b6b2c792-T2027-01-20T00:00:00Z",
  "launchSites": [{
    "launchSiteId": 42,
    "launchSiteCode": "SelectionTest",
    "candidates": [{
      "candidateId": "selected:1f444a57-210c-4b3c-a716-6082b6b2c792-T2027-01-20T00:00:00Z:42",
      "status": "GEOMETRIC_ONLY",
      "launchUtc": "2027-01-14T23:28:04Z",
      "tliUtc": "2027-01-15T01:28:04Z",
      "lunarArrivalUtc": "2027-01-19T18:00:00Z",
      "proposedLandingUtc": "2027-01-20T00:00:00Z"
    }]
  }]
}
```

날짜로 조회하면 원래 분석의 각 만족 구간 시작점에서 `timeStepMinutes`(기본 10분) 간격으로 접지 시각을 열거하고 구간 끝점도 포함합니다. 그날의 시각 중 발사 후보가 있는 가장 이른 시각을 반환합니다. 특정 격자 시각이나 끝점을 골랐다면 `touchdownUtc`를, 표면 후보 ID를 골랐다면 `surfaceCandidateId`를 전달합니다. 세 선택 파라미터 중 하나만 보내세요. 파라미터를 생략하면 기존 첫 접지 시각과 전체 발사장 목록을 받습니다.

선택한 날짜의 접지가 가능해도 발사할 수 있는 곳이 없으면 HTTP 200과 아래처럼 응답합니다. 날짜로 조회한 이 경우 `touchdownUtc`는 그날 격자에서 찾은 첫 접지 시각입니다.

```json
{
  "touchdownUtc": "2027-01-01T00:00:00Z",
  "surfaceCandidateId": "7d282fde-6cdf-4e54-a907-e153694a748e-00001",
  "launchSites": []
}
```

## 3. 궤적과 착륙 후 관측 연결

선택 상태에 `runId`, `touchdownUtc`, `surfaceCandidateId`를 함께 보관하고, 발사장을 고르면 그 발사장의 `candidates[0].candidateId`를 추가합니다. ID는 서버가 반환한 문자열 전체를 사용하고 경로에 넣을 때 `encodeURIComponent`를 적용합니다.

| 동작 | 요청 | 응답에서 확인할 값 |
| --- | --- | --- |
| 발사장 선택 후 궤적 계산 | `POST /analyses/{runId}/launch-options/{launchCandidateId}/trajectory` (본문 없음) | `trajectoryRunId`, `status`, `candidateId`, `surfaceCandidateId`, `proposedLandingUtc` |
| 같은 궤적 다시 열기 | `GET /analyses/{runId}/trajectories/{trajectoryRunId}` | 저장된 발사 후보와 표면 후보, 접지 시각 및 궤적 표본 |
| 같은 접지에서 하늘 관측 | `GET /analyses/{runId}/surface-candidates/{surfaceCandidateId}/post-landing` | `surfaceCandidateId`, `touchdownUtc`, `surfaceEndUtc`, `samples[]` |

위의 1월 20일 예시에서 궤적의 `proposedLandingUtc`와 관측의 `touchdownUtc`는 모두 `2027-01-20T00:00:00Z`입니다. 관측의 `surfaceEndUtc`는 `2027-01-22T00:00:00Z`입니다. 이 값들을 선택 응답과 대조하면 됩니다. 분석 전체의 `earliestTouchdownUtc`는 첫 접지 화면에만 사용합니다. 다른 날짜를 선택해도 이전에 저장한 ID로 원래 궤적·관측을 다시 열 수 있습니다. V7 이전에 저장한 궤적 상세의 `surfaceCandidateId`는 `null`일 수 있습니다.

| 선택 요청 결과 | 화면 처리 |
| --- | --- |
| 200, `launchSites.length > 0` | 반환된 발사장 중 하나 선택 |
| 200, `launchSites: []` | 접지는 가능, 해당 선택의 발사장 선택 비활성화 |
| 400 | 날짜·시각 형식이나 복수 선택자의 동시 지정 확인 |
| 404 `SURFACE_CANDIDATE_NOT_FOUND` | 표면 후보가 이 실행에 속하는지 확인 |
| 409 `ANALYSIS_NOT_SUCCEEDED` | 분석 실행 상태 확인 |
| 422 `TOUCHDOWN_DATE_UNAVAILABLE` | 해당 날짜에 만족 구간이 없거나 지정 시각이 유효한 후보 격자·끝점 밖임 |
| 503 `LAUNCH_SITE_DATA_INCOMPLETE` | 발사장 기준 데이터 미비로 표시 |

## DM2 기준 실측

LLSN ver2의 DM2 관측 자료(`data/observer-2027-2029/DM2.csv`, 157,825개 표본), 저장소의 지형 자료, 발사장 카탈로그(`experiments/launch_sites.csv`, 13곳)를 로컬 API에 적재해 확인했습니다. `landingSiteId=2`로 마감일 `2029-12-31`만 보내고 기본 태양·지구 동시 가시 48시간을 적용했습니다.

| UTC 연도 | 유효 도착일 | 발사 후보가 있는 날짜 |
| --- | ---: | ---: |
| 2027 | 73 | 73 |
| 2028 | 90 | 90 |
| 2029 | 77 | 77 |
| 합계 | 240 | 240 |

32개 만족 구간을 UTC 달력에 펼치면 첫날은 `2027-01-11`, 마지막 날은 `2029-12-19`입니다. 예를 들어 첫 구간은 `2027-01-11T03:30:00Z`부터 `2027-01-22T08:10:00Z`까지여서 날짜 12개를 표시할 수 있습니다. 기본 응답의 `candidates[]`에는 50개가 저장됐고, 전체 240일을 날짜 선택 API로 개별 조회했을 때 매일 12곳에서 `GEOMETRIC_ONLY` 후보가 반환됐습니다. 궤적 계산 결과는 사용자가 그중 한 후보를 골라 POST한 뒤 `status`로 확인합니다.

LLSN Java21 ver2 원본에서도 다음 명령으로 같은 32개 구간을 재현할 수 있습니다.

```sh
java -jar llsn.jar date-opportunities --site DM2 --hours 48 --deadline 2029-12-31 --offline --output results/dm2-check
java -jar llsn.jar launch-options results/dm2-check/date_opportunities.json DM2 results/dm2-check/launch-options.json
```

원본 `Deadline.emitGrid`는 첫 구간의 `2027-01-11T03:30:00Z`부터 `11:40:00Z`까지 10분 간격으로 50개를 내보내고 `TRUNCATED`를 반환합니다. 원본 `LaunchPlan.options`는 첫 만족 구간 시작점 하나에 대해서만 발사장을 계산합니다. 240일은 이 API에서 전체 구간의 접지 격자를 UTC 날짜별로 선택할 수 있게 확장한 결과입니다.

**도착 선택 화면은 27줄**입니다. 전체 `windows[]` 32개 중 접지 시각을 고를 수 있는 폭이 48시간 미만인 5개를 제외해 `arrivalWindows[]` 27개를 반환합니다. 예를 들어 `2028-04-29T08:50:00Z`부터 같은 날 `18:10:00Z`까지 접지가 가능하고, 마지막 접지 후 가시성은 `2028-05-01T18:10:00Z`까지 이어집니다. 이 구간은 접지 시각의 선택 폭이 9시간 20분이어서 화면 목록에서 제외됩니다.

마감일을 `2028-12-31`로 앞당기면 같은 DM2의 전체 구간은 22개이고 화면 목록은 18개입니다. LLSN의 `touchdownWindowCount` 요약과 대조할 때는 같은 마감일을 사용하고 전체 만족 구간 수를 비교하세요.

접지 시각을 매일 **한국시간 오전 9시**(`00:00Z`)로 고정해서 원본 구간의 후보 격자를 세면 **210일**입니다. 연도별로 63일, 80일, 67일입니다. **UTC 오전 9시**(`09:00Z`)로 고정하면 **218일**입니다. 날짜 선택 파라미터 `touchdownDateUtc`는 시각을 고정하지 않습니다.

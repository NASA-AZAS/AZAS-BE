# 후보지 조회와 기간별 가시성 분석

후보지 목록에서 얻은 DB PK와 날짜 범위로 분석을 요청합니다.
API 기본 경로는 `/api/v1`이며 기존 `ApiResponse`와 `X-Request-ID`를 사용합니다.
Swagger UI는 로컬 실행 후 `http://127.0.0.1:8000/docs`에서 확인합니다.

## 요청 순서

| 메서드·경로 | 역할 |
| --- | --- |
| `GET /api/v1/landing-sites` | 기본 데이터셋의 후보지 27개 요약 |
| `GET /api/v1/landing-sites/{id}` | DB PK로 후보지 상세 조회 |
| `POST /api/v1/landing-sites/{id}/analyses` | 가시성 분석 요청 |
| `GET /api/v1/analyses/{runId}` | 실행 상태와 결과 상태 조회 |
| `GET /api/v1/analyses/{runId}/daily-results` | 날짜순 일별 결과와 해당 날짜의 가시 구간 |

목록에 `terrainDatasetId`를 지정하면 해당 데이터셋만 조회합니다. 기본 버전은 `landing-screen / 2026-11-14-v1`입니다.
응답의 `landingSiteId`가 요청 경로의 PK입니다. `Site01` 등의 `siteCode`와 구분하세요.
목록·상세에 좌표·지표 표고·관측 높이·지형 선별 상태·데이터 버전을 포함하며 지평선 3,600행은 전송하지 않습니다.

분석 POST 본문:

```json
{
  "startDate": "2026-12-01",
  "endDate": "2026-12-03"
}
```

두 날짜를 **모두 포함**하며 UTC 기준입니다. 위 요청은 `2026-12-01T00:00:00Z`부터
`2026-12-04T00:00:00Z` 직전까지 3일을 평가합니다. 계산 간격은 60분, 요청당 1~31일입니다.
종료 경계의 천체 각도도 조회하되 마지막 표본 자체의 시간 가중치는 0입니다.

POST는 HTTP 202와 상태 조회용 `Location` 헤더를 반환합니다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "analysisRunId": "8b03b44d-c077-4e74-a6bb-bb24c50d7df0",
    "executionStatus": "PENDING"
  }
}
```

`analysisRunId`로 상태를 약 1~2초 간격으로 조회합니다. 실제 작업은 응답 전에 시작될 수도 있으므로
202의 `PENDING`은 접수 시점의 상태입니다. 실행 상태는 `PENDING → RUNNING → SUCCEEDED / FAILED`입니다.
`SUCCEEDED`이면 일별 결과를 조회하고, `FAILED`이면 `failureCode`를 표시합니다.
진행 중·실패한 실행의 일별 결과는 빈 배열이며 저장 중간 결과를 공개하지 않습니다.

특정 날짜만 필요하면 `?date=2026-12-02`를 지정하세요. 평가하지 않은 날짜는 빈 배열입니다.
일별 응답은 가시 비율, 판정 가능·미확인 시간 비율, 최장 차폐 시간, 지형 범위·품질 플래그,
태양·지구·공동 가시 구간을 포함합니다. 미산출 수치는 `null`입니다.

## 과학적 결과를 읽는 방법

실행 성공과 분석의 완전성은 별도입니다. 현재 엔진은 `executionStatus=SUCCEEDED`여도
먼 지형이 미검증이므로 `overallResultStatus=PARTIAL`을 반환합니다.
원본 LLSN과 동일하게 지평선 이하의 천체 중심은 `OCCULTED`, 지평선 위는 `UNKNOWN`으로 남깁니다.
현재 데이터로 `VISIBLE`을 확정하지 않으므로 가시 구간이 비어 있어도 구현 실패를 뜻하지 않습니다.

가시 비율은 **판정 가능한 시간**을 분모로 사용하며, 판정 가능한 시간이 없으면 `null`입니다.
미확인은 차폐 시간으로 세지 않으며 연속 차폐 구간을 끊습니다. 일별 집계는 UTC 경계에서 나누고,
기간 전체의 비율을 날짜별로 복사하지 않습니다. 지구 중심 가시성은 실제 통신 링크 판정과 구분해야 합니다.
이 요청은 `VISIBILITY` 분석입니다.

## 마감일 기반 착륙 기회 분석

| 메서드·경로 | 역할 |
| --- | --- |
| `POST /api/v1/landing-sites/{id}/date-opportunities` | 후보지 DB PK로 비동기 분석 접수. 202와 실행 ID 반환 |
| `GET /api/v1/analyses/{runId}` | 실행 상태, 결과 상태, 평가 기간, 실패 사유 조회 |
| `GET /api/v1/analyses/{runId}/date-opportunities` | 첫 접지 시각, 전체 접지 구간, 평가한 개별 후보(기본 상한 50개) |
| `GET /api/v1/analyses/{runId}/daily-results` | 일별 태양·지구·공동 가시율 및 가시 구간. `?date=2027-01-19` 지원 |

```json
{
  "missionDeadlineUtc": "2027-01-31",
  "surfaceDurationHours": 48
}
```

탐색 시작은 `2027-01-01T00:00:00Z`입니다. 마감일은 2027~2029년의 날짜(해당 날짜 23:59 UTC) 또는 초 단위 ISO 시각입니다. 활동 시간의 기본값은 48시간이고 양의 정수 분으로 환산 가능해야 합니다. 분석은 `observer_sample`을 후보지 PK와 기간으로 조회합니다. 요청 기간에 10분 격자 표본이 빠져 있으면 `FAILED / OBSERVER_DATA_INCOMPLETE`를 기록합니다.

기본 표면 조건은 태양과 지구가 모두 보이는 것입니다. 각 천체의 고도가 그 방위각의 지역 지평선보다 높으면 `VISIBLE`입니다. 다른 조건은 [요청 옵션](analysis-history.md#요청-옵션)에서 선택할 수 있습니다. 일별 가시율은 선택한 조건과 관계없이 실제 가시성을 UTC 날짜별로 집계합니다. 마지막 날짜는 마감 시각까지만 집계합니다.

| 결과 필드 | 해석 |
| --- | --- |
| `windows[]` | 저장된 전체 접지 구간과 각각의 `surfaceStatus`. 접지 시각은 `startUtc`부터 `endUtc`까지 양 끝을 포함합니다. `endUtc`는 조건을 지지하는 마지막 관측 시각(`supportEndUtc`)에서 활동 시간을 뺀 값입니다. |
| `earliestTouchdownUtc` | `SATISFIED`인 첫 구간의 시작. 만족 구간이 없으면 `null`입니다. |
| `candidates[]` | 만족 구간에서 접지 시각과 입력한 전이 조건 조합을 개별 평가한 결과. `proposedLandingUtc`가 접지 시각이며 `reasons`는 해당 후보의 판정 사유입니다. |

`maxCandidates`는 기본 50개이며 1~10,000개로 지정할 수 있습니다. 다음 후보 평가가 이 상한을 넘으면 실행 상태 조회의 `overallResultStatus`가 `TRUNCATED`입니다. 이때도 전체 접지 구간과 일별 결과를 조회할 수 있습니다. 진행 중이거나 실패한 실행은 최초 접지 시각이 `null`이고 두 배열이 비므로, 빈 응답을 해석할 때는 먼저 `GET /api/v1/analyses/{runId}`의 `executionStatus`를 확인하세요.

예를 들어 전이 입력 없이 표면 조건을 만족하면, 같은 응답에서 구간은 `windows[].surfaceStatus = SATISFIED`이고 개별 후보는 `status = ENVIRONMENT_ONLY`, `lambertStatus = NOT_EVALUATED`, `reasons = ["TRANSFER_INPUTS_MISSING"]`입니다. 화면에서는 접지 가능 구간을 표시하고 전이 계산 여부는 해당 후보에 표시하면 됩니다.

동일 요청이라도 실행마다 엔진 해시, 지형 데이터셋 해시, 사용한 관측 표본 수와 표본 해시를 보관합니다. 실제 원본과 비교하려면 테스트 프로세스에 `LLSN_VER2_HOME`으로 원본 모듈 디렉터리를 지정합니다. `AnalysisApiTests`가 일회용 PostgreSQL의 Site01 관측값으로 2027년 1월 접지 구간·후보 시각·31일의 태양/지구 가시율을 오프라인 CLI 결과와 비교합니다.

## 분석 코드 교체 지점

기존 단일 Spring 프로젝트 안에서 다음 부분을 구분합니다. Gradle 모듈·분석 서버는 추가하지 않습니다.

```text
Controller → AnalysisJobs / AnalysisStore
               → AnalysisEngine.analyze(Input)
                   → LlsnAnalysisAdapter
                       → HorizonsClient + VisibilityCalculator
               ← AnalysisEngine.Output → 일별 결과·가시 구간 저장
```

- `AnalysisEngine`: 호출 입력·반환 계약. API와 저장 계층은 이 계약만 사용합니다.
- `LlsnAnalysisAdapter`: DB 입력과 계산 코드의 입력 연결, 계산 결과를 `Output`으로 매핑하는 지점입니다.
- `engine/VisibilityCalculator`: 지평선 보간·판정·UTC 일별 집계 계산입니다.
- `HorizonsClient`: 외부 HTTP·좌표/시각/프레임 검증입니다. 파일 캐시를 읽거나 쓰지 않습니다.

계산 코드의 내부 형식만 바뀌면 Adapter에서 연결합니다. 새 지표·의미 변경이 생기면 계약·저장 컬럼도 검토해야 합니다.
각 실행은 엔진 버전과 실제 계산·Adapter·Horizons 클래스 바이트의 SHA-256을 보관합니다.
지형 데이터 버전, 조회 URL·시각·응답 해시도 기록하지만 외부 응답 본문을 캐시하지 않습니다.
동일 후보지·기간 요청도 새 실행을 만들고 정상 실행당 태양·지구를 각각 한 번씩 새로 조회합니다.
자동 재시도는 하지 않습니다.

## 실행 제한과 실패 처리

작업자는 앱 프로세스당 1개이고 대기열은 2개입니다. 수용 한도를 넘으면 DB 지형 조회 전에 HTTP 429를 반환합니다.
전체 제한시간은 접수·대기를 포함한 120초이며 외부 HTTP 요청은 각 45초, 연결은 10초 제한입니다.
외부 통신 동안 DB 트랜잭션을 유지하지 않습니다. 모든 날짜 결과·가시 구간·성공 상태는 한 트랜잭션으로 저장합니다.
저장 중 실패하면 해당 실행의 결과 전체를 롤백하고 실패 상태를 별도 트랜잭션으로 기록합니다.

V3 migration은 `analysis_run`에 실행 소유 토큰과 만료 시각을 추가합니다.
앱은 시작 후 15초 간격으로 만료된 작업만 실패 처리합니다. 다른 프로세스의 아직 유효한 작업을 일괄 중단하지 않습니다.
만료·소유권이 일치하지 않는 작업은 뒤늦게 결과를 덮어쓸 수 없습니다.
재시작으로 끊긴 작업은 만료 후 `ANALYSIS_TIMEOUT`이 되며 자동 재실행하지 않습니다.
작업 스레드의 로그에도 접수 요청의 `requestId`와 `analysisRunId`가 기록됩니다.

| 상황 | 응답·실행 결과 |
| --- | --- |
| 없는 후보지·데이터셋·분석 UUID | HTTP 404 |
| 잘못된 PK·날짜·31일 초과·역전된 기간 | HTTP 400 |
| 분석 수용 한도 초과 | HTTP 429 `ANALYSIS_CAPACITY_EXCEEDED` |
| JPL HTTP·통신 오류 | 접수된 실행의 `FAILED / HORIZONS_UNAVAILABLE` |
| JPL 응답 제한시간 초과 | `FAILED / HORIZONS_TIMEOUT` |
| 좌표계·시각·행 수 등이 다른 JPL 응답 | `FAILED / HORIZONS_INVALID_RESPONSE` |
| 전체 만료시간 초과 | `FAILED / ANALYSIS_TIMEOUT` |

실패 상태를 조회하는 GET 자체는 HTTP 200이며 본문의 실행 상태와 코드를 확인해야 합니다.
원본 외부 오류·스택은 응답에 포함하지 않습니다. 요청 ID로 [운영 로그](logging.md)를 확인할 수 있습니다.

## 검증

```powershell
.\gradlew.bat --no-daemon check bootJar
python -m unittest discover -s scripts/ci -v
python scripts/ci/check_docs.py
```

기본 테스트는 JPL에 접속하지 않습니다. 원본 모듈의 두 시나리오 578개 시각과 관측 응답 fixture를 사용해
보간·판정·일별 집계와 HTTP 파서를 확인합니다. 별도 PostgreSQL에서 실제 Adapter·계산 코드·비동기 API·저장/롤백을 검증합니다.
비동기 한도·기한 취소와 소유권·재시작 만료 처리도 검사합니다.
라이브 연결을 명시적으로 확인하려면 테스트 프로세스에 `AZAS_LIVE_HORIZONS_TEST=true`를 설정하고
`test --tests '*HorizonsLiveTests'`를 실행하세요. Site01의 한 시간에 대해 태양·지구를 각각 한 번 조회합니다.
이 테스트는 기본 CI에서 건너뛰며, 외부 서비스 가용성에 영향을 받습니다.

Windows 개발 PC에서 JPL 호출이 `PKIX path building failed`로 실패하고 필요한 CA가 Windows에 신뢰 등록돼 있다면,
해당 터미널에서 다음 옵션을 추가한 뒤 로컬 실행하세요. Linux·Docker에는 이 Windows 전용 옵션을 전달하지 않습니다.

```powershell
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
.\scripts\run-local.ps1
```

운영 실행 방법과 CI/CD 경계는 [배포 가이드](deployment.md)를 참고하세요.

## 첫 접지 시각 기준 발사장 후보

`GET /api/v1/analyses/{runId}/launch-options`에 착륙 기회 분석의 실행 ID를 전달합니다. 응답의 `data.landingSiteId`는 해당 실행의 달 후보지 PK이고, `data.touchdownUtc`는 해당 실행에서 표면 조건을 만족한 가장 이른 구간의 시작(UTC)입니다. `executionStatus`가 `SUCCEEDED`인 실행에서 조회할 수 있으며, 전체 결과가 `TRUNCATED`여도 저장된 접지 구간을 모두 대상으로 합니다.

`launchSites`는 `launchSiteId`(발사장 PK) 순서입니다. 각 항목에는 원본 `launchSiteCode`, 위도·동경 기준 경도, `candidates`가 있습니다. `candidates`는 빈 배열이거나 가장 이른 후보 한 개입니다. 후보에는 `candidateId`, `launchUtc`, `tliUtc`(달 전이 진입), `lunarArrivalUtc`, `proposedLandingUtc`가 있습니다.

`ILLUSTRATIVE_FIXED_PARKING_PLANE` 모델은 경사각 51.6°, 승교점 적경 0°, 고도 300 km를 가정합니다. 접지 6시간 전을 달 도착으로 두고, 발사 2시간 뒤의 달 전이 진입부터 도착까지 48~120시간이며 발사가 분석 시작 시각 이후인 교차점을 찾습니다. 예시의 `2027-01-19T04:10:00Z` 접지에 대해 Kennedy의 후보는 `Kennedy-1799883120`(`2027-01-13T23:32:00Z`)이고 Vostochny의 후보는 0개입니다. `GEOMETRIC_ONLY`와 `launchVerified=false`는 기하학적 후보임을 나타냅니다.

| 상황 | 응답 |
| --- | --- |
| `PENDING`, `RUNNING`, `FAILED` 또는 성공했지만 만족 구간이 없음 | 200, 해당 `executionStatus`, `touchdownUtc=null`, `launchSites=[]` |
| 접지 시각이 있고 발사장 데이터가 있음 | 200, 등록된 각 발사장 반환. 후보가 없는 발사장도 `candidates=[]`로 포함 |
| 접지 시각이 있고 발사장 테이블이 비어 있음 | 503 `LAUNCH_SITE_DATA_INCOMPLETE` |
| 실행 ID 없음 / 실행 종류 불일치 | 404 `ANALYSIS_NOT_FOUND` / 409 `ANALYSIS_TYPE_MISMATCH` |

## 선택한 발사 후보의 예비 달 전이 궤적

`POST /api/v1/analyses/{runId}/launch-options/{candidateId}/trajectory`를 본문 없이 호출합니다. 같은 실행의 `launch-options`에서 반환된 `candidateId`를 사용합니다. 서버가 실행의 후보, 좌표, 접지 시각을 확인하고 동기적으로 계산합니다. 응답의 `landingSiteId`와 `launchSiteId`는 각각 달 후보지와 지구 발사장의 DB PK입니다.

| `data.status` | 응답 내용 |
| --- | --- |
| `PRELIMINARY` | `samples`에 `tliUtc`부터 `lunarArrivalUtc`까지의 시각, 경과 초, 지구 중심 ICRF 위치 `positionKm`와 속도 `velocityKmS`가 들어갑니다. 간격은 600초이며 도착 시각을 마지막 표본에 포함합니다. |
| `NO_PRELIMINARY_SOLUTION` | 지원하는 전이 해를 찾지 못했습니다. `lambertStatus`와 `reason`을 반환하고 `samples`는 비어 있습니다. |
| `FAILED` | Horizons 조회·검증 또는 수치 계산이 실패했습니다. `reason`에 `HORIZONS_UNAVAILABLE` 등의 사유가 있고 `samples`는 비어 있습니다. |

세 계산 결과는 HTTP 200으로 반환됩니다. 실행이 없으면 404 `ANALYSIS_NOT_FOUND`, 해당 실행에 후보가 없으면 404 `LAUNCH_CANDIDATE_NOT_FOUND`입니다. 다른 분석 유형은 409 `ANALYSIS_TYPE_MISMATCH`, 성공 전이거나 실패한 착륙 기회 분석은 409 `ANALYSIS_NOT_SUCCEEDED`입니다.

계산에는 달 도착 시각의 JPL Horizons 지구·달 VECTORS를 사용합니다. `sourceRecords`에는 검증한 응답의 대상, 조회 URL, 시각, SHA-256을 담습니다. `PRELIMINARY_EARTH_TWO_BODY` 모델의 도착 목표는 달 중심에서 지구 방향으로 2,000 km 떨어진 점입니다. `positionResidualKm`는 목표에 대한 전파 오차, `tliDeltaVKmS`는 주차 궤도 속도에서 전이 속도로 바꾸는 데 필요한 속도 변화량입니다. 예비 모델의 `launchVerified`와 `trajectoryVerified`는 모두 `false`입니다.

Kennedy 예제 `Kennedy-1799883120`은 `2027-01-14T01:32:00Z`부터 `2027-01-18T22:10:00Z`까지 701개 표본을 반환합니다. 표면 접지는 `2027-01-19T04:10:00Z`이며, 표본 구간은 달 도착에서 끝납니다.

아래는 이 예제의 첫 표본(`data.samples[0]`)입니다. 위치와 속도는 소수 여섯 자리로 반올림했습니다.

```json
{
  "utc": "2027-01-14T01:32:00Z",
  "elapsedSeconds": 0,
  "positionKm": [-6047.927763, 1759.075112, 2219.400439],
  "velocityKmS": [-3.974492, 8.157424, 5.904744]
}
```

## 발사 후보에서 저장된 궤적까지 조회하기

| 순서 | 호출 | 다음 호출에 쓸 값 |
| --- | --- | --- |
| 1 | `POST /api/v1/landing-sites/{id}/date-opportunities` | HTTP 202 응답의 `data.analysisRunId`가 이후 경로의 `{runId}`입니다. `Location`을 조회해 `SUCCEEDED`를 확인합니다. |
| 2 | `GET /api/v1/analyses/{runId}/launch-options` | 발사장을 선택하고 `data.launchSites[].candidates[].candidateId`를 복사합니다. 예: `Kennedy-1799883120`. 접지 후보 목록에도 이름이 같은 필드가 있으므로 발사장 목록에서 값을 가져오세요. |
| 3 | `POST /api/v1/analyses/{runId}/launch-options/{candidateId}/trajectory` | 본문 없이 호출합니다. 계산을 마치면 응답의 `data.trajectoryRunId`로 이번 계산을 다시 열 수 있습니다. 같은 후보로 다시 POST하면 새 실행이 생깁니다. |
| 4 | `GET /api/v1/analyses/{runId}/trajectories/{trajectoryId}` | 3단계의 `trajectoryRunId`를 `{trajectoryId}`에 넣습니다. 저장된 결과를 읽으므로 Horizons를 호출하지 않습니다. |

궤적 ID를 모르면 `GET /api/v1/analyses/{runId}/trajectories`로 최근 100건을 조회합니다. `data`는 접수 시각(`createdAt`) 내림차순 요약 배열입니다. 각 항목의 `candidateId`, `trajectoryRunId`, 상태, 시각, 사유로 실행을 고른 뒤 상세를 조회하세요.

**궤적 상세 응답을 읽는 법**

| `data.executionStatus` | `data.status` | `data.samples` | 화면 처리 |
| --- | --- | --- | --- |
| `PENDING` / `RUNNING` | `null` | `[]` | 대기 / 계산 중을 표시하고 같은 상세 GET을 다시 조회합니다. |
| `SUCCEEDED` | `PRELIMINARY` | TLI부터 달 도착까지의 UTC 표본 | `positionKm`(XYZ, km)로 예비 궤적을 그립니다. 속도는 `velocityKmS`(XYZ, km/s)입니다. |
| `SUCCEEDED` | `NO_PRELIMINARY_SOLUTION` | `[]` | 지원하는 모델에서 해를 찾지 못했습니다. `lambertStatus`와 `reason`을 확인합니다. |
| `FAILED` | `FAILED` | `[]` | `reason`을 확인합니다. 예를 들어 `HORIZONS_UNAVAILABLE`이면 궤적 계산 중 외부 벡터를 얻지 못한 경우입니다. |

실패한 기록도 상세 조회에 성공하면 HTTP 200입니다. HTTP 404 `TRAJECTORY_NOT_FOUND`는 지정한 분석 ID와 궤적 ID의 조합으로 조회되는 기록이 없다는 뜻입니다. 기존 분석의 이력 목록이 비면 HTTP 200과 `data: []`를 받고, 분석 ID 자체가 없으면 목록은 HTTP 404 `ANALYSIS_NOT_FOUND`를 반환합니다.

## 원본 옵션 및 저장된 실행 기록

추가 요청 옵션, 발사 후보 고정, 궤적 실행 조회, V6 이전 결과 호환 방식은 [분석 옵션과 실행 기록](analysis-history.md)을 참고하세요.

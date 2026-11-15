# 분석 옵션과 실행 기록

기본 API 흐름과 응답은 [분석 API](analysis-api.md), 전체 테이블은 [ERD](erd.dbml)를 참고하세요.

## 요청 옵션

`POST /api/v1/landing-sites/{id}/date-opportunities`는 URL의 후보지 PK 하나를 분석합니다.
기존 `missionDeadlineUtc`와 `surfaceDurationHours` 요청은 그대로 사용할 수 있습니다.
기준 시작일은 원본 ver2와 같은 `2027-01-01T00:00:00Z`입니다. 날짜만 쓰면 마감은 UTC 23:59입니다.

| 옵션 | 기본값·범위 | 계산에 반영되는 지점 |
| --- | --- | --- |
| `surfaceCriteria.sun`, `.earth` | `VISIBLE`; `ANY`, `VISIBLE`, `OCCULTED` | 요청한 조건을 만족하는 연속 표본 구간 |
| `surfaceCriteria.unknownPolicy` | `RECORD_UNKNOWN`, `EXCLUDE_WITH_RECORD` | 현재 DB는 유한한 각도만 허용하므로 UNKNOWN 표본을 생성하지 않음. 누락은 실행 실패 |
| `timeStepMinutes` | 10; 1~1440 | 개별 접지 후보 탐색 간격. 관측 아카이브 자체는 항상 10분 간격 |
| `maxCandidates` | 50; 1~10000 | 개별 후보 평가 수 상한. 전체 접지 구간은 자르지 않음 |
| `refinementPolicy` | `minStepMinutes=min(10,timeStepMinutes)`, `maxDepth=0` | 이웃 후보의 상태·솔버 상태가 다를 때 분 단위 중점 추가 평가 |
| `networkMode` | `ONLINE` 또는 `CACHE_ONLY` | 표면 조건은 DB만 사용. 전이 계산은 ONLINE에서 외부 벡터 조회 |

최소 정밀도는 1분 이상, 탐색 간격 이하이고 최대 깊이는 0~12입니다.
미지원 키, 잘못된 enum, 부분적인 전이 입력은 접수 전에 HTTP 400입니다.
PK를 URL에서 받으므로 원본 CLI의 `candidateSiteIds`, `siteDeadlinesUtc`, 파일 경로·출력 옵션은 받지 않습니다.

예를 들어 태양 조건을 무시하고 지구가 차폐된 구간에서 1시간 활동할 후보를 최대 7개 찾습니다.

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

일별 태양·지구 가시 비율은 실제 가시성으로 집계합니다. 검색 조건을 ANY나 OCCULTED로 바꿔도 그 지표의 의미는 같습니다.
표본 사이의 짧은 구간을 새 관측으로 확인하는 기능은 없으며, 후보 탐색 간격을 줄여도 아카이브 정밀도가 높아지지는 않습니다.

## 원본 전이 입력

전이 계산에는 아래 다섯 옵션을 **함께** 전달합니다. 시간 범위는 `min`, `max`, `step`을 가진 시간 단위 객체입니다.
각 값은 정수 분으로 표현할 수 있어야 하고, 한 범위는 최대 100개 간격을 허용합니다. 끝값도 평가합니다.

| 옵션 | 내용 |
| --- | --- |
| `departureState` | `epochUtc`, `frame="ICRF"`, `center="EARTH"`, `positionKm[3]`, `velocityKmS[3]`, `earthMuKm3S2` |
| `arrivalTargetRule` | `type="MOON_RELATIVE_ICRF_OFFSET"`, `offsetKm[3]`, `minimumAltitudeKm` |
| `transferDurationHours` | 양수 전이 시간 범위 |
| `preLandingDelayHours` | 0 이상 도착~표면 접지 지연 범위 |
| `lambert` | `branch="SHORT_WAY"` 또는 `"LONG_WAY"`, `positionToleranceKm`(0 초과~10 이하) |

`launchToTliDelayHours`는 전이 입력이 있을 때만 추가할 수 있습니다. 생략하면 `launchUtc=null`이며 TLI를 발사로 표시하지 않습니다.
출발 위치는 지구 반경 밖, 도착 오프셋은 달 반경+최소 고도 밖이어야 합니다.
출발 epoch는 기준 시작일에서 366일 이내, 정수 초 UTC입니다.

전이 후보 하나는 TLI·달 도착 각각에서 지구(399)·달(301) 벡터를 새로 조회합니다(정상 평가당 4회).
원본과 같은 Earth-only 전파와 zero-revolution Lambert를 사용하며, 모든 `trajectoryVerified`는 false입니다.
`CACHE_ONLY` 전이에는 서버의 별도 벡터 아카이브가 없으므로 외부 호출 없이 `UNKNOWN / SOURCE_OR_NUMERICAL_ERROR`, 사유 `VECTOR_ARCHIVE_UNAVAILABLE`을 기록합니다.
출발 시간이 탐색 시작보다 이르면 `EXCLUDED`로 기록하며 벡터를 조회하지 않습니다.

`date-opportunities` 후보 응답에는 기존 ID·접지·활동 종료 시각에 더해 상태, 발사/TLI/도착 시각, 전이 시간, 솔버 상태·잔차, 모델, 사유, 솔버 상세가 포함됩니다.
전이 후보의 이중체 계산과 아래의 고정 주차궤도 발사 후보·궤적 계산은 서로 다른 입력을 사용하는 흐름입니다.

## 저장과 조회

| 테이블 | 저장 단위 |
| --- | --- |
| `analysis_run` | 실행 UUID, 기본값까지 확정한 요청 JSON, 입력·엔진 해시, 출처, 상태 |
| `landing_opportunity_result` | 후보 상한과 무관하게 전체 접지 가능 구간 |
| `surface_candidate_result` | 실제로 평가한 개별 후보·전이 판정. 주요 수치·상태는 컬럼 |
| `launch_option_batch` | 실행 당시 모델·가정·엔진 해시·접지 시각·계산 시각 |
| `launch_option_result` | 발사장별 좌표 사본과 후보. 후보가 없는 발사장도 행으로 보존 |
| `trajectory_run` | 궤적 POST마다 새 UUID, 선택한 입력 사본·엔진 해시·상태·잔차·delta-v·출처 |
| `trajectory_sample` | 궤적 실행별 순번·UTC·경과 초·위치 XYZ·속도 XYZ 컬럼 |

신규 분석은 완료 트랜잭션에서 발사 후보까지 저장합니다. 이후 카탈로그 좌표가 바뀌어도 같은 실행의 후보는 같습니다.
발사장 카탈로그가 비어 있으면 표면 분석은 성공하고 발사 배치는 `DATA_UNAVAILABLE`로 남습니다. 데이터를 넣은 뒤 새 분석을 요청하세요.

V6 이전 실행은 당시 발사 후보·궤적이 저장되지 않았습니다. 기존 전체 구간에서 접지 후보를 읽는 호환 동작을 유지하고,
발사 후보는 첫 조회 시 한 번 계산해 **현재 계산 시각**으로 저장합니다. 과거에 저장됐던 결과로 취급하지 않습니다.
이후 같은 실행의 발사 후보는 재계산하지 않습니다. 과거 궤적은 임의 생성하지 않습니다.
발사 후보 응답의 `calculatedAt`, `engineSha256`으로 실제 계산 시점과 구현을 확인할 수 있습니다.

기존 궤적 POST는 동기 HTTP 200을 유지합니다. 응답에 `trajectoryRunId`, `executionStatus`, `createdAt`, `startedAt`, `finishedAt`, `engineSha256`이 추가됩니다.
대기 중 PENDING, 실제 작업 시작 후 RUNNING이며 접수·실행 시작·종료 시각을 구분합니다.

```text
POST /api/v1/analyses/{runId}/launch-options/{candidateId}/trajectory
GET  /api/v1/analyses/{runId}/trajectories
GET  /api/v1/analyses/{runId}/trajectories/{trajectoryId}
```

목록은 최신 100건입니다. 상세 GET은 저장한 결과·표본만 읽으며 Horizons를 호출하지 않습니다.
재계산은 POST로 새 실행을 만듭니다. 네트워크 실패도 실행에 남으며 표본은 빈 배열입니다.
실행 상태 SUCCEEDED는 계산 절차의 완료를 뜻합니다. 실제 판단은 `status`, `lambertStatus`, 검증 플래그를 함께 보세요.

기존 표면 분석과 궤적 계산이 작업자 1개·대기열 2개·대기 포함 120초 한도를 공유합니다.
기한이 지난 작업은 늦은 결과로 덮어쓰지 못하고, 재시작 후 만료된 실행도 실패로 정리합니다.

## DB와 배포 호환성

Flyway V6는 테이블·인덱스만 추가합니다. V1~V5 파일과 체크섬, 적재된 CSV 데이터, 기존 분석 결과는 수정하지 않습니다.
신규 테이블에는 사용자 분석 요청으로만 결과가 쌓입니다. CSV 수동 적재 방식과 로컬 Compose 실행 방법은 같습니다.
새 환경변수·라이브러리·운영 배포 파일은 필요하지 않습니다.

이 작업은 `dev` 대상 PR입니다. PR의 Java·PostgreSQL·Docker 검사를 수행하며, 운영 배포는 기존처럼 `main` push 또는 `main`의 수동 실행에서만 진행합니다.

## 원본 비교 검증

기본 CI는 네트워크 대신 기록된 원본 fixture와 루프백 HTTP 서버를 사용합니다.
13개 발사장 전체 후보, Kennedy 궤적 701개 표본, 단경로/장경로 및 사용자 지정 중력계수, 역방향 전파를 비교합니다.
추가로 원본 폴더가 있는 개발 PC에서는 실제 CLI와 표면 조건 VISIBLE/ANY/OCCULTED, 후보 간격·상한을 바꾼 결과를 비교합니다.

```powershell
$env:LLSN_VER2_HOME = 'C:\path\to\LLSN-Java21-ver2'
.\gradlew.bat --no-daemon check bootJar
```

이는 원본 구현과의 회귀 비교이며 독립적인 과학 검증을 의미하지 않습니다.

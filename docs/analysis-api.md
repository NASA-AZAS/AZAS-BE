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
이 API는 `VISIBILITY` 분석입니다. 마감일 기반 착륙 기회 탐색과 궤적·스코어링은 제공하지 않습니다.

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

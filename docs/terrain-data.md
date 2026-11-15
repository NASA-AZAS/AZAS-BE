# 지형 데이터 모델과 초기 적재

새 DB로 앱을 시작하면 Flyway가 테이블을 생성하고 후보지 27개와 지평선 표본 97,200개를 적재합니다.
기존 로컬 실행 명령을 그대로 사용하며 별도 CSV 경로·환경변수·수동 적재 명령은 필요하지 않습니다.
현재 변경은 저장 모델과 초기 데이터까지입니다. 후보지 조회 API와 분석 모듈 실행은 후속 작업입니다.

## 테이블과 저장 범위

[확정 ERD](erd.dbml)는 dbdiagram에 붙여 넣을 수 있는 DBML입니다.

| 테이블 | 저장 내용 | 주요 식별·연결 기준 |
| --- | --- | --- |
| `terrain_dataset` | 원본 버전·해시·좌표 기준·출처 메타데이터 | 코드 + 버전 유일, 번들 SHA-256 유일 |
| `landing_site` | 후보지 좌표·지표 표고·관측 높이·지형 선별 상태 | 데이터셋 + `site_code` 유일 |
| `horizon_sample` | 방위각별 지형 차폐각과 두 종류의 거리 | 후보지 PK + 방위각 인덱스 복합 PK |
| `analysis_run` | 후보지 하나에 대한 분석 요청·기간·실행 상태·계산 조건 | UUID, 후보지 FK |
| `daily_analysis_result` | UTC 날짜별 가시 비율·판정 가능 비율·차폐 요약 | 실행 + 날짜 유일 |
| `visibility_window` | 날짜별 태양·지구·공동 가시 시간 구간 | 일별 결과 FK |
| `landing_opportunity_result` | 마감일 기반 착륙 기회와 판정 근거 | 실행 + 기회 코드 유일 |

분석 시나리오 중간 테이블·시간별 원본 판정·Horizons 캐시는 만들지 않습니다.
동일 후보지·기간·조건으로 다시 계산해도 새로운 `analysis_run`을 저장할 수 있습니다.
일별 요약과 여러 가시 구간을 분리하고, 여러 날짜에 걸칠 수 있는 착륙 기회는 실행에 직접 연결합니다.
분석 결과 테이블은 빈 상태로 시작하며 모듈의 예제 결과 CSV는 적재하지 않습니다.

주요 결과는 개별 컬럼이며, JSONB는 원본 메타데이터·가변 계산 조건·출처·궤적 상세에만 사용합니다.
미산출 수치는 `NULL`, 미확인 판정은 `UNKNOWN`으로 보존합니다. 가시 비율의 분모는 판정 가능한 시간입니다.
실행 성공(`SUCCEEDED`)과 과학 결과의 불완전성(`PARTIAL`)은 별도 상태입니다.
마감일은 사용자가 선택한 날짜와 실제 적용한 마감 시각을 함께 보관합니다.

## 원본과 검증

분석 모듈 `LLSN-Java21`에서 전달된 `landing-screen` 번들을
`src/main/resources/terrain/landing-screen/v1/`에 원본 바이트 그대로 보관합니다.
실행 JAR에도 포함되므로 저장소 밖의 분석 폴더나 파일 경로에 의존하지 않습니다.

| 원본 | 용도 |
| --- | --- |
| `candidates.csv` | 후보지 27개의 좌표·지표 표고·선별 결과 |
| `Site01_horizon.csv` ~ `Site27_horizon.csv` | 사이트별 3,600행, 합계 97,200행 |
| `metadata.json` | 좌표계·높이·각도·NoData 정책 |
| `product_registry.json` | 원본 30개 파일의 바이트 수·SHA-256 |
| `Site01_mmgis.geojson` | Site01 좌표·지표 표고·관측 높이 교차 검증 |
| `HORIZON_METADATA.md` | 생산자의 이관 기록 |

CSV 28개에서 적재하는 데이터 행은 총 97,227개입니다. TIFF·JAR·예제 분석 결과는 포함하지 않습니다.
데이터셋 코드는 `landing-screen`, 버전은 `2026-11-14-v1`입니다.
`source_metadata`는 `metadata`와 `productRegistry`를 포함하며, 번들 해시는 두 JSON과 모든 등록 파일을 포함합니다.
`.gitattributes`에서 원본 번들의 줄바꿈 변환을 막아 Windows와 CI의 체크섬을 일치시킵니다.

적재 전에 아래 조건을 검사하며 실패하면 앱 시작을 중단합니다.

- 등록 파일의 경로·바이트 수·SHA-256, 후보지와 프로파일의 일대일 대응
- 후보지 27개, 사이트별 3,600행, 방위각 `0.0° ~ 359.9°`의 `0.1°` 격자
- 숫자의 유한성, 위경도·각도·거리 범위, 필수 헤더와 중복 여부
- `MOON_ME`/DE421 달 중심 좌표, 기준 구면 반지름 1,737,400m, 지표 위 관측 높이 1m
- 현지 북쪽 0°·시계 방향 방위각, 현지 수평면 기준 고도각, Site01 GeoJSON과 CSV의 일치

경도는 원본의 동경 양수·서경 음수 표기를 보존합니다. 지표 표고와 지표 위 관측 높이는 별도 컬럼입니다.
음수 지평선 고도각도 유효하며 0으로 바꾸지 않습니다.
차폐 지형의 접평면 수평 거리와 탐색한 구면 호 거리는 서로 다른 컬럼으로 유지합니다.
Site01의 방위각 220° 기준값은 고도각 8.1972760°, 차폐 수평 거리 5,730.6m, 탐색 거리 311,785m입니다.

이 검증은 전달된 파일의 무결성과 선언된 메타데이터의 일치 여부를 확인합니다.
DEM 재계산이나 독립적인 과학 검증을 수행하지 않으며 `distant_terrain_verified=false`를 유지합니다.
CSV 밖 지형·태양 원반·실제 통신 링크 조건의 검증은 별도입니다.

## 적재 트랜잭션과 버전 관리

`V1__create_terrain_and_analysis_tables.sql`이 테이블과 FK·유일성·값 범위 제약을 생성합니다.
`V2__import_landing_screen_v1`은 검증된 번들을 JDBC 500행 배치로 적재합니다.
97,200개 엔티티를 JPA 영속성 컨텍스트에 올리지 않으며, Flyway가 전체 트랜잭션과 실행 이력을 관리합니다.
중간 배치에서 실패하면 데이터셋·후보지·표본이 함께 롤백됩니다.

재시작 시 Flyway 검증 후 이미 적용된 적재를 건너뜁니다.
적재기를 같은 입력으로 다시 실행해도 행을 추가하지 않으며, 같은 버전의 해시가 다르면 덮어쓰지 않고 실패합니다.
V2 체크섬은 번들 해시에서 계산합니다. 적용 후 원본 파일·SQL·Java 적재 코드를 수정하지 마세요.
데이터가 바뀌면 새로운 리소스 버전과 새 마이그레이션을 추가하고 기존 데이터셋을 보존합니다.
이전 분석 실행은 후보지를 통해 당시 데이터셋 버전을 계속 참조합니다.

## Repository 조회와 로컬 검증

후보지는 데이터셋 버전별로 조회하고 분석 입력용 지평선은 방위각 인덱스 순서로 가져옵니다.
조회·변환 서비스는 트랜잭션 안에서 필요한 입력 DTO를 구성하면 됩니다.

```java
var dataset = datasetRepository.findByDatasetCodeAndDatasetVersion(
        "landing-screen", "2026-11-14-v1").orElseThrow();
var sites = landingSiteRepository.findByTerrainDatasetIdOrderBySiteCode(dataset.getId());
var samples = horizonSampleRepository.findByIdLandingSiteIdOrderByIdAzimuthIndex(siteId);
```

JDK 21과 Docker가 준비된 상태에서 실행합니다.

```powershell
.\gradlew.bat --no-daemon check bootJar
```

테스트는 별도 Testcontainers PostgreSQL을 사용해 전체 행 수·기준값·중복 방지·중간 실패 롤백과
JPA의 enum·JSONB·배열·미산출 값 보존을 검증합니다. 운영 DB나 Horizons API는 호출하지 않습니다.
일반 로컬 실행과 DB 접속 정보는 [DB 개발 가이드](database.md)를 참고하세요.

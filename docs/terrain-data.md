# 지형 데이터와 DataGrip 수동 업로드

**Flyway는 스키마를 만들고, 데이터는 DataGrip에서 직접 올립니다.**
앱 시작이나 배포 시 CSV를 읽거나 자동 적재하지 않습니다. 별도 초기화 프로그램도 없습니다.
이미 후보지 27개·지평선 표본 97,200개가 있는 DB는 그대로 사용하면 됩니다.

## 새 DB에 업로드

1. Spring을 한 번 실행해 Flyway가 테이블을 만들게 합니다. 로컬은 [Compose 실행](database.md)을 사용합니다.
2. DataGrip에서 대상 PostgreSQL에 연결하고 `public` 스키마를 새로고침합니다. RDS는 기존 승인된 연결 경로와 계정을 사용합니다.
3. 아래 순서로 **기존 테이블을 우클릭 → Import/Export → Import Data from File(s)** 에서 CSV를 선택합니다.
4. UTF-8, 쉼표 구분, 큰따옴표 인용, **First row is header**를 선택하고 같은 이름의 컬럼끼리 매핑합니다.
5. 세 파일의 업로드가 모두 끝나면 [after-import.sql](../data/import/after-import.sql)을 같은 DB의 쿼리 콘솔에서 실행합니다.

| 순서 | 업로드 파일 | 대상 테이블 | 행 수 |
| --- | --- | --- | --- |
| 1 | [01_terrain_dataset.csv](../data/import/01_terrain_dataset.csv) | `public.terrain_dataset` | 1 |
| 2 | [02_landing_site.csv](../data/import/02_landing_site.csv) | `public.landing_site` | 27 |
| 3 | [03_horizon_sample.csv](../data/import/03_horizon_sample.csv) | `public.horizon_sample` | 97,200 |

스키마에 파일을 드래그해 새 테이블을 만드는 대신 Flyway가 생성한 **기존 테이블**을 선택합니다.
CSV의 PK·FK를 모두 그대로 매핑하세요. `created_at`은 파일에 없으며 DB 기본값을 사용합니다.
`source_metadata`는 큰따옴표로 감싼 JSON 한 칸입니다. 미리보기에서 여러 컬럼으로 쪼개지지 않았는지 확인하세요.
DataGrip 공식 안내: [CSV를 기존 테이블에 가져오기](https://www.jetbrains.com/help/datagrip/import-data.html).

이 파일은 **지형 테이블 세 개가 모두 비어 있는 DB의 최초 업로드용**입니다.
데이터셋 PK 1과 후보지 PK 1~27, 후보지별 방위각 인덱스 0~3599를 포함합니다.
이미 적재된 DB에 다시 넣거나 기존 PK를 바꾸지 마세요. 과거 분석 결과가 후보지 PK를 참조합니다.
중간에 업로드가 실패하면 반영된 행과 오류를 확인하고 복구한 뒤 진행하세요. 자동 덮어쓰기·초기화는 없습니다.
`after-import.sql`은 수동 입력한 PK 이후부터 ID가 생성되도록 시퀀스를 맞추고 행 수를 확인합니다.

새 DB는 업로드 전에도 앱과 DB readiness가 정상 시작하지만 후보지 API는 `TERRAIN_DATASET_NOT_FOUND`를 반환합니다.
모든 파일의 업로드가 끝난 뒤 [후보지 조회·분석 API](analysis-api.md)를 사용하세요.
데이터 업로드 후 앱을 재시작할 필요는 없습니다.

## 기존 DB와 Flyway 이력

V1은 테이블·제약을, V3는 분석 실행 관리 컬럼을 생성합니다. 기존 SQL은 변경하지 않습니다.
과거 CSV 적재를 담당한 Java V2는 기존 DB 검증을 위해 이름·유형·체크섬 `2091428245`를 유지하는 빈 이력으로 남깁니다.
V2는 새 DB에서도 데이터를 쓰지 않습니다. 기존 DB의 데이터·PK·분석 이력·Flyway 기록을 수정하거나 `repair`하지 않습니다.
이후 스키마 변경은 새 Flyway 버전으로 추가하고 데이터 변경은 필요한 경우 직접 수행합니다.

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

## 데이터 출처와 기준값

`data/import/`는 원본 `LLSN-Java21`의 `landing-screen` 데이터를 DB 컬럼 형태로 정리한 정적 파일입니다.
앱 소스·리소스·실행 JAR·Docker 이미지에는 CSV와 적재 코드가 포함되지 않습니다.
테스트만 이 파일을 별도 PostgreSQL에 업로드해 조회·분석을 검증합니다. CI는 일회용 DB에만 테스트 데이터를 넣습니다.

- 데이터셋: `landing-screen` / `2026-11-14-v1`
- 원본 번들 SHA-256: `7ca8a99525a7fb8c9827121bfc1cbeff054355344d3a76f3316da9d8e0d904ad`
- `source_metadata`: 원본 `metadata`와 파일별 SHA-256을 담은 `productRegistry` 전체
- 좌표: `MOON_ME`/DE421 달 중심 위경도, 동경 양수·서경 음수
- 기준 구면 반지름: 1,737,400m, 모든 관측점은 지표 위 1m
- 방위각: 현지 북쪽 0°부터 시계 방향, 0.1° 간격, 사이트당 3,600행

Site01의 방위각 220° 기준값은 고도각 8.1972760°, 차폐 수평 거리 5,730.6m, 탐색 구면 호 거리 311,785m입니다.
음수 지평선 고도각도 유효합니다. 두 거리의 의미와 지표 표고·관측 높이는 각각 별도 컬럼으로 유지합니다.
원본의 후보지 순서와 `Site01`, `Site20v2`, `Haworth`, `LM1` 등의 코드를 보존합니다.
DEM 범위 밖 지형·태양 원반·실제 통신 링크는 별도 검증 대상이며 `distant_terrain_verified=false`입니다.

## 검증

```powershell
.\gradlew.bat --no-daemon check bootJar
```

Testcontainers PostgreSQL에서 새 DB의 빈 스키마, 세 CSV 업로드, 기존 V2 체크섬 호환성과 데이터 보존,
27개 후보지·97,200개 표본·Site01 기준값·DB 제약·분석 결과 저장을 검증합니다.
운영 RDS나 EC2에는 접속하지 않습니다.

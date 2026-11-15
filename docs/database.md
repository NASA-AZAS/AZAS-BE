# PostgreSQL, JPA와 Flyway 개발

로컬은 Docker PostgreSQL, 테스트는 Testcontainers PostgreSQL, 운영은 RDS를 사용합니다.
앱은 동일한 SPRING_DATASOURCE 환경변수로 연결하며 운영 자격증명은 EC2가 Secrets Manager에서 읽습니다.

## Docker로 PostgreSQL과 Spring 함께 실행

Docker Desktop과 Compose 2.24 이상을 준비하고 저장소 루트에서 실행합니다. 이 방식에는 호스트 JDK가 필요하지 않습니다.

```powershell
docker compose --env-file docker/postgres.env --file compose.local.yaml up --build --detach --wait --wait-timeout 180
```

`postgres`와 `api` 두 서비스를 실행합니다. DB가 준비된 뒤 Spring이 시작하고, Flyway가 테이블과 CSV를 자동 적용합니다.
첫 이미지 빌드에는 JDK·JRE 이미지와 Gradle 의존성을 내려받는 시간이 필요합니다. 이후에는 빌드 캐시를 사용합니다.

컨테이너의 앱 설정은 `.env.example`에 선택적인 `.env`를 덮어써서 전달합니다. 두 파일의 DB 설정과 관계없이
Compose의 Spring은 항상 `postgres:5432/azas`의 로컬 DB 계정으로 연결합니다. 포트 8000·55432는 로컬 루프백에만 공개합니다.
`.env`의 선택적 로드는 [Docker Compose의 required 옵션](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)을 사용합니다.

```powershell
docker compose --env-file docker/postgres.env --file compose.local.yaml ps
docker compose --env-file docker/postgres.env --file compose.local.yaml logs --follow api
```

Swagger는 `http://127.0.0.1:8000/docs`, 후보지 API는 `http://127.0.0.1:8000/api/v1/landing-sites`입니다.
코드를 변경한 뒤에는 처음의 `up --build` 명령을 다시 실행하세요.

## DataGrip에서 로컬 DB 확인

PostgreSQL 데이터 소스를 만들고 다음 값을 입력한 뒤 연결을 확인합니다.

| 항목 | 값 |
| --- | --- |
| Host | `localhost` |
| Port | `55432` |
| Database | `azas` |
| User | `azas_local` |
| Password | `azas_local_only` |
| SSL mode | `disable` |
| JDBC URL | `jdbc:postgresql://localhost:55432/azas?sslmode=disable` |

DataGrip은 호스트에서 실행하므로 `localhost:55432`를 사용합니다. Docker 안의 Spring만 서비스명 `postgres:5432`를 사용합니다.
연결 후 `public` 스키마를 선택하고 새로고침하면 업무 테이블 7개와 `flyway_schema_history`가 보입니다.
Spring 시작 전에는 마이그레이션이 실행되지 않아 테이블이 없을 수 있습니다.

```sql
SELECT count(*) FROM landing_site;   -- 27
SELECT count(*) FROM horizon_sample; -- 97200
SELECT landing_site_id, site_code, latitude_degrees, longitude_degrees
FROM landing_site ORDER BY site_code;
```

새 DB에서는 분석 요청 전 `analysis_run`과 결과 테이블이 비어 있는 것이 정상입니다. 데이터베이스 UI 컨테이너는 추가하지 않습니다.

## Windows에서 Spring 직접 실행

JDK 21과 Docker Desktop을 준비한 뒤 저장소 루트에서 실행합니다.
Compose의 `api`가 실행 중이면 먼저 `docker compose --env-file docker/postgres.env --file compose.local.yaml stop api`로 중지해
8000번 포트가 겹치지 않도록 합니다. 기존 PostgreSQL 컨테이너와 데이터 볼륨은 계속 사용합니다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
.\scripts\run-local.ps1
```

스크립트는 `compose.local.yaml`의 PostgreSQL을 시작하고 `.env.example`과 `.env`를 읽어
Spring에 전달합니다. 기존 프로세스 환경변수가 가장 우선하며 `.env`가 기본 예시보다 우선합니다.
현재 스크립트는 `AZAS_`, `SPRING_DATASOURCE_` 키를 지원합니다.

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/azas?sslmode=disable
SPRING_DATASOURCE_USERNAME=azas_local
SPRING_DATASOURCE_PASSWORD=azas_local_only
```

위 값은 로컬 Compose 계정과 맞춰져 있습니다. `.env`는 Git에서 제외됩니다.
이미 별도 DB를 실행 중이면 연결 변수를 설정하고 `scripts/run-local.ps1 -SkipDatabase`를 사용하세요.

## Linux/macOS와 IDE

Compose의 `api`가 실행 중이면 먼저 `docker compose --env-file docker/postgres.env --file compose.local.yaml stop api`로 중지합니다.

```bash
docker compose --env-file docker/postgres.env --file compose.local.yaml up --detach --wait postgres
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:55432/azas?sslmode=disable'
export SPRING_DATASOURCE_USERNAME=azas_local
export SPRING_DATASOURCE_PASSWORD=azas_local_only
./gradlew bootRun
```

IntelliJ에서는 DB 시작 명령을 실행한 뒤 `AzasApplication`의 실행 환경변수에 위 세 값을 등록하고
JDK 21로 실행합니다. Spring과 IDE는 `.env`를 자동으로 읽지 않습니다.
Swagger는 http://127.0.0.1:8000/docs, DB readiness는
http://127.0.0.1:8000/actuator/health/readiness 입니다.

DB 도구에서는 host `localhost`, port `55432`, database `azas`, username `azas_local`,
password `azas_local_only`를 사용합니다. DB는 로컬 루프백에만 포트를 공개합니다.

## 로컬 데이터와 중지

```bash
docker compose --env-file docker/postgres.env --file compose.local.yaml down
```

프로젝트명 `azas-local`과 볼륨명 `azas-local_postgres-data`는 기존 구성과 같습니다.
이전 경로의 Compose로 만들었던 데이터도 같은 볼륨에서 이어서 사용합니다.
데이터를 지울 때만 `down --volumes`를 사용하세요.
로컬·CI 이미지는 `docker/postgres.env`의 digest로 고정합니다. PostgreSQL 메이저 버전은 운영과 같은 17입니다.

## 스키마 변경

Flyway는 앱 시작 시 SQL과 Java 마이그레이션을 버전 순서대로 적용합니다.
`V1__create_terrain_and_analysis_tables.sql`은 확정 ERD의 7개 테이블을 생성하고,
Java 마이그레이션 `V2__import_landing_screen_v1`은 번들 CSV를 검증해 최초 한 번 적재합니다.
Java 마이그레이션은 `src/main/java/db/migration/`, SQL은 `src/main/resources/db/migration/`에 있습니다.
데이터 출처·행 수·버전 갱신 정책은 [지형 데이터 가이드](terrain-data.md)를 참고하세요.
JPA는 `ddl-auto: validate`, `open-in-view: false`이며 스키마 생성·변경은 Flyway가 담당합니다.
Flyway clean은 비활성화하고 기존 SQL과 V2 데이터 번들의 체크섬을 검증합니다.
적용한 SQL·Java 적재 코드·번들 파일은 수정하지 말고 새 버전을 추가하세요.

후보 앱이 시작할 때도 운영 migration이 실행되므로 기존 앱과 호환되는 변경부터 적용해야 합니다.
이미지 롤백은 SQL을 되돌리지 않습니다. 컬럼 추가 등 호환되는 변경과 데이터 이관을 먼저 진행하고,
삭제·타입 변경은 나중 단계로 분리하세요.

## 테스트와 연결 확인

```powershell
.\gradlew.bat --no-daemon check bootJar
```

Docker가 실행 중이면 Testcontainers가 별도 DB를 만듭니다. 로컬 개발 DB와 `.env`는 테스트에 사용하지 않습니다.
실제 PostgreSQL에서 JPA 저장·조회, Flyway 검증·중복 적용 방지와 실패 SQL의 트랜잭션 롤백을 검사합니다.
CSV 전체 행의 SHA-256·좌표계·유한성·방위각 격자를 검사하고, 배치 적재 도중 실패 시 전체 롤백도 검증합니다.
분석 모델은 PostgreSQL enum·JSONB·배열·UTC 시각과 미산출 `NULL`을 실제 저장·조회합니다.
테스트 엔티티와 SQL은 실행 JAR에 포함되지 않습니다.

Hikari 풀은 최대 3개, minimum idle 0입니다. `/health`는 프로세스 응답,
`/actuator/health/readiness`는 DB 연결을 포함합니다. DB 장애 시 readiness는 503,
복구 시 UP으로 돌아오며 상세 연결 정보는 공개하지 않습니다.

## 운영 연결과 앱 이미지

운영 환경변수 주입·DB 보안 그룹·자격증명 회전·리소스 정리는 EC2에서 관리합니다.
관리 위치와 이미지 배포 계약은 [CI/CD 가이드](deployment.md)를 참고하세요.
앱의 환경변수 이름과 이미지 내부 CA 경로는 유지합니다.

`docker/certs/rds-ca-bundle.crt`는 앱 이미지의 RDS TLS 검증용 공개 CA 묶음입니다.
이미지 경로는 `/app/certs/rds-ca-bundle.crt`이며 JDBC의 `sslmode=verify-full`로 서버 이름과 CA를 검증합니다.
출처: https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem
SHA-256: `913fb5b814f17af79d4c1622584a8d0ceddf5b0d76fe353d0c7d1186cdd6b229`.
CA 교체 시 파일과 해시를 함께 갱신하고 연결을 검증하세요.

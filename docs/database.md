# PostgreSQL, JPA와 Flyway 개발

로컬은 Docker PostgreSQL, 테스트는 Testcontainers PostgreSQL, 운영은 RDS를 사용합니다.
앱은 동일한 SPRING_DATASOURCE 환경변수로 연결하며 운영 자격증명은 EC2가 Secrets Manager에서 읽습니다.

## Windows 로컬 실행

JDK 21과 Docker Desktop을 준비한 뒤 저장소 루트에서 실행합니다.

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

```bash
docker compose --env-file docker/postgres.env --file compose.local.yaml up --detach --wait
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

Flyway는 앱 시작 시 `src/main/resources/db/migration/V<번호>__<설명>.sql`을 순서대로 적용합니다.
첫 업무 테이블은 `V1__...sql`과 Entity를 함께 추가하세요. 아직 운영용 업무 SQL은 없습니다.
JPA는 `ddl-auto: validate`, `open-in-view: false`이며 스키마 생성·변경은 Flyway가 담당합니다.
Flyway clean은 비활성화하고 기존 SQL 체크섬을 검증합니다. 적용한 파일은 수정하지 말고 새 버전을 추가하세요.

후보 앱이 시작할 때도 운영 migration이 실행되므로 기존 앱과 호환되는 변경부터 적용해야 합니다.
이미지 롤백은 SQL을 되돌리지 않습니다. 컬럼 추가 등 호환되는 변경과 데이터 이관을 먼저 진행하고,
삭제·타입 변경은 나중 단계로 분리하세요.

## 테스트와 연결 확인

```powershell
.\gradlew.bat --no-daemon check bootJar
```

Docker가 실행 중이면 Testcontainers가 별도 DB를 만듭니다. 로컬 개발 DB와 `.env`는 테스트에 사용하지 않습니다.
실제 PostgreSQL에서 JPA 저장·조회, Flyway 검증·중복 적용 방지와 실패 SQL의 트랜잭션 롤백을 검사합니다.
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

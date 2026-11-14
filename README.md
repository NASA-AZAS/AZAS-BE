# AZAS Backend

NASA Space Apps의 Lunar Landing Site Navigator API입니다. Java 21, Spring Boot 4.1.2,
Gradle Wrapper 9.8.2를 사용합니다. 협업 규칙은
[조직 기여 가이드](https://github.com/NASA-AZAS/.github/blob/main/CONTRIBUTING.md)를 참고하세요.

## 로컬 실행

JDK 21과 Docker Desktop을 준비하고 저장소 루트에서 실행합니다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
.\scripts\run-local.ps1
```

스크립트는 루트의 `compose.local.yaml`로 PostgreSQL을 시작하고 Spring을 실행합니다.
환경변수는 기존 프로세스 값 → `.env` → `.env.example` 순서로 우선 적용합니다.
별도 DB 연결 변수를 설정했다면 `-SkipDatabase`를 사용할 수 있습니다.
Spring 자체와 IDE 직접 실행은 `.env`를 자동으로 읽지 않으므로 실행 설정에 변수를 등록하세요.
Linux/macOS와 IntelliJ 실행 방법은 [DB 개발 가이드](docs/database.md)를 참고하세요.

- Swagger UI: http://127.0.0.1:8000/docs
- OpenAPI: http://127.0.0.1:8000/openapi.json
- 프로세스 응답: http://127.0.0.1:8000/health → `{"status":"ok"}`
- DB 포함 readiness: http://127.0.0.1:8000/actuator/health/readiness → `{"status":"UP"}`

로컬 DB만 시작하거나 중지할 때는 아래 명령을 사용합니다.

```powershell
docker compose --env-file docker/postgres.env --file compose.local.yaml up --detach --wait
docker compose --env-file docker/postgres.env --file compose.local.yaml down
```

DB는 `127.0.0.1:55432`, 데이터베이스 `azas`, 계정 `azas_local` / `azas_local_only`입니다.
Compose 프로젝트명 `azas-local`과 볼륨 `azas-local_postgres-data`를 유지해 기존 로컬 데이터를
계속 사용합니다. `down`으로 중지해도 데이터는 남습니다.

## 빌드와 테스트

```powershell
.\gradlew.bat --no-daemon check bootJar
```

테스트는 Testcontainers로 별도 PostgreSQL을 자동 실행하므로 Docker가 켜져 있어야 합니다.
실행 JAR은 `build/libs/azas-api.jar`에 생성되며 직접 실행할 때도 DB 연결 환경변수가 필요합니다.
의존성은 `build.gradle`, 고정 버전은 `gradle.lockfile`로 관리합니다.
의존성을 변경하면 `check bootJar --write-locks`로 검증하고 잠금 파일을 함께 커밋하세요.

## 구조와 환경 설정

Gradle group과 프로젝트명은 `azas`이며, Java 기본 패키지도 `azas`를 사용합니다.

```text
src/main/java/azas/
  AzasApplication.java
  global/
    config/             # CORS, OpenAPI, 앱 설정
    health/             # /health
    response/           # ApiResponse, ValidationError
    exception/          # ErrorCode, BusinessException, 전역 처리
src/main/resources/
  application.yaml
  db/migration/         # Flyway 업무 SQL
src/test/               # 실제 PostgreSQL 기반 통합 테스트
compose.local.yaml      # 로컬 PostgreSQL 전용
docker/postgres.env     # 로컬·CI PostgreSQL 이미지 digest
docker/certs/           # 앱 이미지의 공개 RDS CA
scripts/run-local.ps1   # 로컬 DB 시작과 환경변수 주입
.github/workflows/ci.yml
```

기능 코드는 `azas.domain.<기능명>` 아래에 추가합니다.
공통 응답·예외 처리 사용법은 [API 개발 규칙](docs/api-conventions.md),
JPA·Flyway 사용법은 [DB 개발 가이드](docs/database.md)를 참고하세요.
요청 ID와 텍스트/JSON 로그 설정은 [로깅 가이드](docs/logging.md)에 정리되어 있습니다.
앱 설정은 `application.yaml`, `AZAS_`와 `SPRING_DATASOURCE_` 환경변수를 사용합니다.
`AZAS_CORS_ORIGINS`는 JSON 배열이며 기본값은 빈 목록입니다.

## 앱 Docker 이미지

Docker Desktop에서 로컬 PostgreSQL을 시작한 뒤 실행합니다.

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose --env-file docker/postgres.env --file compose.local.yaml up --detach --wait
docker build -t azas-api .
docker run --rm --memory 384m --cpus 1 -p 127.0.0.1:8000:8000 --env-file .env --env 'SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:55432/azas?sslmode=disable' azas-api
```

Linux에서는 앱과 DB가 연결 가능한 Docker 네트워크의 DB 주소를 사용하세요.
JDK 단계에서 JAR을 만들고 JRE 이미지의 UID 10001로 실행합니다.
JVM 힙 기본값은 128MB, 컨테이너 메모리 제한은 384MB이며 healthcheck는 DB readiness를 사용합니다.
앱 이미지에는 RDS TLS 검증에 필요한 공개 CA를 포함합니다.

## CI/CD와 운영 설정

[GitHub Actions](https://github.com/NASA-AZAS/AZAS-BE/actions)는 Java·PostgreSQL 통합 테스트,
로컬 Compose, 앱 이미지의 readiness·스키마·실행 권한·DB 장애/복구를 검사합니다.
요청 ID와 텍스트·JSON 요청 로그, 공개 CA 파일도 실제 이미지에서 검증합니다.
앱 변경이 있는 main에서 검증한 이미지를 SHA 태그로 ECR에 올리고 OIDC·제한된 SSM 문서로 EC2에 전달합니다.
`README.md`와 `docs/`의 Markdown만 변경되고 미배포 앱 변경이 없으면 문서 검사만 수행합니다.
main에서 수동 실행하면 기존처럼 전체 검사와 배포를 수행합니다.

로컬 Compose는 이 레포에서, 운영 Compose·Nginx·Certbot·배포 도구는 EC2에서 관리합니다.
서버 관리 자료는 EC2의 `/opt/azas/management/README.md`에 있습니다.
앱과 서버 사이의 약속 및 Actions 변수는 [CI/CD 가이드](docs/deployment.md)를 참고하세요.

- 운영 Swagger UI: https://api.azas.n-e.kr/docs
- 운영 readiness: https://api.azas.n-e.kr/actuator/health/readiness

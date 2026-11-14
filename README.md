# AZAS Backend

NASA Space Apps의 Lunar Landing Site Navigator API입니다. Java 21, Spring Boot 4.1.2,
Gradle Wrapper 9.8.2를 사용합니다. 협업 규칙은
[조직 기여 가이드](https://github.com/NASA-AZAS/.github/blob/main/CONTRIBUTING.md)를 참고하세요.

## 로컬 실행

JDK 21과 Docker Desktop을 설치한 뒤 저장소 루트에서 실행합니다.

```powershell
.\scripts\run-local.ps1
```

스크립트는 로컬 PostgreSQL을 시작하고 `.env.example` 기본값에 `.env`를 덮어씁니다.
이미 설정한 프로세스 환경변수가 가장 우선합니다. 별도 DB를 쓴다면 연결 변수를 설정하고
`-SkipDatabase`로 실행하세요. Spring 자체는 `.env`를 자동으로 읽지 않습니다.
Linux/macOS·IDE 실행은 [DB 가이드](docs/database.md)의 연결 변수를 등록한 뒤
`./gradlew bootRun`을 사용합니다.

- Swagger UI: http://127.0.0.1:8000/docs (Swagger UI로 리다이렉트)
- OpenAPI: http://127.0.0.1:8000/openapi.json
- 배포 헬스 체크: http://127.0.0.1:8000/health → `{"status":"ok"}`
- Actuator 상태: http://127.0.0.1:8000/actuator/health
- DB 포함 배포 준비 상태: http://127.0.0.1:8000/actuator/health/readiness

`/health`는 프로세스 응답을 확인하며 외부 API나 DB 연결을 보장하지 않습니다.
컨테이너 배포 검사는 readiness를 사용해 DB 연결까지 확인합니다.
Actuator는 health만 공개하며 상세 정보는 노출하지 않습니다.

## 빌드와 의존성

```powershell
.\gradlew.bat --no-daemon clean check bootJar
```

의존성은 `build.gradle`에서 관리하고 `gradle.lockfile`로 버전을 고정합니다.
의존성 변경 시 아래 명령으로 잠금 파일을 갱신하고 함께 커밋하세요.

```powershell
.\gradlew.bat --no-daemon clean check bootJar --write-locks
```

Spring Web MVC, Validation, Actuator, Lombok, SpringDoc, JPA, PostgreSQL 드라이버와 Flyway를 포함합니다.
테스트는 Testcontainers로 PostgreSQL을 시작하므로 Docker가 실행 중이어야 합니다.
빌드한 JAR을 직접 실행할 때도 `SPRING_DATASOURCE_*` 연결 변수가 필요합니다.

## 구조와 환경 설정

```text
src/main/java/azas/
  AzasApplication.java
  global/
    config/             # 환경 설정, CORS, OpenAPI
    health/             # 배포용 /health
    response/           # ApiResponse, ValidationError
    exception/          # ErrorCode, BusinessException, 전역 처리
src/main/resources/application.yaml
src/test/java/azas/
deploy/                 # Compose, Nginx, 인증서 갱신 설정
scripts/                # 배포와 통합 검증
infra/cd.yaml           # ECR, GitHub OIDC, SSM 권한
infra/database.yaml     # 비공개 RDS, Secrets Manager, EC2 읽기 권한
```

기능 코드는 구현할 때 `azas.domain.<기능명>` 아래에 controller·service·dto 등을 추가합니다.
공통 응답 형식과 예외 처리 사용법은 [API 개발 규칙](docs/api-conventions.md)을 참고하세요.

앱 설정은 `application.yaml`, `AZAS_`와 `SPRING_DATASOURCE_` 환경변수를 사용합니다.
로컬 기본값은 `.env.example`, 운영 DB 값은 Secrets Manager에서 가져옵니다.
`AZAS_CORS_ORIGINS`는 기존과 같은 JSON 배열이며 기본값은 빈 목록입니다.
허용하지 않은 origin의 preflight는 Spring MVC에서 403을 반환합니다.
운영 API는 Nginx를 통해서만 접근하며 Nginx가 덮어쓴 forwarding headers를 신뢰합니다.

## Docker

```powershell
Copy-Item .env.example .env
docker compose --env-file deploy/postgres.env --file deploy/compose.local.yaml up --detach --wait
docker build -t azas-api .
docker run --rm --memory 384m --cpus 1 -p 127.0.0.1:8000:8000 --env-file .env --env 'SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:55432/azas?sslmode=disable' azas-api
```

JDK 빌드 단계에서 실행 JAR을 만들고 JRE 이미지에서 UID 10001로 실행합니다.
JVM 힙은 기본 128MB로 제한하고 API 컨테이너는 384MB를 사용합니다.
개발 도구, 테스트 클래스, `.env`는 실행 이미지에 포함하지 않습니다.
위 명령은 Docker Desktop 기준입니다. Linux에서는 같은 Docker 네트워크의 DB 주소를 사용하세요.

## CI/CD

[GitHub Actions](https://github.com/NASA-AZAS/AZAS-BE/actions)는 main 푸시, PR, 수동 실행에서
Gradle Wrapper 검증과 `clean check bootJar`를 실행합니다. 이후 운영 Docker 이미지,
384MB 제한에서의 DB 헬스 체크, Compose HTTPS·프록시·환경변수와 이미지 롤백을 검사합니다.
main에서만 검증한 이미지를 SHA 태그로 ECR에 올리고 OIDC·SSM으로 EC2에 배포합니다.

EC2 배포 도구 갱신과 복구 절차는 [배포 가이드](docs/deployment.md)를 참고하세요.
RDS 생성, Secret 주입, SQL 마이그레이션과 종료 후 정리는 [DB 가이드](docs/database.md)를 참고하세요.
HTTPS와 인증서 갱신은 [HTTPS 가이드](docs/https.md)를 참고하세요.

- 운영 Swagger UI: https://api.azas.n-e.kr/docs
- 운영 헬스 체크: https://api.azas.n-e.kr/health

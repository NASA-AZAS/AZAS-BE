# AZAS Backend

NASA Space Apps의 Lunar Landing Site Navigator API입니다. Java 21, Spring Boot 4.1.2,
Gradle Wrapper 9.8.2를 사용합니다. 협업 규칙은
[조직 기여 가이드](https://github.com/NASA-AZAS/.github/blob/main/CONTRIBUTING.md)를 참고하세요.

## 로컬 실행

JDK 21을 설치한 뒤 저장소 루트에서 실행합니다.

```powershell
$env:AZAS_ENVIRONMENT = 'local'
$env:AZAS_CORS_ORIGINS = '["http://localhost:3000","http://localhost:5173"]'
.\gradlew.bat bootRun
```

Linux/macOS에서는 `./gradlew bootRun`을 사용합니다. Spring의 로컬 실행은 `.env`를
자동으로 읽지 않습니다. 환경변수를 셸이나 IDE 실행 설정에 등록하세요.

- Swagger UI: http://127.0.0.1:8000/docs (Swagger UI로 리다이렉트)
- OpenAPI: http://127.0.0.1:8000/openapi.json
- 배포 헬스 체크: http://127.0.0.1:8000/health → `{"status":"ok"}`
- Actuator 상태: http://127.0.0.1:8000/actuator/health

`/health`는 프로세스 응답을 확인하며 외부 API나 DB 연결을 보장하지 않습니다.
Actuator는 health만 공개하며 상세 정보는 노출하지 않습니다.

## 빌드와 의존성

```powershell
.\gradlew.bat --no-daemon clean check bootJar
java -jar build/libs/azas-api.jar
```

의존성은 `build.gradle`에서 관리하고 `gradle.lockfile`로 버전을 고정합니다.
의존성 변경 시 아래 명령으로 잠금 파일을 갱신하고 함께 커밋하세요.

```powershell
.\gradlew.bat --no-daemon clean check bootJar --write-locks
```

Spring Web MVC, Validation, Actuator, Lombok, SpringDoc을 포함합니다.
DB와 인증 관련 의존성은 기능 요구가 확정되면 추가합니다.

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
```

기능 코드는 구현할 때 `azas.domain.<기능명>` 아래에 controller·service·dto 등을 추가합니다.
공통 응답 형식과 예외 처리 사용법은 [API 개발 규칙](docs/api-conventions.md)을 참고하세요.

앱 설정은 `application.yaml`과 `AZAS_` 환경변수를 사용합니다. `.env.example`을 참고하세요.
`AZAS_CORS_ORIGINS`는 기존과 같은 JSON 배열이며 기본값은 빈 목록입니다.
허용하지 않은 origin의 preflight는 Spring MVC에서 403을 반환합니다.
운영 API는 Nginx를 통해서만 접근하며 Nginx가 덮어쓴 forwarding headers를 신뢰합니다.

## Docker

```powershell
Copy-Item .env.example .env
docker build -t azas-api .
docker run --rm --memory 384m --cpus 1 -p 127.0.0.1:8000:8000 --env-file .env azas-api
```

JDK 빌드 단계에서 실행 JAR을 만들고 JRE 이미지에서 UID 10001로 실행합니다.
JVM 힙은 기본 128MB로 제한하고 API 컨테이너는 384MB를 사용합니다.
개발 도구, 테스트 클래스, `.env`는 실행 이미지에 포함하지 않습니다.

## CI/CD

[GitHub Actions](https://github.com/NASA-AZAS/AZAS-BE/actions)는 main 푸시, PR, 수동 실행에서
Gradle Wrapper 검증과 `clean check bootJar`를 실행합니다. 이후 운영 Docker 이미지,
384MB 제한에서의 헬스 체크, Compose HTTPS·프록시·실패 배포 롤백을 검사합니다.
main에서만 검증한 이미지를 SHA 태그로 ECR에 올리고 OIDC·SSM으로 EC2에 배포합니다.

EC2 배포 도구 갱신과 복구 절차는 [배포 가이드](docs/deployment.md)를 참고하세요.
HTTPS와 인증서 갱신은 [HTTPS 가이드](docs/https.md)를 참고하세요.

- 운영 Swagger UI: https://api.azas.n-e.kr/docs
- 운영 헬스 체크: https://api.azas.n-e.kr/health

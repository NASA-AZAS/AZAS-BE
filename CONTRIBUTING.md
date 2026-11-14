# 백엔드 개발 가이드

브랜치·커밋·PR 제목과 리뷰 절차는
[NASA-AZAS 공통 기여 가이드](https://github.com/NASA-AZAS/.github/blob/main/CONTRIBUTING.md)를 따릅니다.

## 코드 작성 기준

- Java 21, Spring Boot 4.1.2, Gradle Wrapper를 사용합니다.
- 패키지는 소문자, 클래스는 PascalCase, 메서드·변수는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용합니다.
- 새 Java 코드는 공백 4칸으로 들여씁니다. 별도 Java 린트·포맷 도구는 아직 도입하지 않았습니다.
- 컨트롤러는 `azas.api`, 공통 설정은 `azas.config`에 둡니다. 기능 API는 `/api/v1` 아래에 추가합니다.
- 요청·응답은 DTO로 정의하고 입력 검증에는 Jakarta Validation을 사용합니다.
- 설정은 `application.yaml`과 `AzasProperties`로 관리하며 기존 `AZAS_` 환경변수를 유지합니다.
- 의존성은 `build.gradle`에서 변경하고 Gradle로 `gradle.lockfile`을 갱신합니다.
- `.env`, 토큰, 개인 키는 커밋하거나 로그에 남기지 않습니다. `.env.example`에는 예시만 작성합니다.

## 검증과 리뷰

테스트는 `src/test/java`에 작성합니다. 기능 변경에는 API 동작을 확인하는 테스트를 추가하며,
외부 NASA API는 실제 서비스나 비밀정보에 의존하지 않도록 대체합니다.
테스트 설정은 `@SpringBootTest(properties = ...)` 등으로 주입합니다.

```powershell
.\gradlew.bat --no-daemon clean check bootJar
```

Linux에서는 `./gradlew --no-daemon clean check bootJar`를 실행합니다.
컨테이너·배포 변경은 Docker 빌드와 `/health`, HTTPS·프록시·롤백 통합 검사도 통과해야 합니다.
`scripts/test-compose-rollback.sh`는 GitHub-hosted Linux runner에서만 실행합니다.
실행하지 못한 검사는 이유를 PR에 적습니다. API·환경변수·운영 절차 변경은 문서와 PR에 명시합니다.

운영 스크립트는 EC2의 root 소유 파일이므로 저장소 변경만으로 자동 갱신되지 않습니다.
첫 Spring 배포 전 `scripts/install-deploy-tools.sh`를 설치 절차에 따라 적용해야 합니다.

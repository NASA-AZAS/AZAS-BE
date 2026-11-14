# 요청 추적과 로그

서버가 HTTP 요청마다 UUID를 생성해 응답의 `X-Request-ID` 헤더와 MDC의 `requestId`에 넣습니다.
클라이언트가 보낸 같은 이름의 헤더는 ID로 사용하지 않습니다. 프론트는 CORS에서 허용된 origin이면
`response.headers.get('X-Request-ID')`로 ID를 읽어 오류 제보에 함께 전달할 수 있습니다.
공통 응답 본문과 HTTP 상태 코드는 기존 형식을 유지합니다.

## 요청 완료 로그

요청 완료 시 `method`, `path`, `status`, `durationMs`를 기록합니다. 요청 ID는 MDC로 함께 출력되어
같은 요청에서 발생한 기존 5xx 예외 로그와 연결됩니다. 예외 스택은 GlobalExceptionHandler에서
기존처럼 한 번 기록하며 요청 요약에는 스택을 추가하지 않습니다.

```text
2026-11-14T16:00:00.000+09:00 INFO [...] [requestId=...] ... : HTTP request completed method="GET" path="/openapi.json" status="200" durationMs="18"
```

로그 수준은 2xx/3xx INFO, 4xx WARN, 5xx ERROR입니다. 처리시간이 기준 이상이면 최소 WARN입니다.
GET/HEAD의 `/health`, `/actuator/health` 및 하위 경로는 정상 2xx이면서 기준 미만일 때만 생략합니다.
실패하거나 느린 헬스체크, OPTIONS 사전 요청, 리다이렉트는 기록합니다.

요청 본문·쿼리 문자열·Authorization·Cookie·응답 본문은 새 요청 요약에 포함하지 않습니다.
메서드와 URI 경로는 제어문자를 치환하고 각각 16자·512자까지 기록한 뒤 잘림 표시를 붙입니다.
경로에는 리소스 식별자가 들어갈 수 있으므로 비밀정보를 URL 경로로 전달하지 마세요.
이 정책은 요청 요약의 수집 범위이며 다른 코드의 임의 로그나 예외 메시지를 전역 마스킹하지 않습니다.
예외 메시지에도 비밀번호·토큰·사용자 입력 원문을 직접 넣지 않습니다.

## 환경변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `AZAS_LOG_LEVEL` | `INFO` | `azas` 패키지 수준. TRACE/DEBUG/INFO/WARN/ERROR/OFF 등 Spring Boot 지원 값 |
| `AZAS_LOG_FORMAT` | 빈 값 | 기본 텍스트. `logstash`를 지정하면 Spring Boot의 JSON 로그 사용 |
| `AZAS_SLOW_REQUEST_MS` | `1000` | 느린 요청 기준. 1 이상 정수이며 잘못된 값은 시작 시 검증 실패 |

로컬 `.env`에서 설정하면 기존 `scripts/run-local.ps1`이 읽습니다.
이미 설정된 프로세스 환경변수가 `.env`보다 우선합니다. 변경 뒤 앱을 다시 시작하세요.

```dotenv
AZAS_LOG_LEVEL=DEBUG
AZAS_LOG_FORMAT=logstash
AZAS_SLOW_REQUEST_MS=1000
```

텍스트 형식은 콘솔 패턴에 요청 ID와 key/value 필드를 표시합니다.
JSON 요청 요약에는 `requestId`, `method`, `path`, 숫자 `status`와 `durationMs`가 들어갑니다.
JVM 안내문이나 시작 배너 등 로거를 거치지 않는 출력은 JSON이 아닐 수 있습니다.
로그는 stdout으로 출력하며 Docker의 수집·회전은 기존 EC2 설정을 사용합니다.
운영 파일과 설정 관리 경계는 [CI/CD 가이드](deployment.md)를 따릅니다.

새 변수는 기본값이 있어 기존 EC2 환경 파일로도 실행할 수 있습니다.
운영 형식·수준을 변경할 때는 관리자가 EC2의 app.env에 설정하고 재배포합니다.
Secrets Manager의 DB 자격증명 형식을 바꿀 필요는 없습니다.

## 비동기와 검증 범위

Servlet REQUEST/ASYNC/ERROR 디스패치에서 같은 요청 ID를 사용합니다. 비동기 요청의 요약은
초기 스레드가 반환된 시점이 아니라 완료 시점의 상태·처리시간으로 한 번 기록합니다.
각 디스패치·완료 콜백이 끝나면 이전 MDC requestId를 복원하고 다른 MDC 항목은 보존합니다.
임의의 Executor/@Async 작업 스레드에 업무 로그 MDC를 자동 전파하는 기능은 별도 설정이 필요합니다.

테스트는 응답 헤더와 로그 연결, 기존 5xx 스택 1회, 동시·연속 요청의 MDC 정리,
비동기 최종 상태·중복 방지, 로그 수준·헬스체크 생략·느린 요청과 민감값 제외를 검사합니다.
CI는 실제 앱 이미지에서 텍스트·JSON 출력을 검사하며 기존 Java/DB/Docker 검증도 유지합니다.

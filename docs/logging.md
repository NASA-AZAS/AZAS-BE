# 요청 추적과 로그

운영 Spring 로그는 EC2에 접속하지 않고 CloudWatch에서 확인할 수 있습니다.
오류를 제보할 때는 **발생 시각과 응답 헤더의 `X-Request-ID`**를 함께 전달하세요.

- [운영 로그 확인](#운영-로그-확인): 콘솔 접속과 최근 로그 조회
- [요청 ID로 검색](#요청-id로-검색): 응답과 서버 로그 연결
- [오류·느린 요청 조회](#오류느린-요청-조회): 자주 쓰는 검색 쿼리
- [검색 결과가 없을 때](#검색-결과가-없을-때): 시간 범위와 로그 수집 정책 확인
- [운영 설정과 자동 배포](#운영-설정과-자동-배포): 보관 기간과 설정 유지 방식

## 운영 로그 확인

| 항목 | 값 |
| --- | --- |
| AWS 계정 | `389930962915` |
| 리전 | 서울 · `ap-northeast-2` |
| 로그 그룹 | `/azas/production/api` |
| 수집 대상 | 운영 Spring 앱 로그. Nginx 로그는 기존 EC2 로컬 수집 유지 |
| 보관 기간 | 3일 |

1. [CloudWatch 콘솔](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2)에 팀에 부여된 AWS 계정으로 로그인합니다.
2. 계정과 리전이 위 표와 일치하는지 확인합니다.
3. **Logs → Logs Insights**에서 `/azas/production/api`를 선택합니다.
4. 조회 시간은 **최근 1시간**, 쿼리 언어는 **Logs Insights QL**로 설정합니다.
5. 아래 쿼리를 붙여 넣고 **Run query**를 누릅니다.

```sql
fields @timestamp, level, requestId, method, path, status, durationMs, message
| sort @timestamp desc
| limit 100
```

JSON 로그의 필드는 자동으로 인식됩니다. `status`는 HTTP 상태 코드,
`durationMs`는 처리 시간(ms)입니다. 시작 로그처럼 HTTP 요청과 무관한 이벤트는
요청 관련 필드가 비어 있을 수 있습니다. 오류 상세는 결과 행을 펼쳐 확인하세요.

## 요청 ID로 검색

브라우저 개발자 도구에서 **Network → 해당 요청 → Response Headers → `X-Request-ID`**를 복사합니다.
터미널에서는 다음 명령으로 응답 헤더를 확인할 수 있습니다.

```powershell
curl.exe --silent --show-error --dump-header - --output NUL https://api.azas.n-e.kr/openapi.json
```

Logs Insights에서 같은 로그 그룹을 선택하고, 아래 `복사한-요청-ID`를 실제 값으로 바꿉니다.
조회 시간 범위에 요청을 보낸 시각이 포함되어 있어야 합니다.

```sql
fields @timestamp, level, requestId, method, path, status, durationMs, @message
| filter requestId = "복사한-요청-ID"
| sort @timestamp asc
| limit 100
```

응답의 ID와 같은 `requestId`를 가진 로그가 시간순으로 표시됩니다.
요청 완료 로그와 같은 ID가 기록된 예외 로그를 함께 확인하세요.
프론트에서 발생한 오류를 공유할 때는 요청 ID, 발생 시각, 요청 경로를 전달하면 됩니다.

## 오류·느린 요청 조회

### ERROR 또는 서버 오류(5xx)

```sql
fields @timestamp, level, requestId, method, path, status, durationMs, @message
| filter level = "ERROR" or status >= 500
| sort @timestamp desc
| limit 100
```

### 처리 시간이 1초 이상인 요청

```sql
fields @timestamp, level, requestId, method, path, status, durationMs
| filter durationMs >= 1000
| sort durationMs desc
| limit 100
```

자주 쓰는 쿼리는 Logs Insights의 **Save**로 저장해 다시 사용할 수 있습니다.
위 쿼리는 모두 Logs Insights QL용이며, 로그 스트림 화면의 필터 입력란과 문법이 다릅니다.

## 검색 결과가 없을 때

- **계정·리전·그룹**: 위 표의 값과 일치하는지 확인합니다. 접근이 거부되면 관리자에게 CloudWatch 로그 조회 권한을 요청하세요.
- **시간 범위**: 요청 발생 시각이 포함되어 있는지, 콘솔의 시간대가 예상과 같은지 확인합니다. 3일이 지난 로그는 보관 대상이 아닙니다.
- **전송 지연**: 요청 직후에는 잠시 기다렸다가 시간 범위를 갱신하고 쿼리를 다시 실행합니다.
- **헬스 체크**: 정상이고 빠른 `/health`, `/actuator/health` 및 하위 경로의 GET/HEAD는 요청 요약을 생략합니다. 확인용 요청은 `/openapi.json`을 사용하세요.
- **수집 범위**: CloudWatch 설정 적용 이후의 운영 Spring 로그를 조회합니다. 이전 컨테이너의 로컬 로그와 Nginx 로그는 이 그룹으로 보내지 않습니다.
- **요청 ID 범위**: ID가 없는 시작 로그나 임의의 비동기 작업 로그는 요청 ID 검색에 포함되지 않을 수 있습니다. 아래 [비동기와 검증 범위](#비동기와-검증-범위)를 참고하세요.

## 운영 설정과 자동 배포

| 설정 | 현재 운영 값·동작 |
| --- | --- |
| Spring 출력 | `AZAS_LOG_FORMAT=logstash`로 JSON 출력, 콘솔 배너 비활성화 |
| 전송 | Docker `awslogs`, `non-blocking`, 전송 버퍼 `4m` |
| 로그 스트림 | `azas-api-<container-id>`. 컨테이너 교체 시 같은 그룹에 새 스트림 생성 |
| 로컬 조회 | Docker 로그 캐시 유지, `10m` × 3개 파일로 회전 |
| 인증 | EC2 역할로 전용 로그 그룹에 기록. 별도 AWS 키 주입 없음 |

자동 배포는 EC2의 운영 Compose를 재사용합니다. DB 환경변수를 새로 주입할 때도
JSON 로그 설정은 보존됩니다. 로그 그룹의 3일 보관 설정도 앱 배포와 별도로 유지됩니다.
배포 전후 로그를 함께 찾으려면 특정 스트림 대신 **로그 그룹 전체**를 선택하세요.

`non-blocking`은 로그 전송 지연이 앱 요청 처리를 막지 않도록 합니다.
버퍼가 가득 차면 로그가 유실될 수 있으며, 로컬 캐시는 전송 재시도를 보장하는 저장소가 아닙니다.
JVM 안내문처럼 Spring 로거를 거치지 않는 출력은 JSON이 아닐 수 있습니다.

운영 설정과 복구용 백업은 EC2에서 관리합니다. 파일 위치와 관리 경계는
[CI/CD 가이드](deployment.md)를 참고하세요.

공식 문서: [Logs Insights 시작하기](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_AnalyzeLogData_RunSampleQuery.html),
[쿼리 문법](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_QuerySyntax.html),
[쿼리 저장](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/CWL_Insights-Saving-Queries.html).

## 요청 ID 생성

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
로그는 stdout으로 출력합니다. 운영 수집·보관 설정은 위 [운영 설정과 자동 배포](#운영-설정과-자동-배포)를 따릅니다.

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

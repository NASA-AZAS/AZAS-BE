# 패키지와 공통 API 규칙

공통 코드는 `azas.global`로 모읍니다. 기능 코드는 구현할 때
`azas.domain.<기능명>` 아래에 controller, service, dto, exception 등을 추가합니다.
현재 사용하지 않는 도메인이나 DB 계층은 미리 만들지 않습니다.

```text
azas/
  AzasApplication.java
  global/
    config/       # AzasProperties, CORS, OpenAPI 설정
    health/       # 배포용 HealthController
    response/     # ApiResponse<T>, ValidationError
    exception/    # ErrorCode, CommonErrorCode, BusinessException, GlobalExceptionHandler
```

예를 들어 착륙지 API를 구현하면 `azas.domain.landingsite.controller`와
`azas.domain.landingsite.service`, `azas.domain.landingsite.dto`를 추가합니다.
기능 컨트롤러의 기본 경로는 `/api/v1`입니다.
Java 코드는 공백 4칸으로 들여쓰고 클래스는 PascalCase, 메서드·변수는 camelCase를 사용합니다.
의존성 변경 시 Gradle로 `gradle.lockfile`을 갱신하고 변경 설정과 함께 커밋합니다.

## 성공 응답

컨트롤러가 응답 DTO를 `ApiResponse.success(data)`로 반환합니다.

```java
return ApiResponse.success(responseDto);
```

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {"name": "AZAS"}
}
```

생성 응답 등은 `ResponseEntity`로 HTTP 상태를 지정합니다.

```java
return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(responseDto));
```

본문 데이터가 없는 성공 응답은 `ApiResponse.successWithoutData()`를 사용하며 `data`는 null입니다.
HTTP 204가 필요한 API는 `ResponseEntity.noContent().build()`로 본문 없이 반환합니다.
`/health`의 `{"status":"ok"}`, Swagger, OpenAPI, Actuator의 정상 응답은 각 형식을 유지합니다.

## 오류 응답

오류는 HTTP 4xx·5xx 상태와 공통 본문을 함께 반환합니다.

```json
{
  "success": false,
  "code": "COMMON_NOT_FOUND",
  "message": "요청한 리소스를 찾을 수 없습니다.",
  "data": null
}
```

| 상황 | HTTP 상태 | 공통 코드 |
| --- | --- | --- |
| 잘못된 JSON, 누락된 본문·필수 파라미터, 타입 오류, 입력 검증 | 400 | `COMMON_INVALID_REQUEST` |
| 존재하지 않는 경로 | 404 | `COMMON_NOT_FOUND` |
| 지원하지 않는 메서드 | 405 | `COMMON_METHOD_NOT_ALLOWED` |
| 지원하지 않는 응답 형식 | 406 | `COMMON_NOT_ACCEPTABLE` |
| 요청 크기 초과 | 413 | `COMMON_PAYLOAD_TOO_LARGE` |
| 지원하지 않는 요청 형식 | 415 | `COMMON_UNSUPPORTED_MEDIA_TYPE` |
| 위에 없는 명시적 HTTP 4xx 오류 | 원래 상태 유지 | `COMMON_REQUEST_FAILED` |
| 예상하지 못한 예외·반환값 검증 실패 | 500 | `COMMON_INTERNAL_ERROR` |
| 일시적 서비스 사용 불가 | 503 | `COMMON_SERVICE_UNAVAILABLE` |

Spring의 다른 명시적 HTTP 5xx 오류도 원래 상태를 유지하고 `COMMON_INTERNAL_ERROR`를 사용합니다.
`Allow`, `Accept`, `Retry-After` 등 Spring 예외가 제공한 응답 헤더를 보존합니다.
오류 본문은 JSON으로 반환하고 원본 예외 메시지·파서 내용·스택은 포함하지 않습니다.
5xx 예외의 원인은 서버 로그에 기록합니다.

도메인별 오류 enum은 `ErrorCode`를 구현해 HTTP 상태, 안정적인 코드, 공개할 메시지를 제공합니다.
오류 코드는 기능을 구분할 수 있는 접두사를 사용합니다. 예: `LANDING_SITE_NOT_FOUND`.

```java
throw new BusinessException(LandingSiteErrorCode.NOT_FOUND);
```

HTTP 상태·코드·메시지는 해당 `ErrorCode`에 선언한 값을 사용합니다.
외부 시스템의 오류나 사용자 입력 문자열을 공개 메시지로 그대로 전달하지 않습니다.

## 입력 검증

요청 DTO에는 Jakarta Validation 제약을 선언하고 컨트롤러의 `@RequestBody`에 `@Valid`를 붙입니다.
쿼리·경로·헤더 파라미터의 제약은 Spring MVC 내장 메서드 검증을 사용합니다.
컨트롤러 클래스에는 AOP 방식의 `@Validated`를 붙이지 않습니다.
이는 [Spring MVC의 검증 방식](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)을 따릅니다.

검증 실패에는 필드와 메시지를 담은 `errors`가 추가됩니다.

```json
{
  "success": false,
  "code": "COMMON_INVALID_REQUEST",
  "message": "올바르지 않은 요청입니다.",
  "data": null,
  "errors": [
    {"field": "name", "message": "이름은 필수입니다."}
  ]
}
```

필드 오류에는 DTO 필드 경로를, 쿼리·경로·헤더 검증에는 공개 파라미터 이름을 사용합니다.
객체 전체 검증은 객체 이름, 파라미터 간 검증은 `request`로 표시합니다.
`errors`가 비어 있으면 해당 필드를 생략하고, 원본 입력값은 포함하지 않습니다.
응답 반환값의 검증 실패는 서버 오류이므로 500으로 처리하고 검증 세부 내용을 공개하지 않습니다.

## 적용 범위와 검사

`GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 확장해 MVC 예외의 상태·헤더를
보존하고 본문을 공통 형식으로 바꿉니다. CORS 사전 요청 거절이나 서블릿 컨테이너·프록시가
직접 생성한 응답은 해당 계층의 정책을 따릅니다.

```powershell
.\gradlew.bat --no-daemon clean check bootJar
```

성공·비즈니스 오류·입력 검증·잘못된 JSON·상태 및 헤더 유지·내부 정보 비노출과 기존
헬스·Swagger 동작을 검사합니다. 테스트용 컨트롤러는 `src/test`에만 등록합니다.

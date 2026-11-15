# API conventions

Feature endpoints use `/api/v1`. Each response includes a tracking ID in the `X-Request-ID` header, which clients can use to trace a request even when it has no response body. [Logging guide](logging.md)

## Response format

A successful feature request returns its result in `data`. The examples below translate the human-readable messages for English readers. The service currently returns those messages in Korean. Use the HTTP status and the stable `code` to decide how to handle a response.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "Request succeeded.",
  "data": {"name": "AZAS"}
}
```

A controller wraps its result with `ApiResponse.success(data)`. For a creation or another operation that needs a specific HTTP status, return a `ResponseEntity`.

```java
return ApiResponse.success(responseDto);
```

```java
return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(responseDto));
```

Use `ApiResponse.successWithoutData()` for a successful envelope with `data=null`. Use `ResponseEntity.noContent().build()` for HTTP 204 with no body. The health endpoint returns `{"status":"ok"}`. Swagger, OpenAPI and Actuator also keep their own response formats.

## Errors

Feature errors return an HTTP 4xx or 5xx status and a JSON body.

```json
{
  "success": false,
  "code": "COMMON_NOT_FOUND",
  "message": "The requested resource was not found.",
  "data": null
}
```

| Condition | HTTP status | Stable code |
| --- | --- | --- |
| Malformed JSON, missing body or required parameter, type mismatch or invalid input | 400 | `COMMON_INVALID_REQUEST` |
| Unknown path | 404 | `COMMON_NOT_FOUND` |
| Unsupported method | 405 | `COMMON_METHOD_NOT_ALLOWED` |
| Unacceptable response format | 406 | `COMMON_NOT_ACCEPTABLE` |
| Payload too large | 413 | `COMMON_PAYLOAD_TOO_LARGE` |
| Unsupported request format | 415 | `COMMON_UNSUPPORTED_MEDIA_TYPE` |
| Other explicit HTTP 4xx error | Original status | `COMMON_REQUEST_FAILED` |
| Unexpected exception or invalid return value | 500 | `COMMON_INTERNAL_ERROR` |
| Service temporarily unavailable | 503 | `COMMON_SERVICE_UNAVAILABLE` |

For other explicit Spring 5xx errors, the handler preserves the status and uses `COMMON_INTERNAL_ERROR`. It also preserves headers such as `Allow`, `Accept` and `Retry-After`. Responses expose a public message. Server logs hold the cause of a 5xx failure, including details unsuitable for clients.

Each domain implements `ErrorCode` to declare a status, stable code and public message. Prefix the code with the feature name so clients can distinguish cases, for example `LANDING_SITE_NOT_FOUND`. Keep upstream exception text and raw user input out of public messages.

```java
throw new BusinessException(LandingSiteErrorCode.NOT_FOUND);
```

## Validation

Use Jakarta Validation on request DTOs and `@Valid` on the controller's request body. For query, path and header parameters, use Spring MVC's built-in method validation. A controller-level `@Validated` would select the AOP mechanism. [Spring MVC validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)

When validation fails, the response can include an `errors` list.

```json
{
  "success": false,
  "code": "COMMON_INVALID_REQUEST",
  "message": "Invalid request.",
  "data": null,
  "errors": [
    {"field": "name", "message": "Name is required."}
  ]
}
```

Each entry identifies a DTO field path or the public query, path or header parameter name. Whole-object constraints use the object name. Constraints involving multiple parameters use `request`. The API omits empty error lists and submitted values. Invalid response values are server errors and return HTTP 500 without validation details.

## Implementation and verification

Shared code lives under `azas.global`. Add feature controllers, services, DTOs and exceptions under `azas.domain` as the feature needs them. For example, landing site controllers live in `azas.domain.landingsite.controller`. Use four-space indentation, PascalCase class names and camelCase method and variable names. When changing dependencies, regenerate the Gradle lockfile and commit it with the configuration.

```text
azas/
  AzasApplication.java
  global/
    config/       # AzasProperties, CORS, OpenAPI configuration
    health/       # deployment health checks
    response/     # ApiResponse<T>, ValidationError
    exception/    # ErrorCode, CommonErrorCode, BusinessException, GlobalExceptionHandler
```

Separate distinct steps such as validation, iteration and returning the result with one blank line. Keep short related statements together, including within `if`, `for`, `while` and `try` blocks. Keep `else`, `catch` and `finally` attached to the preceding block, with no extra blank lines immediately inside braces. Preserve meaningful newlines within SQL and string literals.

```java
List<Result> results = new ArrayList<>();

for (Item item : items) {
    if (!item.isValid()) {
        continue;
    }

    results.add(convert(item));
}

return results;
```

`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`. It preserves Spring MVC status codes and headers while providing the common response body. A CORS preflight rejection or a response generated by the servlet container or proxy follows that component's policy.

```powershell
.\gradlew.bat --no-daemon clean check bootJar
```

The checks cover successful requests, domain errors, validation failures, malformed JSON, status codes and headers, and the health and Swagger endpoints. Test controllers live only under `src/test`.

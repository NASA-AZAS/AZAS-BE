package azas.global;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import azas.global.exception.BusinessException;
import azas.global.exception.ErrorCode;
import azas.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiExceptionHandlingTests.TestController.class)
class ApiExceptionHandlingTests {

    @Autowired
    MockMvc mvc;

    @Test
    void successfulResponsesHaveAStableEnvelope() throws Exception {
        mvc.perform(get("/api/v1/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data.name").value("AZAS"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void successFactoryPreservesTheControllersHttpStatus() throws Exception {
        mvc.perform(post("/api/v1/test/created"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(content().json("""
                        {"success":true,"code":"SUCCESS","message":"요청이 성공했습니다.","data":null}
                        """));
    }

    @Test
    void domainErrorCodesPreserveTheirStatusAndMessage() throws Exception {
        mvc.perform(get("/api/v1/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"success":false,"code":"TEST_SITE_NOT_FOUND","message":"대상을 찾을 수 없습니다.","data":null}
                        """))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void dtoValidationReturnsFieldsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","count":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "count")))
                .andExpect(jsonPath("$.errors[*].message", containsInAnyOrder("이름은 필수입니다.", "개수는 1 이상이어야 합니다.")))
                .andExpect(content().string(not(containsString("rejectedValue"))));
    }

    @Test
    void queryValidationUsesThePublicParameterName() throws Exception {
        mvc.perform(get("/api/v1/test/query").param("site_count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("site_count"))
                .andExpect(jsonPath("$.errors[0].message").value("개수는 1 이상이어야 합니다."));
    }

    @Test
    void nestedBodyErrorsArePreservedDuringMethodValidation() throws Exception {
        mvc.perform(post("/api/v1/test/method-body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "count")));
    }

    @Test
    void pathValidationUsesThePublicVariableName() throws Exception {
        mvc.perform(get("/api/v1/test/sites/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("site_id"));
    }

    @Test
    void headerValidationUsesThePublicHeaderName() throws Exception {
        mvc.perform(get("/api/v1/test/header").header("X-Site-Count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("X-Site-Count"));
    }

    @Test
    void invalidReturnValuesRemainServerErrors() throws Exception {
        mvc.perform(get("/api/v1/test/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/test/query", "/api/v1/test/query?site_count=not-a-number"})
    void missingOrMalformedParametersRemainBadRequests(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("not-a-number"))));
    }

    @Test
    void malformedJsonDoesNotLeakInputOrParserMessages() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sensitive-input-value\",\"count\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("sensitive-input-value"))))
                .andExpect(content().string(not(containsString("Jackson"))));
    }

    @Test
    void modelBindingFailuresDoNotLeakRejectedValues() throws Exception {
        mvc.perform(get("/api/v1/test/model").param("name", "AZAS")
                        .param("count", "sensitive-binding-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("count"))
                .andExpect(jsonPath("$.errors[0].message").value("올바르지 않은 값입니다."))
                .andExpect(content().string(not(containsString("sensitive-binding-value"))));
    }

    @Test
    void missingBodiesRemainBadRequests() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    void unknownRoutesReturnTheCommon404() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void methodErrorsPreserveTheAllowHeader() throws Exception {
        mvc.perform(put("/api/v1/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedRequestMediaTypesReturn415() throws Exception {
        mvc.perform(post("/api/v1/test/body").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", containsString("application/json")))
                .andExpect(jsonPath("$.code").value("COMMON_UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unsupportedResponseMediaTypesReturnJson406() throws Exception {
        mvc.perform(get("/api/v1/test/success").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_NOT_ACCEPTABLE"));
    }

    @Test
    void explicitHttpErrorsKeepTheirStatusWithoutLeakingTheirReason() throws Exception {
        mvc.perform(get("/api/v1/test/status"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("COMMON_REQUEST_FAILED"))
                .andExpect(content().string(not(containsString("sensitive-internal-reason"))));
    }

    @Test
    void unexpectedExceptionsReturnSafe500Responses() throws Exception {
        mvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("sensitive-internal-details"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @RestController
    @RequestMapping("/api/v1/test")
    static class TestController {

        @GetMapping("/success")
        ApiResponse<Map<String, String>> success() {
            return ApiResponse.success(Map.of("name", "AZAS"));
        }

        @PostMapping("/created")
        ResponseEntity<ApiResponse<Void>> created() {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.successWithoutData());
        }

        @GetMapping("/business")
        ApiResponse<Void> business() {
            throw new BusinessException(TestErrorCode.SITE_NOT_FOUND);
        }

        @PostMapping("/body")
        ApiResponse<TestRequest> body(@Valid @RequestBody TestRequest request) {
            return ApiResponse.success(request);
        }

        @PostMapping("/method-body")
        ApiResponse<TestRequest> methodBody(@Valid @NotNull @RequestBody TestRequest request) {
            return ApiResponse.success(request);
        }

        @GetMapping("/model")
        ApiResponse<TestRequest> model(@Valid @ModelAttribute TestRequest request) {
            return ApiResponse.success(request);
        }

        @GetMapping("/query")
        ApiResponse<Integer> query(
                @RequestParam("site_count") @Min(value = 1, message = "개수는 1 이상이어야 합니다.") int count) {
            return ApiResponse.success(count);
        }

        @GetMapping("/sites/{site_id}")
        ApiResponse<Long> site(@PathVariable("site_id") @Min(1) long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/header")
        ApiResponse<Integer> header(@RequestHeader("X-Site-Count") @Min(1) int count) {
            return ApiResponse.success(count);
        }

        @GetMapping("/invalid-return")
        @Min(1)
        Integer invalidReturn() {
            return 0;
        }

        @GetMapping("/status")
        ApiResponse<Void> statusError() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "sensitive-internal-reason") {
                @Override
                public HttpHeaders getHeaders() {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Retry-After", "30");
                    return HttpHeaders.readOnlyHttpHeaders(headers);
                }
            };
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("sensitive-internal-details");
        }
    }

    record TestRequest(
            @NotBlank(message = "이름은 필수입니다.") String name,
            @Min(value = 1, message = "개수는 1 이상이어야 합니다.") int count
    ) {
    }

    enum TestErrorCode implements ErrorCode {
        SITE_NOT_FOUND;

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String code() {
            return "TEST_SITE_NOT_FOUND";
        }

        @Override
        public String message() {
            return "대상을 찾을 수 없습니다.";
        }
    }
}

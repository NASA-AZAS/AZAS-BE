package azas.global.response;

import azas.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        @Nullable T data,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ValidationError> errors
) {

    public ApiResponse {
        errors = List.copyOf(errors);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "SUCCESS", "요청이 성공했습니다.", data, List.of());
    }

    public static ApiResponse<Void> successWithoutData() {
        return new ApiResponse<>(true, "SUCCESS", "요청이 성공했습니다.", null, List.of());
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode) {
        return failure(errorCode, List.of());
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode, List<ValidationError> errors) {
        return new ApiResponse<>(false, errorCode.code(), errorCode.message(), null, errors);
    }
}

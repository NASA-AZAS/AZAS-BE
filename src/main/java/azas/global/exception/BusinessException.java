package azas.global.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).message());

        if (!errorCode.status().isError()) {
            throw new IllegalArgumentException("Business exceptions require an error HTTP status");
        }

        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

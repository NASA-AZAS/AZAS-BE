package azas.global.exception;

import azas.global.response.ApiResponse;
import azas.global.response.ValidationError;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public @Nullable ResponseEntity<Object> handleBusinessException(
            BusinessException exception, WebRequest request) {
        ErrorCode errorCode = exception.errorCode();
        return handleExceptionInternal(exception, ApiResponse.failure(errorCode),
                new HttpHeaders(), errorCode.status(), request);
    }

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpectedException(
            Exception exception, WebRequest request) {
        return handleExceptionInternal(exception, null, new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(this::validationError)
                .toList();
        return handleExceptionInternal(exception,
                ApiResponse.failure(CommonErrorCode.INVALID_REQUEST, errors), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Invalid return values are server faults, not client input errors.
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        List<ValidationError> errors = new ArrayList<>();
        for (var result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getAllErrors().stream().map(this::validationError).forEach(errors::add);
            } else {
                String field = parameterName(result.getMethodParameter());
                result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(field, message(error)))
                        .forEach(errors::add);
            }
        }
        exception.getCrossParameterValidationResults().stream()
                .map(error -> new ValidationError("request", message(error)))
                .forEach(errors::add);
        return handleExceptionInternal(exception,
                ApiResponse.failure(CommonErrorCode.INVALID_REQUEST, errors), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("Server error while handling a request", exception);
        }
        Object response = body instanceof ApiResponse<?> ? body
                : ApiResponse.failure(CommonErrorCode.fromStatus(status));
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
    }

    private ValidationError validationError(ObjectError error) {
        if (error instanceof FieldError fieldError) {
            // Binding failures may include the rejected value in Spring's default message.
            String message = fieldError.isBindingFailure() ? "올바르지 않은 값입니다." : message(error);
            return new ValidationError(fieldError.getField(), message);
        }
        return new ValidationError(error.getObjectName(), message(error));
    }

    private String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "올바르지 않은 값입니다.";
    }

    private String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            String name = annotationName(requestParam.name(), requestParam.value());
            if (!name.isEmpty()) {
                return name;
            }
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String name = annotationName(pathVariable.name(), pathVariable.value());
            if (!name.isEmpty()) {
                return name;
            }
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            String name = annotationName(requestHeader.name(), requestHeader.value());
            if (!name.isEmpty()) {
                return name;
            }
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    private String annotationName(String name, String value) {
        return !name.isEmpty() ? name : value;
    }
}

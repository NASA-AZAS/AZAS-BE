package azas.domain.analysis.exception;

import azas.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AnalysisErrorCode implements ErrorCode {
    TRAJECTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "TRAJECTORY_NOT_FOUND", "궤적 실행을 찾을 수 없습니다."),
    LAUNCH_SITES_INCOMPLETE(HttpStatus.SERVICE_UNAVAILABLE, "LAUNCH_SITE_DATA_INCOMPLETE", "발사장 기준 데이터 적재가 완료되지 않았습니다."),
    LAUNCH_CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "LAUNCH_CANDIDATE_NOT_FOUND", "해당 분석 실행에서 발사 후보를 찾을 수 없습니다."),
    RUN_NOT_SUCCEEDED(HttpStatus.CONFLICT, "ANALYSIS_NOT_SUCCEEDED", "착륙 기회 분석이 성공한 뒤에 궤적을 계산할 수 있습니다."),
    SITE_NOT_FOUND(HttpStatus.NOT_FOUND, "LANDING_SITE_NOT_FOUND", "후보지를 찾을 수 없습니다."),
    DATASET_NOT_FOUND(HttpStatus.NOT_FOUND, "TERRAIN_DATASET_NOT_FOUND", "지형 데이터셋을 찾을 수 없습니다."),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "분석 실행을 찾을 수 없습니다."),
    TYPE_MISMATCH(HttpStatus.CONFLICT, "ANALYSIS_TYPE_MISMATCH", "착륙 기회 분석 실행을 지정해 주세요."),
    BUSY(HttpStatus.TOO_MANY_REQUESTS, "ANALYSIS_CAPACITY_EXCEEDED", "분석 요청이 많습니다. 잠시 후 다시 요청해 주세요."),
    INVALID_TERRAIN(HttpStatus.UNPROCESSABLE_ENTITY, "ANALYSIS_TERRAIN_INVALID", "분석 가능한 지형 데이터가 아닙니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
    AnalysisErrorCode(HttpStatus status, String code, String message) {
        this.status = status; this.code = code; this.message = message;
    }

    @Override public HttpStatus status() { return status; }
    @Override public String code() { return code; }
    @Override public String message() { return message; }
}

package azas.domain.analysis.controller;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.dto.LaunchTrajectoryResponse;
import azas.domain.analysis.service.LaunchOptionService;
import azas.domain.analysis.service.LaunchTrajectoryService;
import azas.domain.analysis.dto.AnalysisRequest;
import azas.domain.analysis.dto.AnalysisResponses;
import azas.domain.analysis.dto.DateOpportunityRequest;
import azas.domain.analysis.service.AnalysisJobs;
import azas.domain.analysis.service.AnalysisQueryService;
import azas.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Analyses", description = "후보지 기간별 가시성 분석")
public class AnalysisController {
    private final LaunchOptionService launchOptions;
    private final LaunchTrajectoryService trajectories;
    private final AnalysisJobs jobs;
    private final AnalysisQueryService query;
    @PostMapping("/landing-sites/{id}/analyses")
    @Operation(summary = "분석 요청", description = "UTC 기준 시작일과 종료일을 모두 포함한 1~31일을 분석합니다. 접수 후 실행 상태를 조회하세요.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "분석 접수")
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> create(@PathVariable @Positive long id,
            @Valid @RequestBody AnalysisRequest request) {
        UUID runId = jobs.submit(id, request);
        return accepted(runId);
    }

    @PostMapping("/landing-sites/{id}/date-opportunities")
    @Operation(summary = "착륙 기회 분석 요청", description = "후보지 PK와 UTC 마감일, 표면활동 시간으로 접지 가능 구간을 탐색합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "분석 접수")
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> createOpportunities(@PathVariable @Positive long id,
            @Valid @RequestBody DateOpportunityRequest request) {
        return accepted(jobs.submitOpportunities(id, request));
    }

    private ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> accepted(UUID runId) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/analyses/" + runId))
                .body(ApiResponse.success(new AnalysisResponses.Accepted(runId, "PENDING")));
    }

    @GetMapping("/analyses/{runId}")
    @Operation(summary = "분석 실행 상태 조회")
    public ApiResponse<AnalysisResponses.Run> get(@PathVariable UUID runId) { return ApiResponse.success(query.get(runId)); }

    @GetMapping("/analyses/{runId}/date-opportunities")
    @Operation(summary = "착륙 가능 구간 및 접지 후보 조회")
    public ApiResponse<AnalysisResponses.Opportunities> opportunities(@PathVariable UUID runId) {
        return ApiResponse.success(query.opportunities(runId));
    }
    @GetMapping("/analyses/{runId}/daily-results")
    @Operation(summary = "일별 결과 조회", description = "date를 지정하면 해당 UTC 날짜만 조회합니다. 진행 중이거나 결과가 없는 날짜는 빈 배열입니다.")
    public ApiResponse<List<AnalysisResponses.Day>> days(@PathVariable UUID runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(query.days(runId, date));
    }

    @GetMapping("/analyses/{runId}/launch-options")
    @Operation(summary = "첫 접지 시각 기준 발사장별 후보 조회", description = "착륙 기회 분석 실행 ID로 각 발사장의 첫 기하학적 후보를 조회합니다.")
    public ApiResponse<LaunchOptionsResponse> launchOptions(@PathVariable UUID runId) {
        return ApiResponse.success(launchOptions.get(runId));
    }

    @PostMapping("/analyses/{runId}/launch-options/{candidateId}/trajectory")
    @Operation(summary = "선택한 발사 후보의 예비 달 전이 궤적 계산")
    public ApiResponse<LaunchTrajectoryResponse> trajectory(@PathVariable UUID runId, @PathVariable String candidateId) {
        return ApiResponse.success(trajectories.calculate(runId, candidateId));
    }

    @GetMapping("/analyses/{runId}/trajectories/{trajectoryId}")
    @Operation(summary = "저장된 궤적 실행 및 표본 조회")
    public ApiResponse<LaunchTrajectoryResponse> storedTrajectory(@PathVariable UUID runId, @PathVariable UUID trajectoryId) {
        return ApiResponse.success(trajectories.get(runId, trajectoryId));
    }

    @GetMapping("/analyses/{runId}/trajectories")
    @Operation(summary = "최근 궤적 실행 100건 조회")
    public ApiResponse<List<LaunchTrajectoryResponse.Summary>> trajectoryHistory(@PathVariable UUID runId) {
        return ApiResponse.success(trajectories.list(runId));
    }
}

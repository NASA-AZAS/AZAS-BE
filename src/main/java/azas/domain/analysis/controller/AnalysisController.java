package azas.domain.analysis.controller;

import azas.domain.analysis.dto.LaunchOptionsResponse;
import azas.domain.analysis.dto.LaunchTrajectoryResponse;
import azas.domain.analysis.dto.PostLandingResponse;
import azas.domain.analysis.service.PostLandingService;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Tag(name = "Analyses", description = "후보지 가시성·착륙 기회 분석과 발사 후보별 예비 궤적 계산·조회")
public class AnalysisController {
    private final LaunchOptionService launchOptions;
    private final LaunchTrajectoryService trajectories;
    private final AnalysisJobs jobs;
    private final AnalysisQueryService query;
    private final PostLandingService postLanding;
    @PostMapping("/landing-sites/{id}/analyses")
    @Operation(summary = "분석 요청", description = "UTC 기준 시작일과 종료일을 모두 포함한 1~31일을 분석합니다. 접수 후 실행 상태를 조회하세요.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "분석 접수")
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> create(@PathVariable @Positive long id,
            @Valid @RequestBody AnalysisRequest request) {
        UUID runId = jobs.submit(id, request);
        return accepted(runId);
    }

    @PostMapping("/landing-sites/{id}/date-opportunities")
    @Operation(summary = "착륙 기회 분석 요청", description = """
            후보지 목록의 landingSiteId를 사용합니다. 2027-01-01부터 UTC 마감일까지 10분 간격의 관측값을 읽습니다.
            날짜만 보내면 해당 날짜 23:59 UTC가 마감입니다. 기본 조건은 태양·지구 모두 VISIBLE, 활동 48시간, 후보 간격 10분, 최대 50개입니다.
            timeStepMinutes(1~1440)는 후보 탐색 간격이며 maxCandidates(1~10000)는 평가할 개별 후보의 상한입니다.
            개별 후보의 전이를 평가하려면 departureState, arrivalTargetRule, transferDurationHours, preLandingDelayHours, lambert를 함께 보내세요.
            202 응답의 data.analysisRunId로 실행 상태를 조회한 뒤 SUCCEEDED이면 date-opportunities 결과를 조회하세요.
            """, requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DateOpportunityRequest.class),
                    examples = {
                        @ExampleObject(name = "기본 조건", value = """
                                {"missionDeadlineUtc":"2027-01-31"}
                                """),
                        @ExampleObject(name = "지구가 차폐된 구간에서 1시간 활동", value = """
                                {"missionDeadlineUtc":"2027-01-31","surfaceDurationHours":1,
                                 "surfaceCriteria":{"sun":"ANY","earth":"OCCULTED","unknownPolicy":"RECORD_UNKNOWN"},
                                 "timeStepMinutes":60,"maxCandidates":7}
                                """)
                    })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "분석 접수", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> createOpportunities(@PathVariable @Positive long id,
            @Valid @RequestBody DateOpportunityRequest request) {
        return accepted(jobs.submitOpportunities(id, request));
    }

    private ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> accepted(UUID runId) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/analyses/" + runId))
                .body(ApiResponse.success(new AnalysisResponses.Accepted(runId, "PENDING")));
    }

    @GetMapping("/analyses/{runId}")
    @Operation(summary = "분석 실행 상태 조회", description = """
            POST 응답의 Location으로 접수한 실행을 조회합니다. PENDING은 대기, RUNNING은 계산 중입니다.
            SUCCEEDED이면 결과를 조회하고, FAILED이면 failureCode를 확인하세요. overallResultStatus는 결과의 완전성을 나타냅니다.
            """)
    public ApiResponse<AnalysisResponses.Run> get(@PathVariable UUID runId) { return ApiResponse.success(query.get(runId)); }

    @GetMapping("/analyses/{runId}/date-opportunities")
    @Operation(summary = "착륙 가능 구간 및 접지 후보 조회", description = """
            windows는 표면 활동이 가능한 접지 구간과 그 판정(surfaceStatus)을 담습니다.
            candidates는 구간에서 개별 평가한 후보입니다. 각 후보의 status, lambertStatus, reasons를 함께 읽으세요.
            전이 입력을 생략하면 후보는 ENVIRONMENT_ONLY이고 reasons에 TRANSFER_INPUTS_MISSING이 들어갑니다.
            earliestTouchdownUtc는 첫 SATISFIED 구간의 시작이며, 만족 구간이 없으면 null입니다.
            TRUNCATED인 실행도 전체 구간을 반환합니다. maxCandidates는 개별 후보 수에 적용됩니다.
            """)
    public ApiResponse<AnalysisResponses.Opportunities> opportunities(@PathVariable UUID runId) {
        return ApiResponse.success(query.opportunities(runId));
    }
    @GetMapping("/analyses/{runId}/surface-candidates/{candidateId}/post-landing")
    @Operation(summary = "선택한 접지 후보에서 본 지구·태양의 하늘 경로", description = """
            date-opportunities의 candidates[].candidateId를 사용합니다. 접지부터 활동 종료까지 실제 10분 격자 표본을 반환합니다.
            earth와 sun의 방위각은 현지 북쪽부터 시계 방향, 고도각은 현지 수평면 기준(도)입니다. 중심 고도가 지형 지평선보다 높으면 visible=true입니다.
            화면의 중간 위치는 프론트에서 주변 10개 표본의 방위각·고도각을 9차 라그랑주 보간합니다. 방위각의 0°/360° 경계는 연속 각도로 펼칩니다.
            표본이 2~9개이면 선형 보간하며, 가시 여부와 차폐 시각은 원본 표본의 판정을 사용합니다. 구현 예시는 저장소 docs/analysis-api.md에 있습니다.
            다음 차폐는 접지 이후 첫 차폐 표본(접지 포함)이며 관측 아카이브 끝까지 차폐가 없으면 null입니다.
            평균은 2027~2028년 UTC 일별 가시율의 백분율입니다. 미등록 후보는 404, 분석 유형·상태 불일치는 409, 관측 누락은 503입니다.
            """)
    public ApiResponse<PostLandingResponse> postLanding(@PathVariable UUID runId,
            @Parameter(description = "같은 실행의 접지 후보 ID") @PathVariable String candidateId) {
        return ApiResponse.success(postLanding.get(runId, candidateId));
    }

    @GetMapping("/analyses/{runId}/daily-results")
    @Operation(summary = "일별 결과 조회", description = "date를 지정하면 해당 UTC 날짜만 조회합니다. 진행 중이거나 결과가 없는 날짜는 빈 배열입니다.")
    public ApiResponse<List<AnalysisResponses.Day>> days(@PathVariable UUID runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(query.days(runId, date));
    }

    @GetMapping("/analyses/{runId}/launch-options")
    @Operation(summary = "첫 접지 시각 기준 발사장별 후보 조회", description = """
            착륙 기회 분석의 첫 SATISFIED 접지 시각을 기준으로 계산한 발사장별 후보를 읽습니다.
            launchSites[].candidates는 발사장마다 0개 또는 1개입니다. 궤적 POST에는 여기서 얻은 candidateId를 전달하세요.
            calculatedAt은 발사 후보 계산 시각입니다. V6 이전 실행은 첫 조회 시점의 발사장 좌표를 사용합니다.
            """)
    public ApiResponse<LaunchOptionsResponse> launchOptions(@PathVariable UUID runId) {
        return ApiResponse.success(launchOptions.get(runId));
    }

    @PostMapping("/analyses/{runId}/launch-options/{candidateId}/trajectory")
    @Operation(summary = "선택한 발사 후보의 예비 달 전이 궤적 계산", description = """
            성공한 착륙 기회 분석의 발사 후보를 선택합니다. 요청 본문은 없습니다.
            호출할 때마다 새 trajectoryRunId로 계산하고 결과를 저장한 뒤 동기 응답합니다. 같은 결과를 다시 보려면 상세 GET을 사용하세요.
            HTTP 200에서도 data.status가 FAILED일 수 있습니다. 이때 reason에 계산 실패 사유가 들어갑니다.
            404는 실행·후보가 없을 때, 409는 분석 종류·실행 상태가 맞지 않을 때, 429는 작업 한도 초과,
            503은 필요한 발사장 데이터가 비어 있을 때 반환합니다.
            """)
    public ApiResponse<LaunchTrajectoryResponse> trajectory(@PathVariable UUID runId,
            @Parameter(description = "같은 분석의 launchSites[].candidates[].candidateId", example = "Kennedy-1799883120")
            @PathVariable String candidateId) {
        return ApiResponse.success(trajectories.calculate(runId, candidateId));
    }

    @GetMapping("/analyses/{runId}/trajectories/{trajectoryId}")
    @Operation(summary = "저장된 궤적 실행 및 표본 조회", description = """
            같은 분석에 속한 trajectoryRunId로 저장된 결과를 조회합니다. 외부 API 호출은 발생하지 않습니다.
            PENDING·RUNNING·FAILED이면 samples는 빈 배열입니다. SUCCEEDED이면 status를 확인하세요.
            PRELIMINARY는 TLI부터 달 도착까지의 표본을 반환하고 NO_PRELIMINARY_SOLUTION은 빈 배열과 사유를 반환합니다.
            두 ID가 일치하는 기록이 없으면 HTTP 404 TRAJECTORY_NOT_FOUND입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장된 실행의 상태와 결과", useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 분석의 궤적 기록 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                    examples = @ExampleObject(name = "궤적 기록 없음", value = """
                            {"success":false,"code":"TRAJECTORY_NOT_FOUND","message":"궤적 실행을 찾을 수 없습니다.","data":null}
                            """)))
    public ApiResponse<LaunchTrajectoryResponse> storedTrajectory(@PathVariable UUID runId, @PathVariable UUID trajectoryId) {
        return ApiResponse.success(trajectories.get(runId, trajectoryId));
    }

    @GetMapping("/analyses/{runId}/trajectories")
    @Operation(summary = "최근 궤적 실행 100건 조회", description = """
            분석 실행의 궤적 이력을 접수 시각(createdAt) 내림차순으로 최대 100건 조회합니다.
            목록은 상태와 시각을 담은 요약입니다. 위치·속도 표본은 각 trajectoryRunId의 상세 GET으로 조회하세요.
            기존 분석에 이력이 없으면 HTTP 200과 빈 배열, 분석 ID가 없으면 HTTP 404 ANALYSIS_NOT_FOUND입니다.
            """)
    public ApiResponse<List<LaunchTrajectoryResponse.Summary>> trajectoryHistory(@PathVariable UUID runId) {
        return ApiResponse.success(trajectories.list(runId));
    }
}

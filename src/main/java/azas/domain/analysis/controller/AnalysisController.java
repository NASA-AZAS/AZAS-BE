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
import azas.global.exception.BusinessException;
import azas.global.exception.CommonErrorCode;
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
import java.math.BigDecimal;
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
@Tag(name = "Analyses", description = "Visibility, landing windows and preliminary Earth-to-Moon trajectories")
public class AnalysisController {
    private final LaunchOptionService launchOptions;
    private final LaunchTrajectoryService trajectories;
    private final AnalysisJobs jobs;
    private final AnalysisQueryService query;
    private final PostLandingService postLanding;
    @PostMapping("/landing-sites/{id}/analyses")
    @Operation(summary = "Start a visibility analysis", description = "Check visibility for 1 to 31 consecutive UTC dates, including both the start and end dates. Follow the Location header to check progress.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Analysis accepted")
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> create(@PathVariable @Positive long id,
            @Valid @RequestBody AnalysisRequest request) {
        UUID runId = jobs.submit(id, request);
        return accepted(runId);
    }

    @PostMapping("/landing-sites/{id}/date-opportunities")
    @Operation(summary = "Find landing windows before a deadline", description = """
            Choose a site using the ID from the landing site list. The search reads observations every ten minutes from 1 January 2027 to the UTC deadline.
            A date without a time means 23:59 UTC that day. By default, both the Sun and Earth must be visible for 48 hours at a viewing height of one metre.
            Candidates are tested every ten minutes, up to 50 per run. Set timeStepMinutes (1–1440) and maxCandidates (1–10000) to change these limits.
            To evaluate a transfer as well, supply departureState, arrivalTargetRule, transferDurationHours, preLandingDelayHours and lambert together.
            The 202 response includes data.analysisRunId. Follow the Location header and retrieve the landing windows once the run succeeds.
            """, requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DateOpportunityRequest.class),
                    examples = {
                        @ExampleObject(name = "Default landing conditions", value = """
                                {"missionDeadlineUtc":"2027-01-31"}
                                """),
                        @ExampleObject(name = "One hour with Earth below the horizon", value = """
                                {"missionDeadlineUtc":"2027-01-31","surfaceDurationHours":1,
                                 "surfaceCriteria":{"sun":"ANY","earth":"OCCULTED","unknownPolicy":"RECORD_UNKNOWN"},
                                 "timeStepMinutes":60,"maxCandidates":7}
                                """)
                    })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Analysis accepted", useReturnTypeSchema = true)
    public ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> createOpportunities(@PathVariable @Positive long id,
            @Parameter(description = "Viewing height above the lunar surface in metres. At other heights, the model adjusts the horizon using the same terrain obstruction.")
            @RequestParam(defaultValue = "1") double observerHeightAboveSurfaceMeters,
            @Parameter(description = "Time needed on the surface in hours. This query parameter takes precedence over the request body. The default is 48 hours.")
            @RequestParam(required = false) BigDecimal surfaceDurationHours,
            @Valid @RequestBody DateOpportunityRequest request) {
        var effective = request.withSurfaceOptions(surfaceDurationHours, observerHeightAboveSurfaceMeters);
        if (!Double.isFinite(observerHeightAboveSurfaceMeters) || observerHeightAboveSurfaceMeters < 0
                || !effective.isValidDuration()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return accepted(jobs.submitOpportunities(id, effective));
    }

    private ResponseEntity<ApiResponse<AnalysisResponses.Accepted>> accepted(UUID runId) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/analyses/" + runId))
                .body(ApiResponse.success(new AnalysisResponses.Accepted(runId, "PENDING")));
    }

    @GetMapping("/analyses/{runId}")
    @Operation(summary = "Check the status of an analysis", description = """
            Follow the Location header returned when the analysis was accepted. PENDING means queued and RUNNING means work has started.
            Read the results after SUCCEEDED, or check failureCode after FAILED. overallResultStatus tells you how complete the results are.
            """)
    public ApiResponse<AnalysisResponses.Run> get(@PathVariable UUID runId) { return ApiResponse.success(query.get(runId)); }

    @GetMapping("/analyses/{runId}/date-opportunities")
    @Operation(summary = "Get landing windows and individual touchdown candidates", description = """
            Each window gives a range of possible touchdown times and a surfaceStatus. earliestTouchdownUtc is the start of the first
            SATISFIED window, or null if none qualifies. The response includes all windows even when the run is TRUNCATED.
            The candidate limit applies to individually evaluated touchdown times. Check each candidate's status, lambertStatus and reasons.
            If you omit the transfer inputs, its status is ENVIRONMENT_ONLY and its reasons include TRANSFER_INPUTS_MISSING.
            """)
    public ApiResponse<AnalysisResponses.Opportunities> opportunities(@PathVariable UUID runId) {
        return ApiResponse.success(query.opportunities(runId));
    }
    @GetMapping("/analyses/{runId}/surface-candidates/{candidateId}/post-landing")
    @Operation(summary = "See the Sun and Earth from a touchdown site", description = """
            Select a touchdown candidate from date-opportunities (candidates[].candidateId). The samples follow the Sun and Earth from touchdown
            to the end of surface activity on the original ten-minute UTC grid. Azimuth is clockwise from local north. Elevation is measured
            from the local horizontal in degrees. A body is visible when its centre is above the terrain skyline in that direction.
            For a smooth sky track, unwrap azimuth across 0/360 degrees and interpolate ten adjacent samples with a ninth-degree Lagrange polynomial.
            Use linear interpolation for two to nine samples. Read visibility and occultation from the observations. See docs/analysis-api.md for an example.
            The next occultation is the first blocked sample at or after touchdown, or null if the archive ends first.
            The mean visibility percentages use daily UTC values for 2027–2028. An unknown candidate returns 404, an incompatible run returns 409,
            and missing observations return 503.
            """)
    public ApiResponse<PostLandingResponse> postLanding(@PathVariable UUID runId,
            @Parameter(description = "Touchdown candidate returned by this analysis.") @PathVariable String candidateId) {
        return ApiResponse.success(postLanding.get(runId, candidateId));
    }

    @GetMapping("/analyses/{runId}/daily-results")
    @Operation(summary = "Get daily visibility results", description = "Pass a UTC date to read one day. The list is empty while the analysis runs or if that date has no results.")
    public ApiResponse<List<AnalysisResponses.Day>> days(@PathVariable UUID runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(query.days(runId, date));
    }

    @GetMapping("/analyses/{runId}/launch-options")
    @Operation(summary = "Compare launch sites for the first possible touchdown", description = """
            Return the launch options calculated for the start of the first landing window that satisfies the surface requirements.
            Each launch site has zero or one candidate. Use that candidateId when requesting a trajectory.
            calculatedAt records when the options were calculated. Analyses created before schema version 6 use site coordinates from the first lookup.
            """)
    public ApiResponse<LaunchOptionsResponse> launchOptions(@PathVariable UUID runId) {
        return ApiResponse.success(launchOptions.get(runId));
    }

    @PostMapping("/analyses/{runId}/launch-options/{candidateId}/trajectory")
    @Operation(summary = "Calculate a preliminary flight path to the Moon", description = """
            Select a launch option from a successful landing-window analysis and send an empty request body.
            Each POST creates a new trajectoryRunId, saves its result and then returns. Use the detail GET to reopen the same result.
            HTTP 200 can include a failed calculation. Check data.status and reason. An unknown run or candidate returns 404,
            an incompatible analysis type or state returns 409, a full queue returns 429, and missing launch site data returns 503.
            """)
    public ApiResponse<LaunchTrajectoryResponse> trajectory(@PathVariable UUID runId,
            @Parameter(description = "Launch candidate from the selected site in this analysis (launchSites[].candidates[].candidateId).", example = "Kennedy-1799883120")
            @PathVariable String candidateId) {
        return ApiResponse.success(trajectories.calculate(runId, candidateId));
    }

    @GetMapping("/analyses/{runId}/trajectories/{trajectoryId}")
    @Operation(summary = "Retrieve a saved trajectory", description = """
            Use the trajectoryRunId from this analysis to read its stored result without calling Horizons again.
            The samples array is empty while the job is PENDING or RUNNING and when it has FAILED. After SUCCEEDED, inspect status.
            PRELIMINARY includes samples from translunar injection (TLI) to lunar arrival. NO_PRELIMINARY_SOLUTION includes an empty list and a reason.
            A trajectory that does not belong to the specified analysis returns HTTP 404 (TRAJECTORY_NOT_FOUND).
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Status and results of the saved trajectory calculation", useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No matching trajectory for this analysis. Example message shown in English.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                    examples = @ExampleObject(name = "Trajectory not found", value = """
                            {"success":false,"code":"TRAJECTORY_NOT_FOUND","message":"Trajectory run not found.","data":null}
                            """)))
    public ApiResponse<LaunchTrajectoryResponse> storedTrajectory(@PathVariable UUID runId, @PathVariable UUID trajectoryId) {
        return ApiResponse.success(trajectories.get(runId, trajectoryId));
    }

    @GetMapping("/analyses/{runId}/trajectories")
    @Operation(summary = "List the 100 most recent trajectory calculations", description = """
            Return up to 100 calculations for this analysis, newest first by submission time (createdAt).
            Each summary includes status and times. Use its trajectoryRunId to retrieve position and velocity samples.
            An analysis with no trajectory history returns HTTP 200 and an empty list. An unknown analysis returns HTTP 404 (ANALYSIS_NOT_FOUND).
            """)
    public ApiResponse<List<LaunchTrajectoryResponse.Summary>> trajectoryHistory(@PathVariable UUID runId) {
        return ApiResponse.success(trajectories.list(runId));
    }
}

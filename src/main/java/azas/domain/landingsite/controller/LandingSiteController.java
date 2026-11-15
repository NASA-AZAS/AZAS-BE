package azas.domain.landingsite.controller;

import azas.domain.landingsite.dto.LandingSiteDetailResponse;
import azas.domain.landingsite.dto.LandingSiteResponse;
import azas.domain.landingsite.service.LandingSiteService;
import azas.global.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/landing-sites")
@RequiredArgsConstructor
@Tag(name = "Landing sites", description = "착륙 후보지 조회")
public class LandingSiteController {
    private final LandingSiteService service;
    @GetMapping
    @Operation(summary = "후보지 목록 조회")
    public ApiResponse<List<LandingSiteResponse>> list(@RequestParam(required = false) @Positive Long terrainDatasetId) {
        return ApiResponse.success(service.list(terrainDatasetId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "후보지 상세 조회")
    public ApiResponse<LandingSiteDetailResponse> get(@PathVariable @Positive long id) {
        return ApiResponse.success(service.get(id));
    }
}

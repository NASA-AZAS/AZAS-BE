package azas.domain.landingsite.service;

import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.analysis.service.MeanDailyVisibilityService;
import azas.domain.landingsite.dto.LandingSiteDetailResponse;
import azas.domain.landingsite.dto.LandingSiteResponse;
import azas.domain.landingsite.dto.MeanDailyVisibility;
import azas.domain.landingsite.repository.LandingSiteRepository;
import azas.domain.landingsite.repository.TerrainDatasetRepository;
import azas.global.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LandingSiteService {
    private final LandingSiteRepository sites;
    private final TerrainDatasetRepository datasets;
    private final MeanDailyVisibilityService visibility;

    public List<LandingSiteResponse> list(Long datasetId) {
        var dataset = datasetId == null
                ? datasets.findByDatasetCodeAndDatasetVersion("landing-screen", "2026-11-14-v1")
                : datasets.findById(datasetId);
        var selected = dataset.orElseThrow(() -> new BusinessException(AnalysisErrorCode.DATASET_NOT_FOUND));

        return sites.findByTerrainDatasetIdOrderBySiteCode(selected.getId()).stream().map(LandingSiteResponse::from).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LandingSiteDetailResponse get(long id) {
        var site = sites.findById(id).orElseThrow(() -> new BusinessException(AnalysisErrorCode.SITE_NOT_FOUND));
        MeanDailyVisibility mean;
        try {
            mean = visibility.atReferenceHeight(site.getId());
        } catch (BusinessException ex) {
            if (ex.errorCode() != AnalysisErrorCode.OBSERVER_DATA_INCOMPLETE && ex.errorCode() != AnalysisErrorCode.INVALID_TERRAIN) throw ex;
            // Metadata is available even for datasets without a complete visibility archive.
            mean = null;
        }
        return new LandingSiteDetailResponse(LandingSiteResponse.from(site), mean);
    }
}

package azas.domain.landingsite.service;

import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.landingsite.dto.LandingSiteResponse;
import azas.domain.landingsite.repository.LandingSiteRepository;
import azas.domain.landingsite.repository.TerrainDatasetRepository;
import azas.global.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LandingSiteService {
    private final LandingSiteRepository sites;
    private final TerrainDatasetRepository datasets;

    public List<LandingSiteResponse> list(Long datasetId) {
        var dataset = datasetId == null
                ? datasets.findByDatasetCodeAndDatasetVersion("landing-screen", "2026-11-14-v1")
                : datasets.findById(datasetId);
        var selected = dataset.orElseThrow(() -> new BusinessException(AnalysisErrorCode.DATASET_NOT_FOUND));

        return sites.findByTerrainDatasetIdOrderBySiteCode(selected.getId()).stream().map(LandingSiteResponse::from).toList();
    }

    public LandingSiteResponse get(long id) {
        return LandingSiteResponse.from(sites.findById(id)
                .orElseThrow(() -> new BusinessException(AnalysisErrorCode.SITE_NOT_FOUND)));
    }
}

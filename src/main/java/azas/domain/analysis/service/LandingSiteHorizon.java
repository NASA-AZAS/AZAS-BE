package azas.domain.analysis.service;

import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.domain.landingsite.repository.HorizonSampleRepository;
import azas.global.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class LandingSiteHorizon {
    private final HorizonSampleRepository samples;

    List<Double> elevations(long siteId) {
        var rows = samples.findByIdLandingSiteIdOrderByIdAzimuthIndex(siteId);
        if (rows.size() != 3600) throw new BusinessException(AnalysisErrorCode.INVALID_TERRAIN);
        var elevations = new ArrayList<Double>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            double elevation = row.getTerrainHorizonDegrees();
            if (row.getId().getAzimuthIndex() != i || Math.abs(row.getAzimuthDegrees() - i / 10.0) > 1e-8
                    || !Double.isFinite(elevation) || Math.abs(elevation) > 90) {
                throw new BusinessException(AnalysisErrorCode.INVALID_TERRAIN);
            }
            elevations.add(elevation);
        }
        return elevations;
    }
}

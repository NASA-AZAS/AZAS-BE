package azas.domain.landingsite.dto;

import azas.domain.landingsite.entity.LandingSite;

public record LandingSiteResponse(Long landingSiteId, String siteCode, Long terrainDatasetId, String datasetVersion,
        double latitudeDegrees, double longitudeDegrees, double surfaceElevationMeters,
        double observerHeightAboveSurfaceMeters, double lowSlopeSquareSideMeters, String terrainScreeningStatus) {
    public static LandingSiteResponse from(LandingSite site) {
        return new LandingSiteResponse(site.getId(), site.getSiteCode(), site.getTerrainDataset().getId(),
                site.getTerrainDataset().getDatasetVersion(), site.getLatitudeDegrees(), site.getLongitudeDegrees(),
                site.getSurfaceElevationMeters(), site.getObserverHeightAboveSurfaceMeters(),
                site.getLowSlopeSquareSideMeters(), site.getTerrainScreeningStatus());
    }
}

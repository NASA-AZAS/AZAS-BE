package azas.domain.landingsite.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record LandingSiteDetailResponse(@JsonUnwrapped LandingSiteResponse site, MeanDailyVisibility meanDailyVisibility) {}

package azas.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public interface StrictAnalysisOptions {
    @JsonAnySetter
    default void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported analysis option: " + field);
    }
}

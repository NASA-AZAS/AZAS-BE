package azas.domain.analysis.engine;

/** Stable public failure code; upstream details stay in the cause, not the API response. */
public class AnalysisEngineException extends RuntimeException {
    private final String code;

    public AnalysisEngineException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() { return code; }
}

package azas.domain.analysis.repository;

import azas.domain.analysis.engine.DateOpportunityEngine;
import azas.domain.analysis.exception.AnalysisErrorCode;
import azas.global.exception.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ObserverArchiveReader {
    private final JdbcTemplate jdbc;

    @FunctionalInterface
    public interface Visitor {
        boolean visit(long epochSecond, double sunAzimuth, double sunElevation, double earthAzimuth, double earthElevation);
    }

    /** The caller's read transaction keeps the PostgreSQL cursor open. Return false to stop at the current row. */
    public void scan(long siteId, long first, long last, Visitor visitor) {
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    select extract(epoch from observed_at)::bigint, sun_azimuth_degrees, sun_elevation_degrees,
                           earth_azimuth_degrees, earth_elevation_degrees
                    from observer_sample where landing_site_id = ? and observed_at between ? and ?
                    order by observed_at
                    """);
            statement.setLong(1, siteId);
            statement.setTimestamp(2, Timestamp.from(Instant.ofEpochSecond(first)));
            statement.setTimestamp(3, Timestamp.from(Instant.ofEpochSecond(last)));
            statement.setFetchSize(2048);
            return statement;
        }, (ResultSetExtractor<Void>) rows -> {
            long expected = first;
            while (rows.next()) {
                long utc = rows.getLong(1);
                if (utc != expected) throw new BusinessException(AnalysisErrorCode.OBSERVER_DATA_INCOMPLETE);
                if (!visitor.visit(utc, rows.getDouble(2), rows.getDouble(3), rows.getDouble(4), rows.getDouble(5))) return null;
                expected += DateOpportunityEngine.STEP_SECONDS;
            }
            if (expected <= last) throw new BusinessException(AnalysisErrorCode.OBSERVER_DATA_INCOMPLETE);
            return null;
        });
    }
}

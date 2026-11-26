package io.codetriage.triage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CohortStatsTest {

    @Test
    @DisplayName("a clear outlier clears the review threshold, a typical student does not")
    void outlierDetected() {
        double[] cohort = {0.31, 0.33, 0.35, 0.36, 0.38, 0.39, 0.40, 0.41, 0.42, 0.43,
                           0.44, 0.45, 0.36, 0.37, 0.39, 0.41, 0.34, 0.38, 0.42, 0.95};
        CohortStats stats = CohortStats.of(cohort);

        assertTrue(stats.modifiedZScore(0.95) > 3.5);
        assertTrue(Math.abs(stats.modifiedZScore(0.39)) < 1.0);
        assertFalse(stats.tooSmallToJudge());
    }

    @Test
    @DisplayName("the median resists a quarter of the cohort copying")
    void medianResistsContamination() {
        double[] withFive = {0.31, 0.33, 0.35, 0.36, 0.38, 0.39, 0.40, 0.41, 0.42, 0.43,
                             0.44, 0.45, 0.36, 0.37, 0.39, 0.93, 0.94, 0.95, 0.96, 0.97};
        CohortStats stats = CohortStats.of(withFive);

        assertTrue(stats.median() < 0.55, "median was " + stats.median());
        assertTrue(stats.modifiedZScore(0.95) > 3.5);
    }

    @Test
    @DisplayName("a cohort with no spread yields a finite, zero z-score")
    void zeroSpread() {
        CohortStats stats = CohortStats.of(new double[]{0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5});
        assertEquals(0.0, stats.modifiedZScore(0.5));
        assertTrue(Double.isFinite(stats.modifiedZScore(0.99)));
    }

    @Test
    @DisplayName("falls back to mean absolute deviation when MAD collapses to zero")
    void madFallback() {
        double[] skewed = {0.40, 0.40, 0.40, 0.40, 0.40, 0.40, 0.41, 0.43, 0.52, 0.88};
        CohortStats stats = CohortStats.of(skewed);

        assertTrue(stats.dispersion() > 0);
        assertTrue(stats.modifiedZScore(0.88) > 3.5);
    }

    @Test
    @DisplayName("degenerate inputs are safe and small cohorts are guarded")
    void degenerate() {
        assertEquals(0, CohortStats.of(new double[0]).count());
        assertTrue(Double.isFinite(CohortStats.of(new double[0]).modifiedZScore(0.9)));
        assertEquals(0, CohortStats.of(null).count());
        assertTrue(CohortStats.of(new double[]{0.7}).tooSmallToJudge());
    }

    @Test
    @DisplayName("even-length median averages the middle pair")
    void evenMedian() {
        assertEquals(0.5, CohortStats.of(new double[]{0.2, 0.4, 0.6, 0.8}).median(), 1e-9);
    }
}

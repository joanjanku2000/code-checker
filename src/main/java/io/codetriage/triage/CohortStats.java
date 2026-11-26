package io.codetriage.triage;

import java.util.Arrays;

/**
 * Robust cohort statistics.
 *
 * A fixed threshold ("over 70% means copied") is indefensible, because how
 * similar two honest answers to a small exercise look depends entirely on the
 * exercise. A "reverse a string" lab converges hard; an open-ended one does
 * not. So the question asked here is not "is this number high?" but "does this
 * submission stand apart from its own cohort?"
 *
 * Median and MAD are used rather than mean and standard deviation because a
 * handful of genuinely copied submissions would drag a mean upward and hide
 * themselves. The median barely moves.
 */
public final class CohortStats {

    /** Scaling constant that puts the modified z-score on the same footing as a normal z. */
    private static final double MAD_SCALE = 0.6745;
    private static final double MEAN_AD_SCALE = 1.253314;

    private final double median;
    private final double dispersion;
    private final int count;

    private CohortStats(double median, double dispersion, int count) {
        this.median = median;
        this.dispersion = dispersion;
        this.count = count;
    }

    public static CohortStats of(double[] values) {
        if (values == null || values.length == 0) {
            return new CohortStats(0, 0, 0);
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double med = median(sorted);

        double[] deviations = new double[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            deviations[i] = Math.abs(sorted[i] - med);
        }
        Arrays.sort(deviations);
        double mad = median(deviations);

        // MAD collapses to zero when more than half the cohort shares one value,
        // which happens often with small, highly convergent exercises. Fall back
        // to the mean absolute deviation so the score stays usable.
        double disp = mad;
        if (disp == 0.0) {
            double sum = 0;
            for (double d : deviations) {
                sum += d;
            }
            disp = (sum / deviations.length) * MEAN_AD_SCALE;
        }
        return new CohortStats(med, disp, values.length);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) return 0;
        int mid = n / 2;
        return (n % 2 == 1) ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    /**
     * Modified z-score. Returns 0 when the cohort has no spread at all, since
     * in that case nothing stands out by definition.
     */
    public double modifiedZScore(double value) {
        if (dispersion <= 0.0) {
            return 0.0;
        }
        return MAD_SCALE * (value - median) / dispersion;
    }

    public double median() { return median; }

    public double dispersion() { return dispersion; }

    public int count() { return count; }

    /** A cohort this small tells you nothing; the caller should not flag on z alone. */
    public boolean tooSmallToJudge() { return count < 8; }
}

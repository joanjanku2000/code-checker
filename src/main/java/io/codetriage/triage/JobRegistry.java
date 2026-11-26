package io.codetriage.triage;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory progress for the two long jobs (corpus generation, triage run), so
 * the admin pages can show something while they work. Deliberately not
 * persisted: if the app restarts mid-job, the job is gone anyway.
 */
@Component
public class JobRegistry {

    /**
     * Plain class with getters rather than a record: these are read from
     * Thymeleaf, and property access to record components is not worth
     * depending on.
     */
    public static final class JobState {
        private final String label;
        private final String message;
        private final int done;
        private final int total;
        private final boolean finished;
        private final String error;
        private final Instant updatedAt;

        JobState(String label, String message, int done, int total,
                 boolean finished, String error, Instant updatedAt) {
            this.label = label;
            this.message = message;
            this.done = done;
            this.total = total;
            this.finished = finished;
            this.error = error;
            this.updatedAt = updatedAt;
        }

        public String getLabel() { return label; }
        public String getMessage() { return message; }
        public int getDone() { return done; }
        public int getTotal() { return total; }
        public boolean isFinished() { return finished; }
        public String getError() { return error; }
        public Instant getUpdatedAt() { return updatedAt; }

        public int getPercent() { return total <= 0 ? 0 : (int) Math.round(100.0 * done / total); }
    }

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public void start(String key, String label, int total) {
        jobs.put(key, new JobState(label, "Starting", 0, total, false, null, Instant.now()));
    }

    public void progress(String key, String message, int done) {
        jobs.compute(key, (k, existing) -> {
            int total = existing == null ? 0 : existing.getTotal();
            String label = existing == null ? k : existing.getLabel();
            return new JobState(label, message, done, total, false, null, Instant.now());
        });
    }

    public void finish(String key, String message) {
        jobs.compute(key, (k, existing) -> {
            int total = existing == null ? 0 : existing.getTotal();
            String label = existing == null ? k : existing.getLabel();
            return new JobState(label, message, total, total, true, null, Instant.now());
        });
    }

    public void fail(String key, String error) {
        jobs.compute(key, (k, existing) -> {
            int done = existing == null ? 0 : existing.getDone();
            int total = existing == null ? 0 : existing.getTotal();
            String label = existing == null ? k : existing.getLabel();
            return new JobState(label, "Failed", done, total, true, error, Instant.now());
        });
    }

    public JobState get(String key) {
        return jobs.get(key);
    }

    public boolean isRunning(String key) {
        JobState state = jobs.get(key);
        return state != null && !state.isFinished();
    }
}

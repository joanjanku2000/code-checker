package io.codetriage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Corpus generation makes dozens of sequential API calls and a triage run
     * parses every submission. Both take minutes, so neither may occupy a
     * request thread. Pool kept small on purpose: these jobs are IO- and
     * CPU-heavy and a VPS has little of either to spare.
     */
    @Bean(name = "jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("job-");
        executor.initialize();
        return executor;
    }
}

package io.codetriage;

import io.codetriage.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CodeTriageApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeTriageApplication.class, args);
    }
}

package io.codetriage.config;

import io.codetriage.domain.Lab;
import io.codetriage.domain.StudentGroup;
import io.codetriage.domain.repo.LabRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {

    /**
     * Labs 1-5 for each group, so fifteen rows. Each is configured independently:
     * its own assignment, deadline, corpus and triage.
     */
    @Bean
    public ApplicationRunner seedLabs(LabRepository labs) {
        return args -> {
            for (StudentGroup group : StudentGroup.values()) {
                for (int number = 1; number <= 5; number++) {
                    if (labs.findByNumberAndStudentGroup(number, group).isEmpty()) {
                        Lab lab = new Lab();
                        lab.setNumber(number);
                        lab.setStudentGroup(group);
                        lab.setTitle("Laboratori " + number);
                        lab.setOpen(false);
                        labs.save(lab);
                    }
                }
            }
        };
    }
}

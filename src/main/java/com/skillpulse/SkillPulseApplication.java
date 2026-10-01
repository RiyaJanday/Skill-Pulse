package com.skillpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SkillPulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillPulseApplication.class, args);
    }
}

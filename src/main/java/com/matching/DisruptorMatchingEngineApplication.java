package com.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DisruptorMatchingEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisruptorMatchingEngineApplication.class, args);
    }
}
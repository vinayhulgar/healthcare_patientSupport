package com.healthcare.navigator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Healthcare Care Navigator application.
 *
 * <p>A multi-agent AI application that helps patients navigate post-care
 * instructions, medication guidance, and follow-up coordination after medical
 * procedures. Built on Spring Boot 3.x and Spring AI.
 */
@SpringBootApplication
public class HealthcareNavigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthcareNavigatorApplication.class, args);
    }
}

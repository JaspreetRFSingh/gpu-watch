package com.gpuguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GPUGuard — AI Training Infrastructure Reliability Platform
 *
 * Java / Spring Boot equivalent of the Python FastAPI implementation.
 * Tech stack mapping:
 *   Python FastAPI        →  Spring Boot 3 + Spring MVC
 *   prometheus-client     →  Micrometer + micrometer-registry-prometheus
 *   threading.Thread      →  @Scheduled (Spring task scheduler)
 *   dataclasses           →  Java Records + Lombok @Builder
 *   pytest                →  JUnit 5 + Mockito + MockMvc
 *   uvicorn               →  Embedded Tomcat (via spring-boot-starter-web)
 */
@SpringBootApplication
@EnableScheduling
public class GpuGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpuGuardApplication.class, args);
    }
}

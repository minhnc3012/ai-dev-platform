package com.aidevplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Dev Platform — Spring Boot entry point.
 *
 * Key capabilities:
 *   - Vaadin 24 UI for project and agent management
 *   - PostgreSQL persistence via Spring Data JPA + Flyway
 *   - Redis pub/sub for agent task dispatch
 *   - MinIO file storage for requirement documents
 *   - SSE push for real-time agent event streaming to the UI
 *   - Scheduled pipeline health checker (auto-resumes stuck runs every 5 min)
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
public class AiDevPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDevPlatformApplication.class, args);
    }
}

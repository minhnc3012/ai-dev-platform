package com.aidevplatform.config;

import com.vaadin.flow.spring.annotation.EnableVaadin;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Vaadin and async configuration.
 * @EnableAsync allows AgentOrchestrator.runAgentPipeline() to execute on a separate thread.
 */
@Configuration
@EnableVaadin("com.aidevplatform.ui")
@EnableAsync
public class VaadinConfig {
}

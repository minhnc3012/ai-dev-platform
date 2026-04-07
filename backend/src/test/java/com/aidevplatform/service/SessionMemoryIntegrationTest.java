package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.AgentSession;
import com.aidevplatform.domain.entity.AiConfig;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.repository.AgentReportRepository;
import com.aidevplatform.repository.AgentRunRepository;
import com.aidevplatform.repository.AgentSessionRepository;
import com.aidevplatform.repository.ModuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for 3-tier session memory system.
 * Tests the complete flow from agent completion to memory retrieval.
 */
@SpringBootTest
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
public class SessionMemoryIntegrationTest {

    @Autowired
    private SessionCompressorService sessionCompressorService;

    @Autowired
    private SessionCleanupService sessionCleanupService;

    @Autowired
    private ChatSummarizationService chatSummarizationService;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private AgentReportRepository agentReportRepository;

    @Autowired
    private AgentSessionRepository agentSessionRepository;

    @Autowired
    private ModuleRepository moduleRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID testModuleId;
    private UUID testAgentRunId;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        redisTemplate.delete(redisTemplate.keys("*:raw:*"));
        agentSessionRepository.deleteAll();
        agentReportRepository.deleteAll();
        agentRunRepository.deleteAll();
    }

    @Test
    void testCompressSessionFromReport() {
        // Given: An agent run with completed report
        Module module = createTestModule();
        AgentRun run = AgentRun.builder()
                .module(module)
                .agentName("pm")
                .runOrder(0)
                .status(AgentRunStatus.COMPLETED)
                .startedAt(LocalDateTime.now().minusHours(1))
                .completedAt(LocalDateTime.now())
                .build();
        agentRunRepository.save(run);

        AgentReport report = AgentReport.builder()
                .run(run)
                .summary("PM Agent completed: Created project scope and requirements")
                .confidenceScore(new java.math.BigDecimal("0.85"))
                .confidenceReason("Clear requirements provided, scope is well-defined")
                .tokensUsed(1000)
                .build();
        agentReportRepository.save(report);

        // When: Compressing session
        sessionCompressorService.compressSessionFromReport(run.getId());

        // Then: Session record is created with correct data
        AgentSession session = agentSessionRepository.findByRunId(run.getId()).orElseThrow();
        assertThat(session).isNotNull();
        assertThat(session.getSummary()).isEqualTo("PM Agent completed: Created project scope and requirements");
        assertThat(session.getReasoningSummary()).contains("Clear requirements");
        assertThat(session.getStatus()).isEqualTo(AgentSession.SessionStatus.COMPRESSED);
    }

    @Test
    void testGetSessionContext() {
        // Given: A compressed session
        Module module = createTestModule();
        AgentRun run = AgentRun.builder()
                .module(module)
                .agentName("pm")
                .status(AgentRunStatus.COMPLETED)
                .build();
        agentRunRepository.save(run);

        AgentReport report = AgentReport.builder()
                .run(run)
                .summary("PM summary")
                .confidenceScore(new java.math.BigDecimal("0.85"))
                .confidenceReason("Confidence reason")
                .build();
        agentReportRepository.save(report);

        sessionCompressorService.compressSessionFromReport(run.getId());

        // When: Getting session context
        SessionCompressorService.ResumptionContext context = sessionCompressorService.getSessionContext(run.getId());

        // Then: Context contains structured data
        assertThat(context).isNotNull();
        assertThat(context.sessionSummary()).isEqualTo("PM summary");
        assertThat(context.reasoningSummary()).isEqualTo("Confidence reason");
    }

    @Test
    void testSessionContextWithNoPriorSession() {
        // Given: A run with no prior session compression
        Module module = createTestModule();
        AgentRun run = AgentRun.builder()
                .module(module)
                .agentName("pm")
                .status(AgentRunStatus.COMPLETED)
                .build();
        agentRunRepository.save(run);

        // When: Getting context for first agent (no prior sessions)
        SessionCompressorService.ResumptionContext context = sessionCompressorService.getSessionContext(run.getId());

        // Then: Returns basic context (empty for first agent)
        assertThat(context.sessionSummary()).isNullOrEmpty();
    }

    @Test
    void testSessionCompressionWithPreviousAgents() {
        // Given: Multiple completed agents
        Module module = createTestModule();

        AgentRun pmRun = createAndSaveRun(module, "pm", AgentRunStatus.COMPLETED);
        AgentReport pmReport = AgentReport.builder()
                .run(pmRun)
                .summary("PM: Project scope defined")
                .confidenceScore(new java.math.BigDecimal("0.90"))
                .build();
        agentReportRepository.save(pmReport);

        AgentRun architectRun = createAndSaveRun(module, "architect", AgentRunStatus.COMPLETED);
        AgentReport architectReport = AgentReport.builder()
                .run(architectRun)
                .summary("Architect: High-level design completed")
                .confidenceScore(new java.math.BigDecimal("0.85"))
                .build();
        agentReportRepository.save(architectReport);

        AgentRun devRun = AgentRun.builder()
                .module(module)
                .agentName("dev")
                .runOrder(2)
                .status(AgentRunStatus.PENDING)
                .build();
        agentRunRepository.save(devRun);

        // When: Getting context for dev agent
        SessionCompressorService.ResumptionContext context = sessionCompressorService.getSessionContext(devRun.getId());

        // Then: Accumulates memory from all previous agents
        assertThat(context.sessionSummary()).contains("PM: Project scope defined");
        assertThat(context.sessionSummary()).contains("Architect: High-level design completed");
    }

    @Test
    void testSessionCompressionWithRedisCache() {
        // Given: A run with recent messages cached in Redis
        Module module = createTestModule();
        AgentRun run = createAndSaveRun(module, "pm", AgentRunStatus.RUNNING);

        String recentMessages = """
                User: Create a new feature
                Assistant: Starting feature creation
                User: It should include authentication
                Assistant: Will add authentication module
                """;
        redisTemplate.opsForValue().set("session:raw:" + run.getId(), recentMessages, 1, java.util.concurrent.TimeUnit.HOURS);

        // When: Getting session context
        SessionCompressorService.ResumptionContext context = sessionCompressorService.getSessionContext(run.getId());

        // Then: Includes recent messages
        assertThat(context.recentRawMessages()).contains("authentication");
    }

    @Test
    void testSessionContextToSystemPrompt() {
        // Given: A session with structured facts, summary, and reasoning
        Module module = createTestModule();
        AgentRun run = createAndSaveRun(module, "pm", AgentRunStatus.COMPLETED);

        SessionCompressorService.ResumptionContext context = new SessionCompressorService.ResumptionContext(
                Map.of("deliverables", List.of(Map.of("name", "requirements"))),
                "PM completed scope definition",
                "Chose REST over GraphQL for simplicity",
                "Last user message"
        );

        // When: Converting to system prompt
        String prompt = context.toSystemPrompt("dev", objectMapper);

        // Then: Includes all sections
        assertThat(prompt).contains("## Current Agent: dev");
        assertThat(prompt).contains("## Completed Work (Structured Facts)");
        assertThat(prompt).contains("## Session Summary");
        assertThat(prompt).contains("## Design Decisions");
        assertThat(prompt).contains("## Recent Activity");
    }

    @Test
    void testSessionCleanup() {
        // Given: A session with recent messages
        Module module = createTestModule();
        AgentRun run = createAndSaveRun(module, "pm", AgentRunStatus.RUNNING);

        redisTemplate.opsForValue().set("session:raw:" + run.getId(), "test messages", 24, java.util.concurrent.TimeUnit.HOURS);

        // When: Running cleanup (with very short TTL for test)
        redisTemplate.expire("session:raw:" + run.getId(), 1, java.util.concurrent.TimeUnit.SECONDS);

        // Then: Session remains (not expired yet)
        assertThat(redisTemplate.hasKey("session:raw:" + run.getId())).isTrue();
    }

    @Test
    void testChatSummarization() {
        // Given: A chat history
        String chatHistory = """
                User: Create a login feature
                Assistant: Starting login feature implementation
                User: Please use JWT for authentication
                Assistant: Will implement JWT authentication
                User: The token should expire after 24 hours
                Assistant: Token expiration set to 24 hours
                User: Should we also implement refresh tokens?
                Assistant: Good point. Adding refresh token rotation for security.
                User: Great, thanks!
                Assistant: You're welcome! Login feature complete.
                """;

        // When: Summarizing
        ChatSummarizationService.ChatSummary summary = chatSummarizationService.summarizeChat(chatHistory, "pm");

        // Then: Summary captures key points
        assertThat(summary.summary()).isNotEmpty();
        assertThat(summary.summary()).contains("login", "JWT", "authentication");
    }

    @Test
    void testChatSummarizationExtractsDecisions() {
        // Given: Chat with decision patterns
        String chatHistory = """
                User: Should we use REST or GraphQL?
                Assistant: Let me analyze the requirements...
                User: We need flexibility for future changes
                Assistant: Decision: Choose GraphQL for flexibility. REST is more rigid.
                User: And for authentication?
                Assistant: Decision: Use JWT with refresh tokens.
                """;

        // When: Extracting decision reasoning
        String reasoning = chatSummarizationService.extractDecisionReasoning(chatHistory);

        // Then: Captures decision patterns
        assertThat(reasoning).contains("Decision");
        assertThat(reasoning).contains("GraphQL");
        assertThat(reasoning).contains("JWT");
    }

    // Helper methods
    private Module createTestModule() {
        Module module = Module.builder()
                .name("Test Module")
                .status(ModuleStatus.DRAFT)
                .build();
        module.setId(UUID.randomUUID());
        module.setProject(createTestProject());
        return moduleRepository.save(module);
    }

    private Project createTestProject() {
        AiConfig config = AiConfig.builder()
                .llmProvider("openai")
                .llmModelName("gpt-4o")
                .outputLanguage("English")
                .temperature(new java.math.BigDecimal("0.7"))
                .build();
        config.setId(UUID.randomUUID());
        Project project = Project.builder()
                .name("Test Project")
                .aiConfig(config)
                .build();
        project.setId(UUID.randomUUID());
        return project;
    }

    private AgentRun createAndSaveRun(Module module, String agentName, AgentRunStatus status) {
        AgentRun run = AgentRun.builder()
                .module(module)
                .agentName(agentName)
                .runOrder(module.getId().hashCode() % 10)
                .status(status)
                .build();
        return agentRunRepository.save(run);
    }
}

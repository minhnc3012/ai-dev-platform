package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentCallbackDto;
import com.aidevplatform.domain.entity.*;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.enums.LlmInvocationMode;
import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentOrchestrator workflow logic.
 *
 * Covers both approval modes as defined in the spec:
 *
 *   approvalRequired = true:
 *     PM → AWAITING_APPROVAL → (owner approves) → Arch → AWAITING_APPROVAL
 *       → (owner approves) → Dev → (auto trigger) → QA ‖ Docs
 *
 *   approvalRequired = false:
 *     PM → auto trigger → Arch → auto trigger → Dev → auto trigger → QA ‖ Docs
 *
 * Dev → (QA ‖ Docs) is ALWAYS auto-triggered in both modes, because QA and Docs
 * are the terminal parallel agents — no sequential handoff after them.
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock ModuleRepository moduleRepository;
    @Mock AgentRunRepository agentRunRepository;
    @Mock AgentReportRepository agentReportRepository;
    @Mock AgentEventService agentEventService;
    @Mock SseService sseService;
    @Mock UiEventBroadcaster uiEventBroadcaster;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock SessionCompressorService sessionCompressorService;
    @Mock ChatSummarizationService chatSummarizationService;
    @Mock AgentSignalProducer agentSignalProducer;

    @InjectMocks AgentOrchestrator orchestrator;

    // =========================================================================
    // Entity builders
    // =========================================================================

    private AiConfig buildConfig(boolean approvalRequired, List<String> activeAgents) {
        AiConfig config = new AiConfig();
        config.setApprovalRequired(approvalRequired);
        config.setActiveAgents(new ArrayList<>(activeAgents));
        config.setInvocationMode(LlmInvocationMode.API);
        config.setLlmProvider("openai_compatible");
        config.setLlmBaseUrl("http://localhost:11434/v1");
        config.setLlmModelName("gpt-4");
        config.setLlmApiKey("test-key");
        config.setTemperature(new BigDecimal("0.50"));
        config.setMaxTokensPerTask(4096);
        config.setOutputLanguage("en");
        config.setTechStack(new ArrayList<>());
        return config;
    }

    private Module buildModule(UUID moduleId, AiConfig config) {
        User owner = new User();
        owner.setId(UUID.randomUUID());

        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setAiConfig(config);
        project.setOwner(owner);
        project.setWorkspacePath("/tmp/test");
        project.setName("Test Project");

        Module module = new Module();
        module.setId(moduleId);
        module.setProject(project);
        module.setName("Test Module");
        module.setRawRequirement("Build a REST API");
        module.setStatus(ModuleStatus.PENDING_RUN);
        return module;
    }

    private AgentRun buildRun(UUID runId, String agentName, AgentRunStatus status,
                               Module module, int order) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setAgentName(agentName);
        run.setStatus(status);
        run.setModule(module);
        run.setRunOrder(order);
        run.setStartedAt(LocalDateTime.now().minusMinutes(2));
        return run;
    }

    private AgentCallbackDto buildCallback(UUID runId) {
        AgentCallbackDto dto = new AgentCallbackDto();
        dto.setRunId(runId);
        dto.setTokensUsed(500);
        // report intentionally null — mapToReport handles null gracefully
        return dto;
    }

    private SessionCompressorService.ResumptionContext emptyContext() {
        return new SessionCompressorService.ResumptionContext(null, null, null, null);
    }

    /**
     * Mocks TransactionSynchronizationManager to immediately fire afterCommit()
     * on any registered synchronization, making the auto-trigger chain run synchronously.
     */
    private MockedStatic<TransactionSynchronizationManager> mockTsmFireImmediately() {
        MockedStatic<TransactionSynchronizationManager> mocked =
                mockStatic(TransactionSynchronizationManager.class);
        mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
              .thenAnswer(inv -> {
                  TransactionSynchronization sync = inv.getArgument(0);
                  sync.afterCommit();
                  return null;
              });
        return mocked;
    }

    /**
     * Mocks TransactionSynchronizationManager to do nothing (no afterCommit).
     * Used for approval-path tests where we only want to verify the gate state.
     */
    private MockedStatic<TransactionSynchronizationManager> mockTsmNoOp() {
        MockedStatic<TransactionSynchronizationManager> mocked =
                mockStatic(TransactionSynchronizationManager.class);
        mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
              .thenAnswer(inv -> null);
        return mocked;
    }

    // =========================================================================
    // approvalRequired = true — sequential agents (PM, Arch) must wait for owner
    // =========================================================================

    @Test
    void approvalTrue_pmCompletes_setsAwaitingApproval() {
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun = buildRun(pmRunId, "pm", AgentRunStatus.RUNNING, module, 0);
        List<AgentRun> allRuns = List.of(
                pmRun,
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.PENDING, module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.PENDING, module, 2),
                buildRun(UUID.randomUUID(), "qa",        AgentRunStatus.PENDING, module, 3),
                buildRun(UUID.randomUUID(), "docs",      AgentRunStatus.PENDING, module, 4)
        );

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentReportRepository.findByRunId(pmRunId)).thenReturn(Optional.empty());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmNoOp()) {
            orchestrator.handleAgentComplete(buildCallback(pmRunId));
        }

        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(sseService).broadcastAgentAwaitingApproval(any(), eq(pmRunId));
        verify(agentSignalProducer).publishApproveSignal(eq(pmRunId.toString()), any(), eq("pm"));
        // Next agent must NOT be auto-triggered — owner approval is required
        verify(redisTemplate, never()).convertAndSend(eq("agent:next"), anyString());
    }

    @Test
    void approvalTrue_archCompletes_setsAwaitingApproval() {
        UUID moduleId = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.RUNNING, module, 1);
        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",   AgentRunStatus.APPROVED, module, 0),
                archRun,
                buildRun(UUID.randomUUID(), "dev",  AgentRunStatus.PENDING,  module, 2),
                buildRun(UUID.randomUUID(), "qa",   AgentRunStatus.PENDING,  module, 3),
                buildRun(UUID.randomUUID(), "docs", AgentRunStatus.PENDING,  module, 4)
        );

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentReportRepository.findByRunId(archRunId)).thenReturn(Optional.empty());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmNoOp()) {
            orchestrator.handleAgentComplete(buildCallback(archRunId));
        }

        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(sseService).broadcastAgentAwaitingApproval(any(), eq(archRunId));
        verify(agentSignalProducer).publishApproveSignal(eq(archRunId.toString()), any(), eq("architect"));
        verify(redisTemplate, never()).convertAndSend(eq("agent:next"), anyString());
    }

    // =========================================================================
    // approvalRequired = true — Dev → (QA ‖ Docs) ALWAYS auto-triggers
    // =========================================================================

    @Test
    void approvalTrue_devCompletes_autoTriggersQaAndDocsInParallel() {
        UUID moduleId = UUID.randomUUID();
        UUID devRunId = UUID.randomUUID();
        UUID qaRunId  = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun  = buildRun(devRunId,  "dev",  AgentRunStatus.RUNNING, module, 2);
        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.PENDING, module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.PENDING, module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED, module, 1),
                devRun, qaRun, docsRun
        );

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(qaRun, docsRun));
        when(agentReportRepository.findByRunId(devRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(devRunId));
        }

        // Dev must NOT go to AWAITING_APPROVAL — it should auto-trigger QA/Docs
        assertThat(devRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(agentSignalProducer).publishCompleteSignal(eq(devRunId.toString()), any(), eq("dev"));
        verify(agentSignalProducer, never()).publishApproveSignal(eq(devRunId.toString()), any(), eq("dev"));
        verify(sseService, never()).broadcastAgentAwaitingApproval(any(), eq(devRunId));
        // Both QA and Docs must be dispatched concurrently
        verify(redisTemplate, times(2)).convertAndSend(eq("agent:next"), anyString());
        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(docsRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approvalTrue_devCompletes_qaOnly_autoTriggersQa() {
        // Active agents without docs: pm, dev, qa
        UUID moduleId = UUID.randomUUID();
        UUID devRunId = UUID.randomUUID();
        UUID qaRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "dev", "qa"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun = buildRun(devRunId, "dev", AgentRunStatus.RUNNING, module, 1);
        AgentRun qaRun  = buildRun(qaRunId,  "qa",  AgentRunStatus.PENDING, module, 2);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm", AgentRunStatus.APPROVED, module, 0),
                devRun, qaRun
        );

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(qaRun));
        when(agentReportRepository.findByRunId(devRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(devRunId));
        }

        assertThat(devRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(agentSignalProducer).publishCompleteSignal(eq(devRunId.toString()), any(), eq("dev"));
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    // =========================================================================
    // approvalRequired = false — every agent auto-triggers the next
    // =========================================================================

    @Test
    void approvalFalse_pmCompletes_autoTriggersArch() {
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId   = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun   = buildRun(pmRunId,   "pm",        AgentRunStatus.RUNNING, module, 0);
        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.PENDING, module, 1);

        List<AgentRun> allRuns = List.of(
                pmRun, archRun,
                buildRun(UUID.randomUUID(), "dev",  AgentRunStatus.PENDING, module, 2),
                buildRun(UUID.randomUUID(), "qa",   AgentRunStatus.PENDING, module, 3),
                buildRun(UUID.randomUUID(), "docs", AgentRunStatus.PENDING, module, 4)
        );

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(archRun));
        when(agentReportRepository.findByRunId(pmRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(pmRunId));
        }

        assertThat(pmRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(agentSignalProducer).publishCompleteSignal(eq(pmRunId.toString()), any(), eq("pm"));
        verify(sseService, never()).broadcastAgentAwaitingApproval(any(), any());
        // Only Arch triggered (not QA/Docs — Dev still pending)
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approvalFalse_archCompletes_autoTriggersDevSequential() {
        UUID moduleId  = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.RUNNING, module, 1);
        AgentRun devRun  = buildRun(devRunId,  "dev",       AgentRunStatus.PENDING, module, 2);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm", AgentRunStatus.COMPLETED, module, 0),
                archRun, devRun,
                buildRun(UUID.randomUUID(), "qa",   AgentRunStatus.PENDING, module, 3),
                buildRun(UUID.randomUUID(), "docs", AgentRunStatus.PENDING, module, 4)
        );

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(devRun));
        when(agentReportRepository.findByRunId(archRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(archRunId));
        }

        verify(agentSignalProducer).publishCompleteSignal(eq(archRunId.toString()), any(), eq("architect"));
        // Only Dev triggered — not QA/Docs (Dev is still pending at this point)
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(devRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approvalFalse_devCompletes_autoTriggersQaAndDocsInParallel() {
        UUID moduleId  = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun  = buildRun(devRunId,  "dev",  AgentRunStatus.RUNNING, module, 2);
        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.PENDING, module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.PENDING, module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.COMPLETED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.COMPLETED, module, 1),
                devRun, qaRun, docsRun
        );

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(qaRun, docsRun));
        when(agentReportRepository.findByRunId(devRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(devRunId));
        }

        verify(agentSignalProducer).publishCompleteSignal(eq(devRunId.toString()), any(), eq("dev"));
        // QA and Docs dispatched in parallel — 2 sends to agent:next
        verify(redisTemplate, times(2)).convertAndSend(eq("agent:next"), anyString());
        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(docsRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    // =========================================================================
    // approveRun — owner grants approval, next sequential agent starts
    // =========================================================================

    @Test
    void approveRun_pmApproved_triggersArchSequentially() {
        UUID moduleId  = UUID.randomUUID();
        UUID pmRunId   = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun   = buildRun(pmRunId,   "pm",        AgentRunStatus.AWAITING_APPROVAL, module, 0);
        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.PENDING,           module, 1);

        List<AgentRun> allRuns = List.of(
                pmRun, archRun,
                buildRun(UUID.randomUUID(), "dev",  AgentRunStatus.PENDING, module, 2),
                buildRun(UUID.randomUUID(), "qa",   AgentRunStatus.PENDING, module, 3),
                buildRun(UUID.randomUUID(), "docs", AgentRunStatus.PENDING, module, 4)
        );

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(archRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.approveRun(pmRunId);

        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approveRun_archApproved_triggersDevSequentially() {
        UUID moduleId  = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.AWAITING_APPROVAL, module, 1);
        AgentRun devRun  = buildRun(devRunId,  "dev",       AgentRunStatus.PENDING,           module, 2);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm", AgentRunStatus.APPROVED, module, 0),
                archRun, devRun,
                buildRun(UUID.randomUUID(), "qa",   AgentRunStatus.PENDING, module, 3),
                buildRun(UUID.randomUUID(), "docs", AgentRunStatus.PENDING, module, 4)
        );

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(devRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.approveRun(archRunId);

        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(devRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approveRun_devApproved_triggersQaAndDocsInParallel() {
        // In approval mode, Dev is AWAITING_APPROVAL (e.g. if it was put there manually).
        // Approving Dev must trigger QA+Docs in parallel (terminal agents).
        UUID moduleId  = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun  = buildRun(devRunId,  "dev",  AgentRunStatus.AWAITING_APPROVAL, module, 2);
        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.PENDING,           module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.PENDING,           module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED, module, 1),
                devRun, qaRun, docsRun
        );

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(qaRun, docsRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.approveRun(devRunId);

        assertThat(devRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        verify(redisTemplate, times(2)).convertAndSend(eq("agent:next"), anyString());
        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(docsRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    // =========================================================================
    // rejectRun — owner rejects, run resets to PENDING for retry
    // =========================================================================

    @Test
    void rejectRun_setsRejectedPublishesSignalAndResetsForRetry() {
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun = buildRun(pmRunId, "pm", AgentRunStatus.AWAITING_APPROVAL, module, 0);

        List<AgentRun> allRuns = List.of(
                pmRun,
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.PENDING, module, 1)
        );

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        // After reset, pm run will be the only PENDING item for retry
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(pmRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.rejectRun(pmRunId, "Requirements are incomplete");

        verify(agentSignalProducer).publishRejectSignal(
                eq(pmRunId.toString()), any(), eq("pm"), eq("Requirements are incomplete"));
        verify(sseService).broadcastRunRejected(any(), eq(pmRunId), eq("Requirements are incomplete"));
        // Run is reset to PENDING then immediately re-dispatched → ends up RUNNING for retry
        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void rejectRun_storesErrorMessage() {
        UUID moduleId = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        String rejectionReason = "Architecture design needs revision";
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.AWAITING_APPROVAL, module, 1);

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId))
                .thenReturn(List.of(archRun));
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(archRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.rejectRun(archRunId, rejectionReason);

        assertThat(archRun.getErrorMessage()).isEqualTo(rejectionReason);
        assertThat(archRun.getRetryCount()).isEqualTo(1);
    }

    // =========================================================================
    // Idempotency — duplicate completion callbacks must be ignored
    // =========================================================================

    @Test
    void handleAgentComplete_duplicateCallback_isIgnored() {
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "architect"));
        Module module = buildModule(moduleId, config);

        // Run is already in COMPLETED state — simulates a duplicate callback from Python
        AgentRun pmRun = buildRun(pmRunId, "pm", AgentRunStatus.COMPLETED, module, 0);
        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));

        orchestrator.handleAgentComplete(buildCallback(pmRunId));

        // Nothing should change — no report saved, no signals, no redis dispatch
        verify(agentReportRepository, never()).save(any());
        verify(agentSignalProducer, never()).publishCompleteSignal(any(), any(), any());
        verify(agentSignalProducer, never()).publishApproveSignal(any(), any(), any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void handleAgentComplete_runAlreadyAwaiting_isIgnored() {
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun = buildRun(pmRunId, "pm", AgentRunStatus.AWAITING_APPROVAL, module, 0);
        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));

        orchestrator.handleAgentComplete(buildCallback(pmRunId));

        verify(agentReportRepository, never()).save(any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    // =========================================================================
    // isNextStepParallelTerminal edge case: no pending agents at all
    // =========================================================================

    @Test
    void approvalTrue_lastAgentCompletes_noPendingAgents_noApprovalGate() {
        // If there are no pending agents left (e.g., QA is the only active agent and it completes),
        // isNextStepParallelTerminal returns false (empty pending list) → falls through to
        // approval gate. But if QA itself just finished, we should auto-trigger pipeline completion.
        // This test verifies the condition: empty pending → NOT treated as parallel terminal.
        UUID moduleId = UUID.randomUUID();
        UUID qaRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("qa"));
        Module module = buildModule(moduleId, config);

        AgentRun qaRun = buildRun(qaRunId, "qa", AgentRunStatus.RUNNING, module, 0);
        // No pending agents — QA is the last one
        List<AgentRun> allRuns = List.of(qaRun);

        when(agentRunRepository.findById(qaRunId)).thenReturn(Optional.of(qaRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentReportRepository.findByRunId(qaRunId)).thenReturn(Optional.empty());

        // With no PENDING runs, isNextStepParallelTerminal returns false → approval gate fires.
        // This is intentional: a single-agent pipeline with approvalRequired=true still awaits approval.
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmNoOp()) {
            orchestrator.handleAgentComplete(buildCallback(qaRunId));
        }

        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.AWAITING_APPROVAL);
    }

    // =========================================================================
    // rejectRun — Redis retry dispatch after reset-to-PENDING
    // =========================================================================

    @Test
    void rejectRun_resetsAgentToPending_andRedispatches() {
        // After rejection the run is reset to PENDING so the same agent can be re-triggered.
        // This verifies that triggerNextAgent dispatches the rejected (now PENDING) agent
        // to agent:next so Python can retry it.
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun   = buildRun(pmRunId, "pm", AgentRunStatus.AWAITING_APPROVAL, module, 0);
        AgentRun archRun = buildRun(UUID.randomUUID(), "architect", AgentRunStatus.PENDING, module, 1);
        List<AgentRun> allRuns = List.of(pmRun, archRun);

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        // allRuns for triggerNextAgent: PM will be PENDING after reset, Arch is PENDING
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        // PENDING query returns PM (the lowest-order PENDING run — the retried agent)
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(pmRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.rejectRun(pmRunId, "Needs more detail");

        // Run is reset to PENDING then immediately re-triggered → final status is RUNNING
        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        // Redis must dispatch PM again so Python retries it
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
    }

    @Test
    void rejectRun_incrementsRetryCountOnSubsequentRejections() {
        // retryCount must increment correctly across multiple rejection cycles.
        UUID moduleId  = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.AWAITING_APPROVAL, module, 1);
        archRun.setRetryCount(1); // already rejected once before

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(List.of(archRun));
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(archRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.rejectRun(archRunId, "Still not good enough");

        assertThat(archRun.getRetryCount()).isEqualTo(2);
        assertThat(archRun.getErrorMessage()).isEqualTo("Still not good enough");
    }

    // =========================================================================
    // Pipeline completion — approveRun / handleAgentComplete finishes pipeline
    // =========================================================================

    @Test
    void approveRun_pmOnly_lastApproval_completesPipeline() {
        // Single-agent pipeline: PM is the only agent. After approval there are no more
        // pending agents → triggerNextAgent calls completePipeline.
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun = buildRun(pmRunId, "pm", AgentRunStatus.AWAITING_APPROVAL, module, 0);
        // After approval: PM becomes APPROVED; no other runs exist.
        List<AgentRun> allRunsPostApproval = List.of(pmRun);

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId))
                .thenReturn(allRunsPostApproval);
        // No pending agents remain after PM is approved
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());

        orchestrator.approveRun(pmRunId);

        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        // Module must be COMPLETED and broadcast must fire
        assertThat(module.getStatus()).isEqualTo(ModuleStatus.COMPLETED);
        verify(sseService).broadcastModuleComplete(any(), eq(moduleId));
        // No agent:next sent — pipeline is done
        verify(redisTemplate, never()).convertAndSend(eq("agent:next"), anyString());
    }

    @Test
    void approveRun_qaApproved_withDocsAlsoApproved_completesPipeline() {
        // Both QA and Docs have finished. Approving the last one (QA) must complete the pipeline.
        UUID moduleId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.AWAITING_APPROVAL, module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.APPROVED,          module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED, module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.COMPLETED, module, 2),
                qaRun, docsRun
        );

        when(agentRunRepository.findById(qaRunId)).thenReturn(Optional.of(qaRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());

        orchestrator.approveRun(qaRunId);

        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        assertThat(module.getStatus()).isEqualTo(ModuleStatus.COMPLETED);
        verify(sseService).broadcastModuleComplete(any(), eq(moduleId));
    }

    @Test
    void approveRun_qaApproved_whileDocsStillRunning_doesNotCompletePipeline() {
        // QA approved but Docs is still RUNNING (parallel execution). Pipeline must NOT
        // complete yet — it should wait for Docs to finish.
        UUID moduleId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.AWAITING_APPROVAL, module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.RUNNING,           module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED,  module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED,  module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.COMPLETED, module, 2),
                qaRun, docsRun
        );

        when(agentRunRepository.findById(qaRunId)).thenReturn(Optional.of(qaRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());

        orchestrator.approveRun(qaRunId);

        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        // Docs still RUNNING — pipeline must NOT be marked complete
        assertThat(module.getStatus()).isNotEqualTo(ModuleStatus.COMPLETED);
        verify(sseService, never()).broadcastModuleComplete(any(), any());
    }

    // =========================================================================
    // Subset active agents — approvalRequired = false
    // =========================================================================

    @Test
    void approvalFalse_pmAndDevOnly_pmCompletes_autoTriggersDev() {
        // Pipeline with only pm + dev (no architect, qa, docs).
        // PM auto-triggers Dev directly.
        UUID moduleId = UUID.randomUUID();
        UUID pmRunId  = UUID.randomUUID();
        UUID devRunId = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "dev"));
        Module module = buildModule(moduleId, config);

        AgentRun pmRun  = buildRun(pmRunId,  "pm",  AgentRunStatus.RUNNING, module, 0);
        AgentRun devRun = buildRun(devRunId, "dev", AgentRunStatus.PENDING, module, 1);

        List<AgentRun> allRuns = List.of(pmRun, devRun);

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(devRun));
        when(agentReportRepository.findByRunId(pmRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(pmRunId));
        }

        assertThat(pmRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(agentSignalProducer).publishCompleteSignal(eq(pmRunId.toString()), any(), eq("pm"));
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        assertThat(devRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void approvalFalse_devOnlyPipeline_devCompletes_pipelineCompletes() {
        // Only dev is active. After dev completes and is auto-triggered, there are no
        // remaining PENDING agents → pipeline completes immediately.
        UUID moduleId = UUID.randomUUID();
        UUID devRunId = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("dev"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun = buildRun(devRunId, "dev", AgentRunStatus.RUNNING, module, 0);
        List<AgentRun> allRuns = List.of(devRun);

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        // No pending agents after dev completes
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());
        when(agentReportRepository.findByRunId(devRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        // No buildFullTaskConfig call — completePipeline is invoked directly (no agent:next)

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(devRunId));
        }

        verify(agentSignalProducer).publishCompleteSignal(eq(devRunId.toString()), any(), eq("dev"));
        // No agent:next — pipeline is done
        verify(redisTemplate, never()).convertAndSend(eq("agent:next"), anyString());
        assertThat(module.getStatus()).isEqualTo(ModuleStatus.COMPLETED);
        verify(sseService).broadcastModuleComplete(any(), eq(moduleId));
    }

    // =========================================================================
    // approvalRequired = true — approval gate: PM → Arch, then pipeline completes
    // =========================================================================

    @Test
    void approvalTrue_fullSequence_pmApprovedTriggersArch_archApprovedTriggersDev() {
        // Verify the full sequential chain for approval mode:
        //   1. PM approved → Arch dispatched
        //   2. Arch approved → Dev dispatched
        // Each step is a separate approveRun call.

        UUID moduleId  = UUID.randomUUID();
        UUID pmRunId   = UUID.randomUUID();
        UUID archRunId = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        // --- Step 1: owner approves PM ---
        AgentRun pmRun   = buildRun(pmRunId,   "pm",        AgentRunStatus.AWAITING_APPROVAL, module, 0);
        AgentRun archRun = buildRun(archRunId, "architect", AgentRunStatus.PENDING,           module, 1);
        AgentRun devRun  = buildRun(devRunId,  "dev",       AgentRunStatus.PENDING,           module, 2);

        when(agentRunRepository.findById(pmRunId)).thenReturn(Optional.of(pmRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId))
                .thenReturn(List.of(pmRun, archRun, devRun));
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(archRun, devRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.approveRun(pmRunId);

        assertThat(pmRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        // Only Arch triggered — Dev must still wait
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
        reset(redisTemplate, agentRunRepository, agentReportRepository, moduleRepository,
              sessionCompressorService, agentSignalProducer);

        // --- Step 2: owner approves Arch ---
        archRun.setStatus(AgentRunStatus.AWAITING_APPROVAL);

        when(agentRunRepository.findById(archRunId)).thenReturn(Optional.of(archRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId))
                .thenReturn(List.of(pmRun, archRun, devRun));
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(devRun));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        orchestrator.approveRun(archRunId);

        assertThat(archRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        assertThat(devRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        verify(redisTemplate, times(1)).convertAndSend(eq("agent:next"), anyString());
    }

    @Test
    void approvalTrue_devCompletes_qaAndDocsAutoTriggered_thenBothApproved_pipelineCompletes() {
        // Full terminal sequence for approval mode:
        //   Dev completes → QA+Docs auto-triggered (no approval gate for terminal agents)
        //   Docs approved (QA already approved) → pipeline completes
        UUID moduleId  = UUID.randomUUID();
        UUID devRunId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(true, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun devRun  = buildRun(devRunId,  "dev",  AgentRunStatus.RUNNING, module, 2);
        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.PENDING, module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.PENDING, module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED, module, 1),
                devRun, qaRun, docsRun
        );

        when(agentRunRepository.findById(devRunId)).thenReturn(Optional.of(devRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of(qaRun, docsRun));
        when(agentReportRepository.findByRunId(devRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        when(sessionCompressorService.getSessionContext(any())).thenReturn(emptyContext());

        // Dev completes — QA+Docs must be auto-triggered (no approval gate)
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(devRunId));
        }

        assertThat(devRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(docsRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        verify(redisTemplate, times(2)).convertAndSend(eq("agent:next"), anyString());
        reset(redisTemplate, agentRunRepository, agentReportRepository, moduleRepository,
              sessionCompressorService, agentSignalProducer);

        // QA finishes first — approval gate fires (no pending qa/docs left to detect parallel terminal)
        qaRun.setStatus(AgentRunStatus.AWAITING_APPROVAL);

        // Approve QA while Docs is still RUNNING → no pipeline completion yet
        List<AgentRun> allRunsQaApproval = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED,  module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED,  module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.COMPLETED, module, 2),
                qaRun, docsRun
        );

        when(agentRunRepository.findById(qaRunId)).thenReturn(Optional.of(qaRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRunsQaApproval);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());

        orchestrator.approveRun(qaRunId);

        assertThat(qaRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        assertThat(module.getStatus()).isNotEqualTo(ModuleStatus.COMPLETED); // Docs still RUNNING
        verify(sseService, never()).broadcastModuleComplete(any(), any());
        reset(redisTemplate, agentRunRepository, agentReportRepository, moduleRepository,
              sseService, agentSignalProducer);

        // Docs finishes and gets approved → pipeline completes
        docsRun.setStatus(AgentRunStatus.AWAITING_APPROVAL);

        List<AgentRun> allRunsDocsApproval = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.APPROVED,  module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.APPROVED,  module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.COMPLETED, module, 2),
                qaRun,   // APPROVED
                docsRun  // AWAITING_APPROVAL → will become APPROVED
        );

        when(agentRunRepository.findById(docsRunId)).thenReturn(Optional.of(docsRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRunsDocsApproval);
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());

        orchestrator.approveRun(docsRunId);

        assertThat(docsRun.getStatus()).isEqualTo(AgentRunStatus.APPROVED);
        assertThat(module.getStatus()).isEqualTo(ModuleStatus.COMPLETED);
        verify(sseService).broadcastModuleComplete(any(), eq(moduleId));
    }

    // =========================================================================
    // approvalRequired = false — full auto-chain pipeline completion
    // =========================================================================

    @Test
    void approvalFalse_qaAndDocsComplete_pipelineCompletes() {
        // After both QA and Docs finish in auto-trigger mode, the pipeline must be
        // marked COMPLETED. This tests the handleAgentComplete path for a terminal agent
        // (QA completing while Docs has already been COMPLETED).
        UUID moduleId  = UUID.randomUUID();
        UUID qaRunId   = UUID.randomUUID();
        UUID docsRunId = UUID.randomUUID();
        AiConfig config = buildConfig(false, List.of("pm", "architect", "dev", "qa", "docs"));
        Module module = buildModule(moduleId, config);

        AgentRun qaRun   = buildRun(qaRunId,   "qa",   AgentRunStatus.RUNNING,    module, 3);
        AgentRun docsRun = buildRun(docsRunId, "docs", AgentRunStatus.COMPLETED,  module, 4);

        List<AgentRun> allRuns = List.of(
                buildRun(UUID.randomUUID(), "pm",        AgentRunStatus.COMPLETED, module, 0),
                buildRun(UUID.randomUUID(), "architect", AgentRunStatus.COMPLETED, module, 1),
                buildRun(UUID.randomUUID(), "dev",       AgentRunStatus.COMPLETED, module, 2),
                qaRun, docsRun
        );

        when(agentRunRepository.findById(qaRunId)).thenReturn(Optional.of(qaRun));
        when(agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId)).thenReturn(allRuns);
        // No pending agents remain — all done
        when(agentRunRepository.findByModuleIdAndStatusOrderByRunOrder(moduleId, AgentRunStatus.PENDING))
                .thenReturn(List.of());
        when(agentReportRepository.findByRunId(qaRunId)).thenReturn(Optional.empty());
        when(moduleRepository.findById(moduleId)).thenReturn(Optional.of(module));
        // No buildFullTaskConfig call — no pending agents, completePipeline is invoked directly

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockTsmFireImmediately()) {
            orchestrator.handleAgentComplete(buildCallback(qaRunId));
        }

        // QA is auto-completed (no approval gate in false mode)
        assertThat(qaRun.getStatus()).isNotEqualTo(AgentRunStatus.AWAITING_APPROVAL);
        verify(agentSignalProducer).publishCompleteSignal(eq(qaRunId.toString()), any(), eq("qa"));
        // No more agent:next — all done
        verify(redisTemplate, never()).convertAndSend(eq("agent:next"), anyString());
        assertThat(module.getStatus()).isEqualTo(ModuleStatus.COMPLETED);
        verify(sseService).broadcastModuleComplete(any(), eq(moduleId));
    }
}

package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentCallbackDto;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aidevplatform.service.AgentSignalProducer;
import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.AiConfig;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.domain.model.*;
import com.aidevplatform.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Coordinates the full agent pipeline lifecycle for a module.
 * Handles creation, dispatching, completion callbacks, and approval gates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {

    private final ModuleRepository moduleRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentReportRepository agentReportRepository;
    private final AgentEventService agentEventService;
    private final SseService sseService;
    private final UiEventBroadcaster uiEventBroadcaster;
    private final RedisTemplate<String, String> redisTemplate;
    private final SessionCompressorService sessionCompressorService;
    private final ChatSummarizationService chatSummarizationService;
    private final AgentSignalProducer agentSignalProducer;
    private final PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final List<String> ORDERED_AGENTS =
            List.of("pm", "architect", "dev", "qa", "docs");

    /**
     * Initialises the full agent pipeline for a given module.
     * Creates one AgentRun record per active agent, then dispatches
     * the first task to the Python agent layer via Redis.
     *
     * Idempotent: does nothing if the module is not in a startable state.
     */
    @Async
    @Transactional
    public void runAgentPipeline(UUID moduleId) {
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));

        // Only start a fresh pipeline for modules in a known-startable state.
        if (module.getStatus() != ModuleStatus.DRAFT
                && module.getStatus() != ModuleStatus.PENDING_RUN
                && module.getStatus() != ModuleStatus.FAILED) {
            log.warn("Module {} already in progress or completed (status={}), skipping",
                    moduleId, module.getStatus());
            return;
        }

        AiConfig config = module.getProject().getAiConfig();
        if (config == null) {
            log.error("No AI config found for project: {}", module.getProject().getId());
            module.setStatus(ModuleStatus.FAILED);
            moduleRepository.save(module);
            return;
        }

        List<String> activeAgents = config.getActiveAgents();

        // 1. Check if any agent is already RUNNING or AWAITING_APPROVAL
        List<AgentRun> existingRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId);
        boolean hasInProgress = existingRuns.stream()
                .anyMatch(r -> r.getStatus() == AgentRunStatus.RUNNING
                        || r.getStatus() == AgentRunStatus.AWAITING_APPROVAL);

        if (hasInProgress) {
            log.warn("Module {} already has RUNNING or AWAITING_APPROVAL runs, skipping", moduleId);
            return;
        }

        // 2. Clean up PENDING duplicates for completed agents
        for (AgentRun existing : existingRuns) {
            if (existing.getStatus() == AgentRunStatus.PENDING) {
                boolean hasCompleted = existingRuns.stream()
                        .anyMatch(r -> r.getAgentName().equals(existing.getAgentName())
                                && (r.getStatus() == AgentRunStatus.COMPLETED
                                || r.getStatus() == AgentRunStatus.APPROVED));
                if (hasCompleted) {
                    existing.setStatus(AgentRunStatus.TERMINATED);
                    existing.setErrorMessage("Skipped - agent already completed");
                    agentRunRepository.save(existing);
                }
            }
        }

        // 3. Create PENDING runs only for agents without any record
        int order = 0;
        for (String agentName : ORDERED_AGENTS) {
            if (!activeAgents.contains(agentName)) continue;
            boolean hasRun = existingRuns.stream()
                    .anyMatch(r -> r.getAgentName().equals(agentName));
            if (!hasRun) {
                AgentRun run = AgentRun.builder()
                        .module(module)
                        .agentName(agentName)
                        .runOrder(order++)
                        .status(AgentRunStatus.PENDING)
                        .build();
                agentRunRepository.save(run);
                log.info("Created PENDING run for agent {} in module {}", agentName, moduleId);
            }
        }

        module.setStatus(ModuleStatus.PENDING_RUN);
        moduleRepository.save(module);

        try {
            Map<String, Object> task = buildAgentTask(module, config);
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.convertAndSend("agent:tasks", taskJson);
            log.info("Agent pipeline dispatched for module: {}", moduleId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize agent task for module: {}", moduleId, e);
            module.setStatus(ModuleStatus.FAILED);
            moduleRepository.save(module);
            return;
        }

        String ownerId = module.getProject().getOwner().getId().toString();
        sseService.broadcastModuleUpdateToOwner(ownerId, moduleId, "PIPELINE_STARTED");
    }

    /**
     * Handles the completion callback from a Python agent.
     * Persists the report, compresses session memory, and triggers the next agent.
     */
    @Transactional
    public void handleAgentComplete(AgentCallbackDto callback) {
        AgentRun run = agentRunRepository.findById(callback.getRunId())
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + callback.getRunId()));

        if (run.getStatus() != AgentRunStatus.RUNNING) {
            log.warn("Ignoring duplicate completion for run {} - status is {}",
                    callback.getRunId(), run.getStatus());
            return;
        }

        run.setStatus(AgentRunStatus.COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setTokensUsed(callback.getTokensUsed());

        if (run.getStartedAt() != null) {
            long seconds = java.time.Duration.between(run.getStartedAt(), run.getCompletedAt()).getSeconds();
            run.setDurationSeconds((int) seconds);
        }
        agentRunRepository.save(run);

        // Persist the structured report (upsert: update if already exists)
        AgentReport report = agentReportRepository.findByRunId(run.getId())
                .map(existing -> {
                    AgentReport updated = mapToReport(callback.getReport(), run);
                    updated.setId(existing.getId());
                    updated.setCreatedAt(existing.getCreatedAt());
                    return updated;
                })
                .orElseGet(() -> mapToReport(callback.getReport(), run));
        agentReportRepository.save(report);

        // Flush to ensure PostgreSQL commit changes before triggering next agent
        agentRunRepository.flush();
        agentReportRepository.flush();
        moduleRepository.flush();

        // Compress session memory from AgentReport (structured facts extracted from report)
        try {
            sessionCompressorService.compressSessionFromReport(run.getId());
            log.info("Session compressed for run {}", run.getId());
        } catch (Exception e) {
            log.warn("Failed to compress session for run {}: {}", run.getId(), e.getMessage());
        }

        AiConfig config = run.getModule().getProject().getAiConfig();
        String ownerId = run.getModule().getProject().getOwner().getId().toString();

        // Dev → QA/Docs is always auto-triggered even when approvalRequired=true,
        // because QA and Docs are the terminal parallel agents (no sequential handoff).
        boolean nextIsParallelTerminal = isNextStepParallelTerminal(run.getModule());

        if (Boolean.TRUE.equals(config.getApprovalRequired()) && !nextIsParallelTerminal) {
            run.setStatus(AgentRunStatus.AWAITING_APPROVAL);
            agentRunRepository.save(run);
            sseService.broadcastAgentAwaitingApproval(ownerId, run.getId());

            // Publish APPROVE signal to Redis Stream
            agentSignalProducer.publishApproveSignal(
                    run.getId().toString(),
                    run.getModule().getId().toString(),
                    run.getAgentName()
            );

            final UUID pendingRunId = run.getId();
            final UUID pendingModuleId = run.getModule().getId();
            final String pendingAgentName = run.getAgentName();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    com.aidevplatform.api.dto.AgentEventDto dto =
                            com.aidevplatform.api.dto.AgentEventDto.builder()
                                    .runId(pendingRunId)
                                    .agentName(pendingAgentName)
                                    .eventType("AWAITING_APPROVAL")
                                    .message("Awaiting owner approval")
                                    .severity("INFO")
                                    .timestamp(java.time.LocalDateTime.now())
                                    .build();
                    uiEventBroadcaster.broadcast(dto, pendingModuleId);
                }
            });
            log.info("Agent run awaiting approval: runId={}, signal published", run.getId());
        } else {
            // Publish COMPLETE signal for auto-trigger case
            agentSignalProducer.publishCompleteSignal(
                    run.getId().toString(),
                    run.getModule().getId().toString(),
                    run.getAgentName()
            );

            // Capture IDs before the lambda — safe to read from initialized proxy after commit
            final UUID nextModuleId = run.getModule().getId();
            // Register synchronization to trigger next agent AFTER transaction commit.
            // Wrapping in a new TransactionTemplate is required: afterCommit() runs outside
            // any transaction, so lazy associations on entities loaded inside it (e.g.
            // AgentRun.module.project.aiConfig) would throw LazyInitializationException.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        // REQUIRES_NEW is mandatory here: the outer transaction's ThreadLocal
                        // state (isActualTransactionActive) is still true while afterCommit()
                        // runs (cleanup fires after all callbacks). PROPAGATION_REQUIRED would
                        // silently join the already-committed outer transaction, so any
                        // synchronization registered by dispatchAfterCommit() inside the lambda
                        // would be appended to the outer's already-iterated snapshot and never
                        // invoked — causing the Redis dispatch to be silently dropped.
                        TransactionTemplate tt = new TransactionTemplate(transactionManager);
                        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        tt.execute(status -> {
                            Module m = moduleRepository.findById(nextModuleId)
                                    .orElseThrow(() -> new EntityNotFoundException("Module not found: " + nextModuleId));
                            ensureAgentRunsExist(m);
                            triggerNextAgent(m, ownerId);
                            return null;
                        });
                        log.info("Triggered next agent after transaction commit for module={}", nextModuleId);
                    } catch (Exception e) {
                        log.error("Failed to trigger next agent after commit for module={}", nextModuleId, e);
                    }
                }
            });
        }
    }

    /**
     * Ensures all expected agent runs exist for a module.
     * Creates PENDING runs for agents that don't have any run record.
     */
    private void ensureAgentRunsExist(Module module) {
        AiConfig config = module.getProject().getAiConfig();
        List<String> activeAgents = config.getActiveAgents();

        List<AgentRun> existingRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());
        Set<String> existingAgentNames = existingRuns.stream()
                .map(AgentRun::getAgentName)
                .collect(java.util.stream.Collectors.toSet());

        int nextOrder = existingRuns.stream()
                .mapToInt(AgentRun::getRunOrder)
                .max()
                .orElse(0) + 1;

        for (String agentName : ORDERED_AGENTS) {
            if (!activeAgents.contains(agentName)) continue;

            if (!existingAgentNames.contains(agentName)) {
                log.info("Creating missing PENDING run for agent {} in module {}", agentName, module.getId());
                AgentRun newRun = AgentRun.builder()
                        .module(module)
                        .agentName(agentName)
                        .runOrder(nextOrder++)
                        .status(AgentRunStatus.PENDING)
                        .build();
                agentRunRepository.save(newRun);
            }
        }
    }

    /**
     * Approves an agent run and triggers the next agent in the pipeline.
     */
    @Transactional
    public void approveRun(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + runId));
        run.setStatus(AgentRunStatus.APPROVED);
        agentRunRepository.save(run);

        // Publish APPROVE signal to Redis Stream
        agentSignalProducer.publishApproveSignal(
                run.getId().toString(),
                run.getModule().getId().toString(),
                run.getAgentName()
        );

        String ownerId = run.getModule().getProject().getOwner().getId().toString();
        triggerNextAgent(run.getModule(), ownerId);
        log.info("Agent run approved: runId={}, signal published", runId);
    }

    /**
     * Rejects an agent run and creates a retry run for the rejected agent.
     */
    @Transactional
    public void rejectRun(UUID runId, String reason) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + runId));
        run.setStatus(AgentRunStatus.REJECTED);
        run.setErrorMessage(reason);
        run.setRetryCount(run.getRetryCount() != null ? run.getRetryCount() + 1 : 1);
        agentRunRepository.save(run);

        String ownerId = run.getModule().getProject().getOwner().getId().toString();
        sseService.broadcastRunRejected(ownerId, runId, reason);

        // Publish REJECT signal to Redis Stream
        agentSignalProducer.publishRejectSignal(
                run.getId().toString(),
                run.getModule().getId().toString(),
                run.getAgentName(),
                reason
        );

        log.info("Agent run rejected: runId={}, reason={}, signal published", runId, reason);

        // Reset to PENDING for retry (reuse existing run to avoid unique constraint on module_id+agent_name)
        run.setStatus(AgentRunStatus.PENDING);
        agentRunRepository.save(run);
        triggerNextAgent(run.getModule(), ownerId);
    }

    /**
     * Triggers the next agent(s) in the pipeline.
     * Cleans up stale PENDING records and triggers QA/Docs in parallel when Dev completes.
     */
    private void triggerNextAgent(Module module, String ownerId) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());

        // Get agents that have fully completed (not including RUNNING)
        Set<String> completedAgents = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.COMPLETED
                        || r.getStatus() == AgentRunStatus.APPROVED
                        || r.getStatus() == AgentRunStatus.AWAITING_APPROVAL
                        || r.getStatus() == AgentRunStatus.REJECTED
                        || r.getStatus() == AgentRunStatus.FAILED)
                .map(AgentRun::getAgentName)
                .collect(java.util.stream.Collectors.toSet());

        // Cleanup stale PENDING records for COMPLETED agents
        for (AgentRun run : allRuns) {
            if (run.getStatus() == AgentRunStatus.PENDING
                    && completedAgents.contains(run.getAgentName())) {
                run.setStatus(AgentRunStatus.TERMINATED);
                run.setErrorMessage("Skipped - agent already done");
                agentRunRepository.save(run);
            }
        }

        List<AgentRun> pending = agentRunRepository
                .findByModuleIdAndStatusOrderByRunOrder(module.getId(), AgentRunStatus.PENDING)
                .stream()
                .filter(r -> !completedAgents.contains(r.getAgentName()))
                .toList();

        log.info("triggerNextAgent: module={}, completedAgents={}, pendingCount={}",
                module.getId(), completedAgents, pending.size());

        if (!pending.isEmpty()) {
            List<AgentRun> parallelAgents = pending.stream()
                    .filter(r -> "qa".equals(r.getAgentName()) || "docs".equals(r.getAgentName()))
                    .toList();

            if (parallelAgents.size() == 2 && pending.size() == 2) {
                log.info("triggerNextAgent: triggering parallel QA and Docs agents");
                triggerParallelAgents(parallelAgents, module, ownerId);
            } else {
                AgentRun next = pending.get(0);
                log.info("triggerNextAgent: triggering next sequential agent: {}", next.getAgentName());
                triggerSingleAgent(next, module, ownerId);
            }
        } else if (allAgentsFinished(allRuns)) {
            log.info("triggerNextAgent: all agents finished, completing pipeline");
            completePipeline(module, ownerId);
        } else {
            log.warn("triggerNextAgent: no pending agents but pipeline not complete");
        }
    }

    private boolean allAgentsFinished(List<AgentRun> allRuns) {
        Set<String> activeAgents = allRuns.stream()
                .map(AgentRun::getAgentName)
                .collect(java.util.stream.Collectors.toSet());

        boolean hasQA = activeAgents.contains("qa");
        boolean hasDocs = activeAgents.contains("docs");

        if (hasQA && hasDocs) {
            AgentRun qaRun = allRuns.stream().filter(r -> "qa".equals(r.getAgentName())).findFirst().orElse(null);
            AgentRun docsRun = allRuns.stream().filter(r -> "docs".equals(r.getAgentName())).findFirst().orElse(null);
            boolean qaDone = qaRun != null && isAgentFullyCompleted(qaRun);
            boolean docsDone = docsRun != null && isAgentFullyCompleted(docsRun);
            return qaDone && docsDone;
        }

        for (AgentRun run : allRuns) {
            if (!isAgentFullyCompleted(run)) return false;
        }
        return true;
    }

    private boolean isAgentFullyCompleted(AgentRun run) {
        AgentRunStatus status = run.getStatus();
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.APPROVED
                || status == AgentRunStatus.REJECTED
                || status == AgentRunStatus.FAILED;
    }

    /**
     * Returns true when the only remaining PENDING agents are QA and/or Docs.
     * Used to bypass the owner-approval gate when transitioning from Dev to the
     * terminal parallel agents — per spec, Dev → (QA ‖ Docs) is always auto-triggered.
     */
    private boolean isNextStepParallelTerminal(Module module) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());
        List<String> pendingNames = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.PENDING)
                .map(AgentRun::getAgentName)
                .toList();
        return !pendingNames.isEmpty()
                && pendingNames.stream().allMatch(n -> "qa".equals(n) || "docs".equals(n));
    }

    private void triggerParallelAgents(List<AgentRun> agents, Module module, String ownerId) {
        for (AgentRun agentRun : agents) {
            agentRun.setStatus(AgentRunStatus.RUNNING);
            agentRun.setStartedAt(LocalDateTime.now());
            agentRunRepository.save(agentRun);
        }

        String firstAgent = agents.get(0).getAgentName();
        module.setCurrentAgent(firstAgent);
        module.setStatus(resolveRunningStatus(firstAgent));
        moduleRepository.save(module);

        for (AgentRun agentRun : agents) {
            try {
                Map<String, Object> taskConfig = buildFullTaskConfig(agentRun);
                String taskJson = objectMapper.writeValueAsString(taskConfig);
                UUID dispatchRunId = agentRun.getId();
                String dispatchAgentName = agentRun.getAgentName();
                // Dispatch AFTER the current transaction commits so the RUNNING status is
                // visible to handleAgentComplete before the Python agent can call back.
                dispatchAfterCommit("agent:next", taskJson, dispatchRunId, dispatchAgentName);
            } catch (JsonProcessingException e) {
                log.error("Failed to dispatch parallel agent task: runId={}", agentRun.getId(), e);
            }
        }
    }

    private void triggerSingleAgent(AgentRun next, Module module, String ownerId) {
        next.setStatus(AgentRunStatus.RUNNING);
        next.setStartedAt(LocalDateTime.now());
        agentRunRepository.save(next);

        module.setCurrentAgent(next.getAgentName());
        module.setStatus(resolveRunningStatus(next.getAgentName()));
        moduleRepository.save(module);

        // Immediately notify the UI so the row flips to RUNNING without waiting
        // for Python's STARTED event (which arrives after the Redis round-trip).
        broadcastStatusEvent(next, "STARTED", "Agent " + next.getAgentName() + " dispatched, starting…");

        try {
            Map<String, Object> taskConfig = buildFullTaskConfig(next);
            String taskJson = objectMapper.writeValueAsString(taskConfig);
            UUID dispatchRunId = next.getId();
            String dispatchAgentName = next.getAgentName();
            // Dispatch AFTER the current transaction commits so the RUNNING status is
            // visible to handleAgentComplete before the Python agent can call back.
            dispatchAfterCommit("agent:next", taskJson, dispatchRunId, dispatchAgentName);
        } catch (JsonProcessingException e) {
            log.error("Failed to dispatch next agent task: runId={}", next.getId(), e);
        }
    }

    /**
     * Sends a Redis message after the current transaction commits, or immediately if no
     * transaction is active. This prevents the race condition where Python receives a task
     * and calls back before the RUNNING status is committed to the database.
     */
    private void dispatchAfterCommit(String channel, String taskJson, UUID runId, String agentName) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisTemplate.convertAndSend(channel, taskJson);
                    log.info("Next agent triggered: runId={}, agent={}", runId, agentName);
                }
            });
        } else {
            redisTemplate.convertAndSend(channel, taskJson);
            log.info("Next agent triggered: runId={}, agent={}", runId, agentName);
        }
    }

    private void completePipeline(Module module, String ownerId) {
        module.setStatus(ModuleStatus.COMPLETED);
        module.setCurrentAgent(null);
        moduleRepository.save(module);
        sseService.broadcastModuleComplete(ownerId, module.getId());

        com.aidevplatform.api.dto.AgentEventDto dto = com.aidevplatform.api.dto.AgentEventDto.builder()
                .agentName("pipeline")
                .eventType("MODULE_COMPLETE")
                .message("All agents completed successfully")
                .severity("INFO")
                .timestamp(java.time.LocalDateTime.now())
                .build();
        uiEventBroadcaster.broadcast(dto, module.getId());
        log.info("Module pipeline completed: moduleId={}", module.getId());
    }

    private void broadcastStatusEvent(AgentRun run, String eventType, String message) {
        com.aidevplatform.api.dto.AgentEventDto dto = com.aidevplatform.api.dto.AgentEventDto.builder()
                .runId(run.getId())
                .agentName(run.getAgentName())
                .eventType(eventType)
                .message(message)
                .severity("INFO")
                .timestamp(java.time.LocalDateTime.now())
                .build();
        uiEventBroadcaster.broadcast(dto, run.getModule().getId());
    }

    /**
     * Builds task config with 3-tier memory context for agent resumption.
     * Integrates structured facts, session summary, and reasoning.
     */
    public Map<String, Object> buildFullTaskConfig(AgentRun run) {
        Module module = run.getModule();
        AiConfig config = module.getProject().getAiConfig();

        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());
        Map<String, String> previousOutputs = new LinkedHashMap<>();

        SessionCompressorService.ResumptionContext currentAgentContext = buildAgentMemoryContext(run);

        for (AgentRun prev : allRuns) {
            if (prev.getId().equals(run.getId())) break;
            if (prev.getStatus() == AgentRunStatus.COMPLETED
                    || prev.getStatus() == AgentRunStatus.APPROVED) {
                AgentReport prevReport = prev.getReport();
                if (prevReport != null) {
                    previousOutputs.put(prev.getAgentName(), prevReport.getSummary());
                }
            }
        }

        Map<String, Object> task = new HashMap<>();
        task.put("run_id", run.getId().toString());
        task.put("module_id", module.getId().toString());
        task.put("agent_name", run.getAgentName());
        task.put("invocation_mode", config.getInvocationMode().name());
        task.put("llm_provider", config.getLlmProvider());
        task.put("llm_base_url", config.getLlmBaseUrl());
        task.put("llm_model_name", config.getLlmModelName());
        task.put("llm_api_key", config.getLlmApiKey());
        task.put("llm_cli_command", config.getLlmCliCommand());
        task.put("temperature", config.getTemperature());
        task.put("max_tokens_per_task", config.getMaxTokensPerTask());
        task.put("output_language", config.getOutputLanguage());
        task.put("raw_requirement", module.getRawRequirement());
        task.put("tech_stack", config.getTechStack());
        task.put("coding_style_guide", config.getCodingStyleGuide());
        task.put("workspace_path", module.getProject().getWorkspacePath());
        task.put("module_name", module.getName());
        task.put("previousOutputs", previousOutputs);
        task.put("sessionMemory", currentAgentContext);

        // Build system prompt for Python agent
        String systemPrompt = buildSystemPrompt(run, currentAgentContext, objectMapper);
        task.put("systemPrompt", systemPrompt);

        return task;
    }

    private SessionCompressorService.ResumptionContext buildAgentMemoryContext(AgentRun run) {
        SessionCompressorService.ResumptionContext context = sessionCompressorService.getSessionContext(run.getId());

        if (context.sessionSummary() == null || context.sessionSummary().isEmpty()) {
            return buildAccumulatedMemoryContext(run);
        }

        return context;
    }

    private SessionCompressorService.ResumptionContext buildAccumulatedMemoryContext(AgentRun run) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(run.getModule().getId());

        com.fasterxml.jackson.databind.node.ObjectNode accumulatedFacts = objectMapper.createObjectNode();
        Map<String, String> allSummaries = new LinkedHashMap<>();

        for (AgentRun prev : allRuns) {
            if (prev.getId().equals(run.getId())) break;
            if (prev.getStatus() == AgentRunStatus.COMPLETED
                    || prev.getStatus() == AgentRunStatus.APPROVED) {
                AgentReport prevReport = prev.getReport();
                if (prevReport != null) {
                    allSummaries.put(prev.getAgentName(), prevReport.getSummary());

                    if (prevReport.getDeliverables() != null) {
                        accumulatedFacts.set(prev.getAgentName() + "_deliverables", objectMapper.valueToTree(prevReport.getDeliverables()));
                    }
                    if (prevReport.getIssuesFound() != null) {
                        accumulatedFacts.set(prev.getAgentName() + "_issues", objectMapper.valueToTree(prevReport.getIssuesFound()));
                    }
                }
            }
        }

        // Do not inject hardcoded assumptions here — reasoning must come from
        // actual previous-agent outputs, not from a fixed template that would
        // override the real requirement (e.g. injecting "REST API / JWT" for a
        // task that asked for nothing of the sort).
        String reasoning = allSummaries.isEmpty()
                ? ""
                : "Context accumulated from previous agents: " + String.join(", ", allSummaries.keySet());

        return new SessionCompressorService.ResumptionContext(accumulatedFacts,
                String.join("\n\n", allSummaries.values()),
                reasoning,
                null);
    }

    /**
     * Formats session context as a system prompt string for Python agent consumption.
     */
    private String buildSystemPrompt(AgentRun run, SessionCompressorService.ResumptionContext context, ObjectMapper objectMapper) {
        if (context == null) {
            return "You are an AI agent assistant. You will work on your assigned tasks.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Agent Context\n");
        sb.append("Current Agent: ").append(run.getAgentName()).append("\n\n");

        sb.append("## Completed Work (Structured Facts)\n");
        if (context.structuredFacts() != null && context.structuredFacts().size() > 0) {
            sb.append(context.structuredFacts().toPrettyString()).append("\n\n");
        } else {
            sb.append("(No structured facts yet)\n\n");
        }

        sb.append("## Session Summary\n");
        if (context.sessionSummary() != null && !context.sessionSummary().isBlank()) {
            sb.append(context.sessionSummary()).append("\n\n");
        } else {
            sb.append("(No session summary available)\n\n");
        }

        sb.append("## Design Decisions & Trade-offs\n");
        if (context.reasoningSummary() != null && !context.reasoningSummary().isBlank()) {
            sb.append(context.reasoningSummary()).append("\n\n");
        }

        sb.append("## Recent Activity\n");
        if (context.recentRawMessages() != null && !context.recentRawMessages().isBlank()) {
            sb.append("```plaintext\n");
            sb.append(context.recentRawMessages());
            sb.append("\n```\n");
        } else {
            sb.append("(No recent activity - this is expected for a new agent or fresh start)\n");
        }

        return sb.toString();
    }

    private ModuleStatus resolveRunningStatus(String agentName) {
        return switch (agentName) {
            case "pm" -> ModuleStatus.PM_RUNNING;
            case "architect" -> ModuleStatus.ARCHITECT_RUNNING;
            case "dev" -> ModuleStatus.DEV_RUNNING;
            case "qa" -> ModuleStatus.QA_RUNNING;
            case "docs" -> ModuleStatus.DOCS_RUNNING;
            default -> ModuleStatus.PENDING_RUN;
        };
    }

    private Map<String, Object> buildAgentTask(Module module, AiConfig config) {
        List<AgentRun> runs = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());
        AgentRun firstRun = runs.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.PENDING)
                .findFirst()
                .orElse(null);

        if (firstRun != null) {
            firstRun.setStatus(AgentRunStatus.RUNNING);
            firstRun.setStartedAt(LocalDateTime.now());
            agentRunRepository.save(firstRun);

            module.setCurrentAgent(firstRun.getAgentName());
            module.setStatus(resolveRunningStatus(firstRun.getAgentName()));
            moduleRepository.save(module);

            return buildFullTaskConfig(firstRun);
        }

        log.warn("No pending run found for module {}, building minimal task", module.getId());
        Map<String, Object> task = new HashMap<>();
        task.put("run_id", "unknown");
        task.put("module_id", module.getId().toString());
        task.put("agent_name", "pm");
        task.put("invocation_mode", config.getInvocationMode().name());
        task.put("llm_provider", config.getLlmProvider());
        task.put("llm_base_url", config.getLlmBaseUrl());
        task.put("llm_model_name", config.getLlmModelName());
        task.put("llm_api_key", config.getLlmApiKey());
        task.put("llm_cli_command", config.getLlmCliCommand());
        task.put("temperature", config.getTemperature());
        task.put("max_tokens_per_task", config.getMaxTokensPerTask());
        task.put("output_language", config.getOutputLanguage());
        task.put("raw_requirement", module.getRawRequirement());
        task.put("tech_stack", config.getTechStack());
        task.put("coding_style_guide", config.getCodingStyleGuide());
        task.put("workspace_path", module.getProject().getWorkspacePath());
        task.put("module_name", module.getName());
        task.put("previousOutputs", Map.of());
        return task;
    }

    @Transactional
    public String resumePipeline(UUID moduleId) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId);
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));
        String ownerId = module.getProject().getOwner().getId().toString();

        // Scenario 0: Detect and clean up stuck RUNNING agents (>30 min)
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(30);
        for (AgentRun run : allRuns) {
            if (run.getStatus() == AgentRunStatus.RUNNING
                    && run.getStartedAt() != null
                    && run.getStartedAt().isBefore(stuckThreshold)) {
                log.warn("Detected stuck run {} for agent {}", run.getId(), run.getAgentName());
                run.setStatus(AgentRunStatus.FAILED);
                run.setErrorMessage("Timeout - agent didn't report back within 30 minutes");
                agentRunRepository.save(run);
            }
        }

        Optional<AgentRun> awaitingOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.AWAITING_APPROVAL)
                .findFirst();
        if (awaitingOpt.isPresent()) {
            AgentRun run = awaitingOpt.get();
            broadcastStatusEvent(run, "AWAITING_APPROVAL", "Awaiting owner approval");
            log.info("resumePipeline: re-broadcast AWAITING_APPROVAL for run={}, agent={}",
                    run.getId(), run.getAgentName());
            return "AWAITING_APPROVAL:" + run.getAgentName();
        }

        Optional<AgentRun> rejectedOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.REJECTED)
                .findFirst();
        if (rejectedOpt.isPresent()) {
            AgentRun rejectedRun = rejectedOpt.get();
            int retryCount = rejectedRun.getRetryCount() != null ? rejectedRun.getRetryCount() : 0;
            if (retryCount >= 3) {
                log.warn("Max retry attempts (3) exceeded for agent {}", rejectedRun.getAgentName());
                triggerNextAgent(module, ownerId);
                return "MAX_RETRIES_EXCEEDED:" + rejectedRun.getAgentName();
            }

            // Reset to PENDING for retry (reuse existing run to avoid unique constraint on module_id+agent_name)
            rejectedRun.setStatus(AgentRunStatus.PENDING);
            rejectedRun.setRetryCount(retryCount + 1);
            agentRunRepository.save(rejectedRun);
            triggerNextAgent(module, ownerId);
            log.info("resumePipeline: reset rejected run={} to PENDING for retry", rejectedRun.getId());
            return "RETRY_CREATED:" + rejectedRun.getAgentName();
        }

        Optional<AgentRun> runningOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.RUNNING)
                .findFirst();
        if (runningOpt.isPresent()) {
            AgentRun run = runningOpt.get();
            try {
                Map<String, Object> taskConfig = buildFullTaskConfig(run);
                redisTemplate.convertAndSend("agent:next", objectMapper.writeValueAsString(taskConfig));
                log.info("resumePipeline: re-dispatched RUNNING run={}, agent={}",
                        run.getId(), run.getAgentName());
                return "REDISPATCHED:" + run.getAgentName();
            } catch (JsonProcessingException e) {
                log.error("resumePipeline: failed to serialize task for run={}", run.getId(), e);
                return "ERROR:" + e.getMessage();
            }
        }

        triggerNextAgent(module, ownerId);
        log.info("resumePipeline: triggered next pending agent for module={}", moduleId);
        return "TRIGGERED_NEXT";
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getInterruptedRuns() {
        List<AgentRun> running = agentRunRepository.findByStatus(AgentRunStatus.RUNNING);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentRun run : running) {
            try {
                result.add(buildFullTaskConfig(run));
            } catch (Exception e) {
                log.warn("Skipping interrupted run {} due to missing context: {}", run.getId(), e.getMessage());
            }
        }
        return result;
    }

    private AgentReport mapToReport(ObjectNode node, AgentRun run) {
        if (node == null || node.isNull()) {
            return AgentReport.builder()
                    .run(run)
                    .summary("No report provided")
                    .build();
        }

        // Python sends snake_case: confidence_score, confidence_reason, tokens_used
        String summary = node.path("summary").asText("");
        java.math.BigDecimal confidenceScore = null;
        JsonNode csNode = node.path("confidence_score");
        if (!csNode.isMissingNode() && !csNode.isNull()) {
            confidenceScore = csNode.decimalValue();
        }
        String confidenceReason = node.path("confidence_reason").isMissingNode() ? null
                : node.path("confidence_reason").asText(null);
        Integer tokensUsed = node.path("tokens_used").isMissingNode() ? null
                : node.path("tokens_used").intValue();
        Integer durationSeconds = node.path("duration_seconds").isMissingNode() ? null
                : node.path("duration_seconds").intValue();

        AgentReport report = AgentReport.builder()
                .run(run)
                .summary(summary)
                .confidenceScore(confidenceScore)
                .confidenceReason(confidenceReason)
                .tokensUsed(tokensUsed)
                .durationSeconds(durationSeconds)
                .build();

        JsonNode deliverablesNode = node.path("deliverables");
        if (deliverablesNode.isArray()) {
            List<Deliverable> deliverables = new ArrayList<>();
            for (JsonNode m : (ArrayNode) deliverablesNode) {
                deliverables.add(Deliverable.builder()
                        .type(m.path("type").asText(null))
                        .name(m.path("name").asText(null))
                        .filePath(m.path("file_path").asText(null))
                        .description(m.path("description").asText(null))
                        .lines(m.path("lines").isMissingNode() ? null : m.path("lines").intValue())
                        .build());
            }
            report.setDeliverables(deliverables);
        }

        JsonNode issuesNode = node.path("issues_found");
        if (issuesNode.isArray()) {
            List<Issue> issues = new ArrayList<>();
            for (JsonNode m : (ArrayNode) issuesNode) {
                issues.add(Issue.builder()
                        .severity(m.path("severity").asText(null))
                        .description(m.path("description").asText(null))
                        .suggestedAction(m.path("suggested_action").asText(null))
                        .build());
            }
            report.setIssuesFound(issues);
        }

        JsonNode nextStepsNode = node.path("next_steps");
        if (nextStepsNode.isArray()) {
            List<NextStep> steps = new ArrayList<>();
            for (JsonNode m : (ArrayNode) nextStepsNode) {
                steps.add(NextStep.builder()
                        .action(m.path("action").asText(null))
                        .agent(m.path("agent").asText(null))
                        .priority(m.path("priority").asText(null))
                        .build());
            }
            report.setNextSteps(steps);
        }

        JsonNode decisionsNode = node.path("owner_decisions_needed");
        if (decisionsNode.isArray()) {
            List<OwnerDecision> decisions = new ArrayList<>();
            for (JsonNode m : (ArrayNode) decisionsNode) {
                List<String> options = new ArrayList<>();
                JsonNode optsNode = m.path("options");
                if (optsNode.isArray()) {
                    for (JsonNode opt : optsNode) {
                        options.add(opt.asText());
                    }
                }
                decisions.add(OwnerDecision.builder()
                        .question(m.path("question").asText(null))
                        .options(options)
                        .impact(m.path("impact").asText(null))
                        .build());
            }
            report.setOwnerDecisionsNeeded(decisions);
        }

        return report;
    }
}

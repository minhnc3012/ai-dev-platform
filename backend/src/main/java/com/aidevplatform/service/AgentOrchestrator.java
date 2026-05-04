package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentCallbackDto;
import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.AgentTemplate;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.WorkflowDefinition;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.domain.model.*;
import com.aidevplatform.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.stream.Collectors;

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
    private final WorkflowService workflowService;
    private final AgentTemplateRepository agentTemplateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Async
    @Transactional
    public void runAgentPipeline(UUID moduleId) {
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));

        if (module.getStatus() != ModuleStatus.DRAFT
                && module.getStatus() != ModuleStatus.PENDING_RUN
                && module.getStatus() != ModuleStatus.FAILED) {
            log.warn("Module {} already in progress or completed (status={}), skipping",
                    moduleId, module.getStatus());
            return;
        }

        if (module.getWorkflowId() == null) {
            log.error("Module {} has no workflow assigned — cannot start pipeline", moduleId);
            module.setStatus(ModuleStatus.FAILED);
            moduleRepository.save(module);
            return;
        }

        startWorkflowPipeline(module);
    }

    @Transactional
    public void handleAgentComplete(AgentCallbackDto callback) {
        AgentRun run = agentRunRepository.findById(callback.getRunId())
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + callback.getRunId()));

        if (run.getStatus() != AgentRunStatus.RUNNING) {
            log.warn("Ignoring duplicate completion for run {} — status is {}",
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

        AgentReport report = agentReportRepository.findByRunId(run.getId())
                .map(existing -> {
                    AgentReport updated = mapToReport(callback.getReport(), run);
                    updated.setId(existing.getId());
                    updated.setCreatedAt(existing.getCreatedAt());
                    return updated;
                })
                .orElseGet(() -> mapToReport(callback.getReport(), run));
        agentReportRepository.save(report);

        agentRunRepository.flush();
        agentReportRepository.flush();
        moduleRepository.flush();

        try {
            sessionCompressorService.compressSessionFromReport(run.getId());
        } catch (Exception e) {
            log.warn("Failed to compress session for run {}: {}", run.getId(), e.getMessage());
        }

        String ownerId = run.getModule().getProject().getOwner().getId().toString();
        handleWorkflowAgentComplete(run, ownerId);
    }

    @Transactional
    public void approveRun(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + runId));
        run.setStatus(AgentRunStatus.APPROVED);
        agentRunRepository.save(run);

        agentSignalProducer.publishApproveSignal(
                run.getId().toString(),
                run.getModule().getId().toString(),
                run.getAgentName());

        String ownerId = run.getModule().getProject().getOwner().getId().toString();
        triggerNextWorkflowStage(run.getModule(), ownerId);
        log.info("Agent run approved: runId={}", runId);
    }

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
        agentSignalProducer.publishRejectSignal(
                run.getId().toString(),
                run.getModule().getId().toString(),
                run.getAgentName(),
                reason);

        // Retry: reset to RUNNING and re-dispatch the same stage
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        agentRunRepository.save(run);

        try {
            WorkflowDefinition workflow = workflowService.getById(run.getModule().getWorkflowId());
            Optional<WorkflowStage> stageOpt = workflowService.findStageById(workflow.getStages(), run.getStageId());
            Map<String, Object> taskConfig = stageOpt
                    .map(stage -> buildWorkflowTaskConfig(run, stage))
                    .orElseGet(() -> buildFullTaskConfig(run));
            String taskJson = objectMapper.writeValueAsString(taskConfig);
            dispatchAfterCommit("agent:next", taskJson, run.getId(), run.getAgentName());
        } catch (JsonProcessingException e) {
            log.error("Failed to re-dispatch rejected run {}: {}", runId, e.getMessage());
        }
        log.info("Agent run rejected and retried: runId={}, reason={}", runId, reason);
    }

    @Transactional
    public String resumePipeline(UUID moduleId) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId);
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));
        String ownerId = module.getProject().getOwner().getId().toString();

        // Clean up stuck RUNNING agents (>30 min)
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(30);
        for (AgentRun run : allRuns) {
            if (run.getStatus() == AgentRunStatus.RUNNING
                    && run.getStartedAt() != null
                    && run.getStartedAt().isBefore(stuckThreshold)) {
                log.warn("Detected stuck run {} for agent {}", run.getId(), run.getAgentName());
                run.setStatus(AgentRunStatus.FAILED);
                run.setErrorMessage("Timeout — agent didn't respond within 30 minutes");
                agentRunRepository.save(run);
            }
        }

        Optional<AgentRun> awaitingOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.AWAITING_APPROVAL)
                .findFirst();
        if (awaitingOpt.isPresent()) {
            AgentRun run = awaitingOpt.get();
            broadcastStatusEvent(run, "AWAITING_APPROVAL", "Awaiting owner approval");
            log.info("resumePipeline: re-broadcast AWAITING_APPROVAL for run={}", run.getId());
            return "AWAITING_APPROVAL:" + run.getAgentName();
        }

        Optional<AgentRun> runningOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.RUNNING)
                .findFirst();
        if (runningOpt.isPresent()) {
            AgentRun run = runningOpt.get();
            try {
                Map<String, Object> taskConfig = buildFullTaskConfig(run);
                redisTemplate.convertAndSend("agent:next", objectMapper.writeValueAsString(taskConfig));
                log.info("resumePipeline: re-dispatched RUNNING run={}", run.getId());
                return "REDISPATCHED:" + run.getAgentName();
            } catch (JsonProcessingException e) {
                log.error("resumePipeline: failed to serialize task for run={}", run.getId(), e);
                return "ERROR:" + e.getMessage();
            }
        }

        // If there are FAILED runs, retry them first before looking for next stages
        Optional<AgentRun> failedOpt = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.FAILED)
                .findFirst();
        if (failedOpt.isPresent()) {
            AgentRun failedRun = failedOpt.get();
            failedRun.setStatus(AgentRunStatus.RUNNING);
            failedRun.setStartedAt(LocalDateTime.now());
            failedRun.setCompletedAt(null);
            failedRun.setErrorMessage(null);
            agentRunRepository.save(failedRun);
            try {
                WorkflowDefinition workflow = workflowService.getById(module.getWorkflowId());
                Optional<WorkflowStage> stageOpt = workflowService.findStageById(
                        workflow.getStages(), failedRun.getStageId());
                Map<String, Object> taskConfig = stageOpt
                        .map(stage -> buildWorkflowTaskConfig(failedRun, stage))
                        .orElseGet(() -> buildFullTaskConfig(failedRun));
                redisTemplate.convertAndSend("agent:next", objectMapper.writeValueAsString(taskConfig));
                log.info("resumePipeline: retrying FAILED run={}, agent={}", failedRun.getId(), failedRun.getAgentName());
                return "RETRIED:" + failedRun.getAgentName();
            } catch (JsonProcessingException e) {
                log.error("resumePipeline: failed to serialize retry for run={}", failedRun.getId(), e);
                return "ERROR:" + e.getMessage();
            }
        }

        triggerNextWorkflowStage(module, ownerId);
        log.info("resumePipeline: triggered next pending stage for module={}", moduleId);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Workflow execution
    // ─────────────────────────────────────────────────────────────────────────

    private void startWorkflowPipeline(Module module) {
        WorkflowDefinition workflow = workflowService.getById(module.getWorkflowId());
        module.setStatus(ModuleStatus.STAGE_RUNNING);
        moduleRepository.save(module);

        List<WorkflowStage> stagesToRun = workflowService.getNextStagesToRun(
                workflow.getStages(), Set.of(), Set.of());

        for (WorkflowStage stage : stagesToRun) {
            createAndDispatchWorkflowStage(stage, module, workflow);
        }

        String ownerId = module.getProject().getOwner().getId().toString();
        sseService.broadcastModuleUpdateToOwner(ownerId, module.getId(), "PIPELINE_STARTED");
        log.info("Workflow pipeline started for module={}, workflow={}", module.getId(), workflow.getId());
    }

    private void createAndDispatchWorkflowStage(WorkflowStage stage, Module module, WorkflowDefinition workflow) {
        AgentTemplate template = agentTemplateRepository.findById(UUID.fromString(stage.getAgentTemplateId()))
                .orElseThrow(() -> new EntityNotFoundException("AgentTemplate not found: " + stage.getAgentTemplateId()));

        // Reuse existing run if one already exists for this (module, stage) — avoids duplicate-key
        // violation on resume/retry after a FAILED or stuck run.
        AgentRun run = agentRunRepository.findByModuleIdAndStageId(module.getId(), stage.getId())
                .map(existing -> {
                    existing.setStatus(AgentRunStatus.RUNNING);
                    existing.setStartedAt(LocalDateTime.now());
                    existing.setCompletedAt(null);
                    existing.setErrorMessage(null);
                    existing.setTokensUsed(0);
                    existing.setAgentTemplate(template); // ensure template is always linked
                    existing.setAgentName(template.getName());
                    return existing;
                })
                .orElseGet(() -> {
                    int order = agentRunRepository.findMaxRunOrderByModuleId(module.getId()) + 1;
                    return AgentRun.builder()
                            .module(module)
                            .agentName(template.getName())
                            .stageId(stage.getId())
                            .agentTemplate(template)
                            .runOrder(order)
                            .status(AgentRunStatus.RUNNING)
                            .startedAt(LocalDateTime.now())
                            .build();
                });
        agentRunRepository.save(run);

        // Broadcast STARTED after commit — the run must be visible in DB before the UI queries it
        final UUID startedRunId = run.getId();
        final String startedAgentName = run.getAgentName();
        final UUID startedModuleId = run.getModule().getId();
        final String startedTemplateName = template.getName();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                com.aidevplatform.api.dto.AgentEventDto dto =
                        com.aidevplatform.api.dto.AgentEventDto.builder()
                                .runId(startedRunId).agentName(startedAgentName)
                                .eventType("STARTED").message("Agent '" + startedTemplateName + "' dispatched…")
                                .severity("INFO").timestamp(LocalDateTime.now()).build();
                uiEventBroadcaster.broadcast(dto, startedModuleId);
            }
        });

        try {
            Map<String, Object> taskConfig = buildWorkflowTaskConfig(run, stage);
            String taskJson = objectMapper.writeValueAsString(taskConfig);
            dispatchAfterCommit("agent:next", taskJson, run.getId(), run.getAgentName());
        } catch (JsonProcessingException e) {
            log.error("Failed to dispatch workflow stage: runId={}", run.getId(), e);
        }
    }

    private void handleWorkflowAgentComplete(AgentRun run, String ownerId) {
        WorkflowDefinition workflow = workflowService.getById(run.getModule().getWorkflowId());
        Optional<WorkflowStage> stageOpt = workflowService.findStageById(
                workflow.getStages(), run.getStageId());

        boolean pauseForReview = stageOpt
                .map(s -> s.getPauseForReview() != null
                        ? s.getPauseForReview()
                        : Boolean.TRUE.equals(workflow.getDefaultPauseForReview()))
                .orElse(Boolean.TRUE.equals(workflow.getDefaultPauseForReview()));

        if (pauseForReview) {
            run.setStatus(AgentRunStatus.AWAITING_APPROVAL);
            agentRunRepository.save(run);
            sseService.broadcastAgentAwaitingApproval(ownerId, run.getId());

            final UUID pendingRunId = run.getId();
            final UUID pendingModuleId = run.getModule().getId();
            final String pendingAgentName = run.getAgentName();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    com.aidevplatform.api.dto.AgentEventDto dto =
                            com.aidevplatform.api.dto.AgentEventDto.builder()
                                    .runId(pendingRunId).agentName(pendingAgentName)
                                    .eventType("AWAITING_APPROVAL").message("Awaiting owner approval")
                                    .severity("INFO").timestamp(LocalDateTime.now()).build();
                    uiEventBroadcaster.broadcast(dto, pendingModuleId);
                }
            });
        } else {
            final UUID nextModuleId = run.getModule().getId();
            final UUID autoCompletedRunId = run.getId();
            final String autoCompletedAgentName = run.getAgentName();
            final String capturedOwnerId = ownerId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Report is now committed — notify the UI immediately so the report pane renders
                    // without waiting for the next poll cycle (auto-approve: no AWAITING_APPROVAL event)
                    com.aidevplatform.api.dto.AgentEventDto completedDto =
                            com.aidevplatform.api.dto.AgentEventDto.builder()
                                    .runId(autoCompletedRunId).agentName(autoCompletedAgentName)
                                    .eventType("COMPLETED").message("Completed — auto-approved")
                                    .severity("INFO").timestamp(LocalDateTime.now()).build();
                    uiEventBroadcaster.broadcast(completedDto, nextModuleId);

                    try {
                        TransactionTemplate tt = new TransactionTemplate(transactionManager);
                        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        tt.execute(status -> {
                            Module m = moduleRepository.findById(nextModuleId)
                                    .orElseThrow(() -> new EntityNotFoundException("Module not found: " + nextModuleId));
                            triggerNextWorkflowStage(m, capturedOwnerId);
                            return null;
                        });
                    } catch (Exception e) {
                        log.error("Failed to trigger next workflow stage after commit for module={}", nextModuleId, e);
                    }
                }
            });
        }
    }

    private void triggerNextWorkflowStage(Module module, String ownerId) {
        WorkflowDefinition workflow = workflowService.getById(module.getWorkflowId());
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());

        Set<String> completedStageIds = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.COMPLETED
                        || r.getStatus() == AgentRunStatus.APPROVED)
                .map(AgentRun::getStageId)
                .collect(Collectors.toSet());

        // AWAITING_APPROVAL is treated as "in progress" so parallel siblings are not re-dispatched
        // when the owner approves one agent while the other is still awaiting review.
        Set<String> runningStageIds = allRuns.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.RUNNING
                        || r.getStatus() == AgentRunStatus.AWAITING_APPROVAL)
                .map(AgentRun::getStageId)
                .collect(Collectors.toSet());

        List<WorkflowStage> stagesToRun = workflowService.getNextStagesToRun(
                workflow.getStages(), completedStageIds, runningStageIds);

        if (stagesToRun.isEmpty()) {
            if (workflowService.isWorkflowComplete(workflow.getStages(), completedStageIds)) {
                completePipeline(module, ownerId);
            }
            return;
        }

        for (WorkflowStage stage : stagesToRun) {
            createAndDispatchWorkflowStage(stage, module, workflow);
        }
    }

    private Map<String, Object> buildWorkflowTaskConfig(AgentRun run, WorkflowStage stage) {
        Map<String, Object> task = buildFullTaskConfig(run);
        AgentTemplate template = run.getAgentTemplate();
        if (template != null) {
            Map<String, Object> agentDef = new HashMap<>();
            agentDef.put("name", template.getName());
            agentDef.put("role", template.getRole());
            agentDef.put("goal", template.getGoal());
            agentDef.put("backstory_template", template.getBackstoryTemplate());
            agentDef.put("task_description_template", template.getTaskDescriptionTemplate());
            task.put("agent_template", agentDef);
            task.put("agent_name", template.getAgentKey());
        }
        return task;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task config & memory
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> buildFullTaskConfig(AgentRun run) {
        Module module = run.getModule();
        var config = module.getProject().getAiConfig();

        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(module.getId());
        Map<String, String> previousOutputs = new LinkedHashMap<>();
        SessionCompressorService.ResumptionContext currentAgentContext = buildAgentMemoryContext(run);

        for (AgentRun prev : allRuns) {
            if (prev.getId().equals(run.getId())) break;
            if (prev.getStatus() == AgentRunStatus.COMPLETED || prev.getStatus() == AgentRunStatus.APPROVED) {
                AgentReport prevReport = prev.getReport();
                if (prevReport != null) previousOutputs.put(prev.getAgentName(), prevReport.getSummary());
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
        task.put("llm_cli_settings_file", config.getLlmCliSettingsFile());
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
        task.put("systemPrompt", buildSystemPrompt(run, currentAgentContext, objectMapper));
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
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        com.fasterxml.jackson.databind.node.ObjectNode accumulatedFacts = om.createObjectNode();
        Map<String, String> allSummaries = new LinkedHashMap<>();

        for (AgentRun prev : allRuns) {
            if (prev.getId().equals(run.getId())) break;
            if (prev.getStatus() == AgentRunStatus.COMPLETED || prev.getStatus() == AgentRunStatus.APPROVED) {
                AgentReport prevReport = prev.getReport();
                if (prevReport != null) {
                    allSummaries.put(prev.getAgentName(), prevReport.getSummary());
                    if (prevReport.getDeliverables() != null)
                        accumulatedFacts.set(prev.getAgentName() + "_deliverables", om.valueToTree(prevReport.getDeliverables()));
                    if (prevReport.getIssuesFound() != null)
                        accumulatedFacts.set(prev.getAgentName() + "_issues", om.valueToTree(prevReport.getIssuesFound()));
                }
            }
        }

        String reasoning = allSummaries.isEmpty() ? ""
                : "Context from previous agents: " + String.join(", ", allSummaries.keySet());
        return new SessionCompressorService.ResumptionContext(
                accumulatedFacts, String.join("\n\n", allSummaries.values()), reasoning, null);
    }

    private String buildSystemPrompt(AgentRun run, SessionCompressorService.ResumptionContext context, ObjectMapper om) {
        if (context == null) return "You are an AI agent. Work on your assigned tasks.";
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent Context\nCurrent Agent: ").append(run.getAgentName()).append("\n\n");
        sb.append("## Completed Work (Structured Facts)\n");
        if (context.structuredFacts() != null && context.structuredFacts().size() > 0)
            sb.append(context.structuredFacts().toPrettyString()).append("\n\n");
        else sb.append("(No structured facts yet)\n\n");
        sb.append("## Session Summary\n");
        if (context.sessionSummary() != null && !context.sessionSummary().isBlank())
            sb.append(context.sessionSummary()).append("\n\n");
        else sb.append("(No session summary)\n\n");
        sb.append("## Design Decisions\n");
        if (context.reasoningSummary() != null && !context.reasoningSummary().isBlank())
            sb.append(context.reasoningSummary()).append("\n\n");
        sb.append("## Recent Activity\n");
        if (context.recentRawMessages() != null && !context.recentRawMessages().isBlank())
            sb.append("```plaintext\n").append(context.recentRawMessages()).append("\n```\n");
        else sb.append("(No recent activity)\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void completePipeline(Module module, String ownerId) {
        module.setStatus(ModuleStatus.COMPLETED);
        module.setCurrentAgent(null);
        moduleRepository.save(module);
        sseService.broadcastModuleComplete(ownerId, module.getId());
        com.aidevplatform.api.dto.AgentEventDto dto = com.aidevplatform.api.dto.AgentEventDto.builder()
                .agentName("pipeline").eventType("MODULE_COMPLETE")
                .message("All agents completed successfully")
                .severity("INFO").timestamp(LocalDateTime.now()).build();
        uiEventBroadcaster.broadcast(dto, module.getId());
        log.info("Module pipeline completed: moduleId={}", module.getId());
    }

    private void broadcastStatusEvent(AgentRun run, String eventType, String message) {
        com.aidevplatform.api.dto.AgentEventDto dto = com.aidevplatform.api.dto.AgentEventDto.builder()
                .runId(run.getId()).agentName(run.getAgentName())
                .eventType(eventType).message(message)
                .severity("INFO").timestamp(LocalDateTime.now()).build();
        uiEventBroadcaster.broadcast(dto, run.getModule().getId());
    }

    private void dispatchAfterCommit(String channel, String taskJson, UUID runId, String agentName) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redisTemplate.convertAndSend(channel, taskJson);
                    log.info("Agent dispatched: runId={}, agent={}", runId, agentName);
                }
            });
        } else {
            redisTemplate.convertAndSend(channel, taskJson);
            log.info("Agent dispatched: runId={}, agent={}", runId, agentName);
        }
    }

    private AgentReport mapToReport(ObjectNode node, AgentRun run) {
        if (node == null || node.isNull()) {
            return AgentReport.builder().run(run).summary("No report provided").build();
        }
        String summary = node.path("summary").asText("");
        java.math.BigDecimal confidenceScore = null;
        JsonNode csNode = node.path("confidence_score");
        if (!csNode.isMissingNode() && !csNode.isNull()) confidenceScore = csNode.decimalValue();
        String confidenceReason = node.path("confidence_reason").isMissingNode() ? null
                : node.path("confidence_reason").asText(null);
        Integer tokensUsed = node.path("tokens_used").isMissingNode() ? null
                : node.path("tokens_used").intValue();
        Integer durationSeconds = node.path("duration_seconds").isMissingNode() ? null
                : node.path("duration_seconds").intValue();

        AgentReport report = AgentReport.builder()
                .run(run).summary(summary)
                .confidenceScore(confidenceScore).confidenceReason(confidenceReason)
                .tokensUsed(tokensUsed).durationSeconds(durationSeconds)
                .build();

        JsonNode deliverablesNode = node.path("deliverables");
        if (deliverablesNode.isArray()) {
            List<Deliverable> deliverables = new ArrayList<>();
            for (JsonNode m : (ArrayNode) deliverablesNode) {
                deliverables.add(Deliverable.builder()
                        .type(m.path("type").asText(null)).name(m.path("name").asText(null))
                        .filePath(m.path("file_path").asText(null)).description(m.path("description").asText(null))
                        .lines(m.path("lines").isMissingNode() ? null : m.path("lines").intValue()).build());
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
                        .suggestedAction(m.path("suggested_action").asText(null)).build());
            }
            report.setIssuesFound(issues);
        }

        JsonNode nextStepsNode = node.path("next_steps");
        if (nextStepsNode.isArray()) {
            List<NextStep> steps = new ArrayList<>();
            for (JsonNode m : (ArrayNode) nextStepsNode) {
                steps.add(NextStep.builder()
                        .action(m.path("action").asText(null)).agent(m.path("agent").asText(null))
                        .priority(m.path("priority").asText(null)).build());
            }
            report.setNextSteps(steps);
        }

        JsonNode decisionsNode = node.path("owner_decisions_needed");
        if (decisionsNode.isArray()) {
            List<OwnerDecision> decisions = new ArrayList<>();
            for (JsonNode m : (ArrayNode) decisionsNode) {
                List<String> options = new ArrayList<>();
                JsonNode optsNode = m.path("options");
                if (optsNode.isArray()) for (JsonNode opt : optsNode) options.add(opt.asText());
                decisions.add(OwnerDecision.builder()
                        .question(m.path("question").asText(null))
                        .options(options).impact(m.path("impact").asText(null)).build());
            }
            report.setOwnerDecisionsNeeded(decisions);
        }

        return report;
    }
}

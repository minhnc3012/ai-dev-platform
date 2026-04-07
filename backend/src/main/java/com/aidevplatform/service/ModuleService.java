package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.repository.AgentRunRepository;
import com.aidevplatform.repository.AgentReportRepository;
import com.aidevplatform.repository.ModuleRepository;
import com.aidevplatform.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleService {

    private final ModuleRepository moduleRepository;
    private final ProjectRepository projectRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentReportRepository agentReportRepository;
    private final AgentOrchestrator agentOrchestrator;

    public List<Module> findByProject(UUID projectId) {
        return moduleRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public Module findById(UUID id) {
        return moduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + id));
    }

    /**
     * Find a module with its project, AI config, and owner eagerly loaded.
     * Use in Vaadin UI views to avoid LazyInitializationException outside a transaction.
     */
    public Module findByIdWithDetails(UUID id) {
        return moduleRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + id));
    }

    @Transactional
    public Module create(UUID projectId, String name, String description) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));

        Module module = Module.builder()
                .project(project)
                .name(name)
                .description(description)
                .status(ModuleStatus.DRAFT)
                .build();

        Module saved = moduleRepository.save(module);
        log.info("Module created: id={}, name={}", saved.getId(), name);
        return saved;
    }

    @Transactional
    public Module update(UUID id, String name, String description, String rawRequirement) {
        Module module = findById(id);
        module.setName(name);
        module.setDescription(description);
        if (rawRequirement != null) {
            module.setRawRequirement(rawRequirement);
        }
        return moduleRepository.save(module);
    }

    @Transactional
    public void setRequirementFile(UUID moduleId, String filePath, String rawContent) {
        Module module = findById(moduleId);
        module.setReqFilePath(filePath);
        module.setRawRequirement(rawContent);
        moduleRepository.save(module);
        log.info("Requirement file set for module: {}", moduleId);
    }

    /**
     * Triggers the full agent pipeline for this module.
     * Prerequisites: module must have a raw requirement and the project must have an AI config.
     */
    @Transactional
    public void triggerAgentRun(UUID moduleId) {
        Module module = findById(moduleId);
        if (module.getRawRequirement() == null || module.getRawRequirement().isBlank()) {
            throw new IllegalStateException("Module has no requirement to process: " + moduleId);
        }
        if (module.getProject().getAiConfig() == null) {
            throw new IllegalStateException("Project has no AI config configured: " +
                    module.getProject().getId());
        }
        // Guard against double-trigger from the UI (e.g. two rapid clicks)
        if (module.getStatus() != ModuleStatus.DRAFT && module.getStatus() != ModuleStatus.FAILED) {
            throw new IllegalStateException(
                    "Pipeline already running or completed (status=" + module.getStatus() + ")");
        }
        module.setStatus(ModuleStatus.PENDING_RUN);
        moduleRepository.save(module);

        // Delegate to async orchestrator
        agentOrchestrator.runAgentPipeline(moduleId);
        log.info("Agent run triggered for module: {}", moduleId);
    }

    @Transactional
    public void delete(UUID id) {
        moduleRepository.deleteById(id);
        log.info("Module deleted: {}", id);
    }

    /**
     * Stops all running agents and resets all runs to PENDING state,
     * allowing a complete re-run of the pipeline from the beginning.
     */
    @Transactional
    public void stopAndResetPipeline(UUID moduleId) {
        List<AgentRun> allRuns = agentRunRepository.findByModuleIdOrderByRunOrderAsc(moduleId);
        Module module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));
        log.info("Stopping and resetting pipeline for module {}, found {} agent runs", moduleId, allRuns.size());

        // 1. Terminate all RUNNING agents
        for (AgentRun run : allRuns) {
            if (run.getStatus() == AgentRunStatus.RUNNING) {
                run.setStatus(AgentRunStatus.TERMINATED);
                run.setErrorMessage("Manually stopped by user - reset for re-run");
                agentRunRepository.save(run);
                log.info("Marked run {} as TERMINATED for agent {}", run.getId(), run.getAgentName());
            }
        }

        // 2. Reset ALL runs to PENDING (force complete re-run)
        for (AgentRun run : allRuns) {
            run.setStatus(AgentRunStatus.PENDING);
            run.setStartedAt(null);
            run.setCompletedAt(null);
            run.setDurationSeconds(null);
            run.setTokensUsed(0);
            run.setRetryCount(0);
            run.setErrorMessage(null);
            agentRunRepository.save(run);
            log.info("Reset run {} to PENDING for agent {}", run.getId(), run.getAgentName());
        }

        // 3. Clean up all reports for this module
        agentReportRepository.deleteByRunIdIn(allRuns.stream().map(AgentRun::getId).toList());
        log.info("Deleted all reports for module {}", moduleId);

        // 4. Update module status
        module.setStatus(ModuleStatus.PENDING_RUN);
        module.setCurrentAgent(null);
        moduleRepository.save(module);

        // 5. Dispatch first agent
        try {
            log.info("Triggering pipeline restart for module {}", moduleId);
            agentOrchestrator.runAgentPipeline(moduleId);
            log.info("Pipeline reset and restarted for module {}", moduleId);
        } catch (Exception e) {
            log.error("Failed to trigger pipeline restart for module {}: {}", moduleId, e.getMessage(), e);
            throw e;
        }
    }
}

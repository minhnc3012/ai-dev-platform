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
            throw new IllegalStateException("Module has no requirement set");
        }
        if (module.getProject().getAiConfig() == null) {
            throw new IllegalStateException("Project has no AI configuration");
        }
        if (module.getWorkflowId() == null) {
            throw new IllegalStateException("No workflow assigned — assign a workflow before running");
        }
        if (module.getStatus() != ModuleStatus.DRAFT && module.getStatus() != ModuleStatus.FAILED) {
            throw new IllegalStateException(
                    "Pipeline already running or completed (status=" + module.getStatus() + ")");
        }
        module.setStatus(ModuleStatus.PENDING_RUN);
        moduleRepository.save(module);
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

        // 1. Delete all existing runs and reports (workflow path creates fresh runs on restart)
        agentReportRepository.deleteByRunIdIn(allRuns.stream().map(AgentRun::getId).toList());
        agentRunRepository.deleteAll(allRuns);
        log.info("Deleted {} runs and all reports for module {}", allRuns.size(), moduleId);

        // 2. Reset module to DRAFT so runAgentPipeline passes the status guard
        module.setStatus(ModuleStatus.DRAFT);
        module.setCurrentAgent(null);
        moduleRepository.save(module);

        // 3. Start fresh pipeline
        try {
            agentOrchestrator.runAgentPipeline(moduleId);
            log.info("Pipeline reset and restarted for module {}", moduleId);
        } catch (Exception e) {
            log.error("Failed to restart pipeline for module {}: {}", moduleId, e.getMessage(), e);
            throw e;
        }
    }
}

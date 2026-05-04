package com.aidevplatform.service;

import com.aidevplatform.domain.entity.WorkflowDefinition;
import com.aidevplatform.domain.model.WorkflowStage;
import com.aidevplatform.repository.WorkflowDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;

    @Transactional(readOnly = true)
    public WorkflowDefinition getById(UUID id) {
        return workflowDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Workflow not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<WorkflowDefinition> listByProject(UUID projectId) {
        return workflowDefinitionRepository.findByProjectId(projectId);
    }

    @Transactional
    public WorkflowDefinition save(WorkflowDefinition workflow) {
        return workflowDefinitionRepository.save(workflow);
    }

    @Transactional
    public void delete(UUID id) {
        workflowDefinitionRepository.deleteById(id);
    }

    /**
     * Given the workflow stage tree, completed stage IDs, and running stage IDs,
     * returns the next set of stages that should be dispatched.
     *
     * - Iterates top-level stages sequentially
     * - Skips fully completed stages
     * - For parallel groups: returns all non-started, non-running children simultaneously
     * - For sequential groups: recurses into children
     * - Returns empty list when waiting for running stages to finish
     */
    public List<WorkflowStage> getNextStagesToRun(
            List<WorkflowStage> stages,
            Set<String> completedStageIds,
            Set<String> runningStageIds) {

        if (stages == null || stages.isEmpty()) return List.of();

        for (WorkflowStage stage : stages) {
            if (isStageFullyCompleted(stage, completedStageIds)) {
                continue;
            }
            if (hasRunningDescendant(stage, runningStageIds)) {
                return List.of(); // wait for in-progress work
            }
            return switch (stage.getType()) {
                case "agent" -> List.of(stage);
                case "sequential" -> getNextStagesToRun(
                        stage.getChildren(), completedStageIds, runningStageIds);
                case "parallel" -> stage.getChildren() == null ? List.of() :
                        stage.getChildren().stream()
                                .filter(c -> !isStageFullyCompleted(c, completedStageIds))
                                .filter(c -> !runningStageIds.contains(c.getId()))
                                .toList();
                default -> List.of();
            };
        }
        return List.of();
    }

    public boolean isWorkflowComplete(List<WorkflowStage> stages, Set<String> completedStageIds) {
        if (stages == null || stages.isEmpty()) return true;
        return stages.stream().allMatch(s -> isStageFullyCompleted(s, completedStageIds));
    }

    public boolean isStageFullyCompleted(WorkflowStage stage, Set<String> completedStageIds) {
        if ("agent".equals(stage.getType())) {
            return completedStageIds.contains(stage.getId());
        }
        List<WorkflowStage> children = stage.getChildren();
        if (children == null || children.isEmpty()) return true;
        return children.stream().allMatch(c -> isStageFullyCompleted(c, completedStageIds));
    }

    private boolean hasRunningDescendant(WorkflowStage stage, Set<String> runningStageIds) {
        if ("agent".equals(stage.getType())) {
            return runningStageIds.contains(stage.getId());
        }
        List<WorkflowStage> children = stage.getChildren();
        if (children == null) return false;
        return children.stream().anyMatch(c -> hasRunningDescendant(c, runningStageIds));
    }

    public Optional<WorkflowStage> findStageById(List<WorkflowStage> stages, String stageId) {
        if (stages == null || stageId == null) return Optional.empty();
        for (WorkflowStage stage : stages) {
            if (stageId.equals(stage.getId())) return Optional.of(stage);
            Optional<WorkflowStage> found = findStageById(stage.getChildren(), stageId);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
}

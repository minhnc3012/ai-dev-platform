package com.aidevplatform.api;

import com.aidevplatform.api.dto.WorkflowDefinitionDto;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.entity.WorkflowDefinition;
import com.aidevplatform.repository.ModuleRepository;
import com.aidevplatform.repository.ProjectRepository;
import com.aidevplatform.service.WorkflowService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ProjectRepository projectRepository;
    private final ModuleRepository moduleRepository;

    @GetMapping("/projects/{projectId}/workflows")
    public List<WorkflowDefinitionDto> listByProject(@PathVariable UUID projectId) {
        return workflowService.listByProject(projectId).stream().map(this::toDto).toList();
    }

    @GetMapping("/workflows/{id}")
    public WorkflowDefinitionDto get(@PathVariable UUID id) {
        return toDto(workflowService.getById(id));
    }

    @PostMapping("/projects/{projectId}/workflows")
    public WorkflowDefinitionDto create(@PathVariable UUID projectId,
                                         @RequestBody WorkflowDefinitionDto dto) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));
        WorkflowDefinition entity = WorkflowDefinition.builder()
                .project(project)
                .name(dto.getName())
                .description(dto.getDescription())
                .isTemplate(dto.getIsTemplate() != null ? dto.getIsTemplate() : false)
                .defaultPauseForReview(dto.getDefaultPauseForReview() != null ? dto.getDefaultPauseForReview() : true)
                .stages(dto.getStages())
                .build();
        return toDto(workflowService.save(entity));
    }

    @PutMapping("/workflows/{id}")
    public WorkflowDefinitionDto update(@PathVariable UUID id,
                                         @RequestBody WorkflowDefinitionDto dto) {
        WorkflowDefinition entity = workflowService.getById(id);
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        if (dto.getIsTemplate() != null) entity.setIsTemplate(dto.getIsTemplate());
        if (dto.getDefaultPauseForReview() != null) entity.setDefaultPauseForReview(dto.getDefaultPauseForReview());
        if (dto.getStages() != null) entity.setStages(dto.getStages());
        return toDto(workflowService.save(entity));
    }

    @DeleteMapping("/workflows/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/modules/{moduleId}/assign-workflow/{workflowId}")
    public ResponseEntity<Void> assignWorkflow(@PathVariable UUID moduleId,
                                                @PathVariable UUID workflowId) {
        var module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));
        module.setWorkflowId(workflowId);
        moduleRepository.save(module);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/modules/{moduleId}/workflow")
    public ResponseEntity<Void> unassignWorkflow(@PathVariable UUID moduleId) {
        var module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));
        module.setWorkflowId(null);
        moduleRepository.save(module);
        return ResponseEntity.ok().build();
    }

    private WorkflowDefinitionDto toDto(WorkflowDefinition e) {
        return WorkflowDefinitionDto.builder()
                .id(e.getId())
                .projectId(e.getProject() != null ? e.getProject().getId() : null)
                .name(e.getName())
                .description(e.getDescription())
                .isTemplate(e.getIsTemplate())
                .defaultPauseForReview(e.getDefaultPauseForReview())
                .stages(e.getStages())
                .build();
    }
}

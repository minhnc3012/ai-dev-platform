package com.aidevplatform.api;

import com.aidevplatform.api.dto.AgentTemplateDto;
import com.aidevplatform.domain.entity.AgentTemplate;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.repository.AgentTemplateRepository;
import com.aidevplatform.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/agent-templates")
@RequiredArgsConstructor
public class AgentTemplateController {

    private final AgentTemplateRepository agentTemplateRepository;
    private final ProjectRepository projectRepository;

    @GetMapping
    public List<AgentTemplateDto> list(@PathVariable UUID projectId) {
        return agentTemplateRepository.findByProjectId(projectId).stream()
                .map(this::toDto).toList();
    }

    @PostMapping
    public AgentTemplateDto create(@PathVariable UUID projectId, @RequestBody AgentTemplateDto dto) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));
        AgentTemplate entity = AgentTemplate.builder()
                .project(project)
                .name(dto.getName())
                .agentKey(dto.getAgentKey() != null ? dto.getAgentKey() : dto.getName().toLowerCase().replace(" ", "_"))
                .role(dto.getRole())
                .goal(dto.getGoal())
                .backstoryTemplate(dto.getBackstoryTemplate())
                .taskDescriptionTemplate(dto.getTaskDescriptionTemplate())
                .llmConfig(dto.getLlmConfig())
                .build();
        return toDto(agentTemplateRepository.save(entity));
    }

    @PutMapping("/{id}")
    public AgentTemplateDto update(@PathVariable UUID projectId, @PathVariable UUID id,
                                    @RequestBody AgentTemplateDto dto) {
        AgentTemplate entity = agentTemplateRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("AgentTemplate not found: " + id));
        entity.setName(dto.getName());
        if (dto.getAgentKey() != null) entity.setAgentKey(dto.getAgentKey());
        entity.setRole(dto.getRole());
        entity.setGoal(dto.getGoal());
        entity.setBackstoryTemplate(dto.getBackstoryTemplate());
        entity.setTaskDescriptionTemplate(dto.getTaskDescriptionTemplate());
        if (dto.getLlmConfig() != null) entity.setLlmConfig(dto.getLlmConfig());
        return toDto(agentTemplateRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id) {
        agentTemplateRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private AgentTemplateDto toDto(AgentTemplate e) {
        return AgentTemplateDto.builder()
                .id(e.getId())
                .projectId(e.getProject() != null ? e.getProject().getId() : null)
                .name(e.getName())
                .agentKey(e.getAgentKey())
                .role(e.getRole())
                .goal(e.getGoal())
                .backstoryTemplate(e.getBackstoryTemplate())
                .taskDescriptionTemplate(e.getTaskDescriptionTemplate())
                .llmConfig(e.getLlmConfig())
                .build();
    }
}

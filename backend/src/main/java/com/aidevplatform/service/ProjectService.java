package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AiConfig;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.entity.User;
import com.aidevplatform.domain.enums.ProjectStatus;
import com.aidevplatform.repository.ProjectRepository;
import com.aidevplatform.repository.UserRepository;
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
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public List<Project> findAllByOwner(UUID ownerId) {
        return projectRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public List<Project> findActiveByOwner(UUID ownerId) {
        return projectRepository.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, ProjectStatus.ACTIVE);
    }

    public Project findById(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + id));
    }

    public Project findByIdWithAiConfig(UUID id) {
        return projectRepository.findByIdWithAiConfig(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + id));
    }

    @Transactional
    public Project create(String name, String description, String workspacePath, UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + ownerId));

        // Create default AI config for the project
        AiConfig defaultConfig = new AiConfig();

        Project project = Project.builder()
                .name(name)
                .description(description)
                .workspacePath(workspacePath != null && !workspacePath.isBlank() ? workspacePath : null)
                .owner(owner)
                .aiConfig(defaultConfig)
                .status(ProjectStatus.ACTIVE)
                .build();

        Project saved = projectRepository.save(project);
        log.info("Project created: id={}, name={}", saved.getId(), name);
        return saved;
    }

    @Transactional
    public Project update(UUID id, String name, String description, String gitRepoUrl, String workspacePath) {
        Project project = findById(id);
        project.setName(name);
        project.setDescription(description);
        project.setGitRepoUrl(gitRepoUrl);
        project.setWorkspacePath(workspacePath != null && !workspacePath.isBlank() ? workspacePath : null);
        return projectRepository.save(project);
    }

    @Transactional
    public void updateAiConfig(UUID projectId, AiConfig config) {
        Project project = findByIdWithAiConfig(projectId);
        AiConfig existing = project.getAiConfig();
        if (existing == null) {
            project.setAiConfig(config);
        } else {
            existing.setLlmProvider(config.getLlmProvider());
            existing.setInvocationMode(config.getInvocationMode());
            existing.setLlmBaseUrl(config.getLlmBaseUrl());
            existing.setLlmModelName(config.getLlmModelName());
            existing.setLlmApiKey(config.getLlmApiKey());
            existing.setLlmCliCommand(config.getLlmCliCommand());
            existing.setTemperature(config.getTemperature());
            existing.setMaxTokensPerTask(config.getMaxTokensPerTask());
            existing.setOutputLanguage(config.getOutputLanguage());
            existing.setApprovalRequired(config.getApprovalRequired());
            existing.setLlmCliSettingsFile(config.getLlmCliSettingsFile());
            existing.setTechStack(config.getTechStack());
            existing.setCodingStyleGuide(config.getCodingStyleGuide());
        }
        projectRepository.save(project);
        log.info("AI config updated for project: {}", projectId);
    }

    @Transactional
    public void archive(UUID id) {
        Project project = findById(id);
        project.setStatus(ProjectStatus.ARCHIVED);
        projectRepository.save(project);
        log.info("Project archived: {}", id);
    }
}

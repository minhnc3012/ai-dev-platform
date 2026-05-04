package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    List<WorkflowDefinition> findByProjectId(UUID projectId);
    List<WorkflowDefinition> findByIsTemplateTrue();
}

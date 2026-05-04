package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.AgentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentTemplateRepository extends JpaRepository<AgentTemplate, UUID> {
    List<AgentTemplate> findByProjectId(UUID projectId);
    List<AgentTemplate> findByProjectIdIsNull();
}

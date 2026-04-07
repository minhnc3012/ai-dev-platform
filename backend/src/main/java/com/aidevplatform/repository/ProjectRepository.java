package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.enums.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<Project> findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID ownerId, ProjectStatus status);

    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.aiConfig WHERE p.id = :id")
    java.util.Optional<Project> findByIdWithAiConfig(UUID id);
}

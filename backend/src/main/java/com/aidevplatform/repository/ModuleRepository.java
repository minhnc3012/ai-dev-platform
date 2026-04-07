package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.enums.ModuleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModuleRepository extends JpaRepository<Module, UUID> {

    List<Module> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<Module> findByProjectIdAndStatusOrderByCreatedAtDesc(UUID projectId, ModuleStatus status);

    /**
     * Fetch a module with its project, AI config, and owner eagerly loaded in one query.
     * Use this when accessing those relations outside a transaction (e.g., Vaadin UI layer).
     */
    @Query("""
            SELECT m FROM Module m
            LEFT JOIN FETCH m.project p
            LEFT JOIN FETCH p.aiConfig
            LEFT JOIN FETCH p.owner
            WHERE m.id = :id
            """)
    Optional<Module> findByIdWithDetails(@Param("id") UUID id);
}

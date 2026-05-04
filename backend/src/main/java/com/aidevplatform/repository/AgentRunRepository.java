package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.enums.AgentRunStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    List<AgentRun> findByModuleIdOrderByRunOrderAsc(UUID moduleId);

    List<AgentRun> findByModuleIdAndStatusOrderByRunOrder(UUID moduleId, AgentRunStatus status);

    List<AgentRun> findByModuleIdAndStatusIn(UUID moduleId, List<AgentRunStatus> statuses);

    /** Find all runs globally with a given status — used for crash recovery on startup. */
    List<AgentRun> findByStatus(AgentRunStatus status);

    /**
     * Find RUNNING runs that started before {@code threshold} and have not yet completed.
     * Used by the health checker to detect stuck runs (agent died without reporting back).
     */
    @Query("""
            SELECT r FROM AgentRun r
            JOIN FETCH r.module m
            JOIN FETCH m.project p
            JOIN FETCH p.owner
            JOIN FETCH p.aiConfig
            WHERE r.status = com.aidevplatform.domain.enums.AgentRunStatus.RUNNING
              AND r.startedAt < :threshold
            """)
    List<AgentRun> findStuckRuns(@Param("threshold") LocalDateTime threshold);

    java.util.Optional<AgentRun> findByModuleIdAndStageId(UUID moduleId, String stageId);

    @Query("SELECT COALESCE(MAX(r.runOrder), -1) FROM AgentRun r WHERE r.module.id = :moduleId")
    int findMaxRunOrderByModuleId(@Param("moduleId") UUID moduleId);

    /**
     * Find all runs for a module with all relationships eagerly loaded.
     * Used by the monitor view to avoid LazyInitializationException.
     */
    @EntityGraph(attributePaths = {"events", "report"})
    List<AgentRun> findAllByModuleIdOrderByRunOrderAsc(@Param("moduleId") UUID moduleId);

    /**
     * Find runs with report and the report's run eagerly loaded.
     * This creates a graph where report.run points back to the original run.
     */
    @EntityGraph(attributePaths = {"events", "report"})
    @Query("""
            SELECT DISTINCT r FROM AgentRun r
            JOIN FETCH r.module m
            WHERE r.module.id = :moduleId
            ORDER BY r.runOrder ASC
            """)
    List<AgentRun> findAllByModuleIdWithReportAndRun(@Param("moduleId") UUID moduleId);
}

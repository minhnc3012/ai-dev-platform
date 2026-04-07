package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.AgentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {

    List<AgentEvent> findByRunIdOrderByCreatedAtAsc(UUID runId);

    /**
     * Fetch events with their AgentRun eagerly joined, so callers outside a
     * transaction can access run.getAgentName() etc. without a LazyInitializationException.
     */
    @Query("""
            SELECT e FROM AgentEvent e
            JOIN FETCH e.run
            WHERE e.run.id IN :runIds
            ORDER BY e.createdAt ASC
            """)
    List<AgentEvent> findByRunIdInWithRunOrderByCreatedAtAsc(@Param("runIds") List<UUID> runIds);
}

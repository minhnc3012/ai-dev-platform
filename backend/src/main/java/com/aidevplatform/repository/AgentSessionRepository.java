package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    Optional<AgentSession> findByRunId(UUID runId);

    List<AgentSession> findByModuleIdOrderByLastMessageAtDesc(UUID moduleId);

    List<AgentSession> findByStatusAndLastMessageAtBefore(
            AgentSession.SessionStatus status, LocalDateTime threshold
    );
}

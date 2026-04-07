package com.aidevplatform.repository;

import com.aidevplatform.domain.entity.AgentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentReportRepository extends JpaRepository<AgentReport, UUID> {

    Optional<AgentReport> findByRunId(UUID runId);

    List<AgentReport> findByRunIdIn(List<UUID> runIds);

    void deleteByRunIdIn(List<UUID> runIds);
}

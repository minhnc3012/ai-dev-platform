package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.repository.AgentReportRepository;
import com.aidevplatform.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final AgentReportRepository reportRepository;
    private final AgentRunRepository runRepository;

    public Optional<AgentReport> findByRunId(UUID runId) {
        return reportRepository.findByRunId(runId);
    }

    public List<AgentReport> findAllForModule(UUID moduleId) {
        List<AgentRun> runs = runRepository.findByModuleIdOrderByRunOrderAsc(moduleId);
        List<UUID> runIds = runs.stream().map(AgentRun::getId).toList();
        return reportRepository.findByRunIdIn(runIds);
    }

    public List<AgentRun> findRunsForModule(UUID moduleId) {
        return runRepository.findAllByModuleIdWithReportAndRun(moduleId);
    }
}

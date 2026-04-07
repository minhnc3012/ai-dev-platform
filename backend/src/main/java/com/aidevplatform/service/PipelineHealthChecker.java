package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodic health check that detects and resumes stalled agent pipeline runs.
 *
 * A run is considered "stuck" if it has been in RUNNING status for longer than
 * STUCK_THRESHOLD_MINUTES without completing. This can happen when:
 *  - The Python agent service crashed between receiving a task and posting the callback
 *  - A network partition prevented the completion callback from reaching the backend
 *  - The Redis message was lost before the agent service could consume it
 *
 * On each tick the checker re-dispatches stuck runs so the pipeline continues
 * without manual intervention.
 *
 * Additionally, on application startup, immediately checks for stuck runs to
 * restore UI state when backend restarts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineHealthChecker {

    /** How many minutes a run may stay RUNNING before it is considered stuck. */
    private static final int STUCK_THRESHOLD_MINUTES = 2;

    private final AgentRunRepository agentRunRepository;
    private final AgentOrchestrator agentOrchestrator;

    /**
     * Triggered immediately when Spring Boot application is fully ready.
     * Checks for any stuck runs and re-dispatches them to restore UI state
     * after backend restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("PipelineHealthChecker initialized - checking for stuck runs on startup");
        checkStuckRuns();
    }

    /**
     * Runs every 5 minutes. Finds all RUNNING runs whose startedAt is older than
     * {@link #STUCK_THRESHOLD_MINUTES} and triggers a resume for each affected module.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void checkStuckRuns() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
        List<AgentRun> stuck = agentRunRepository.findStuckRuns(threshold);

        if (stuck.isEmpty()) {
            return;
        }

        log.warn("Health check: found {} stuck RUNNING run(s) older than {} minutes",
                stuck.size(), STUCK_THRESHOLD_MINUTES);

        for (AgentRun run : stuck) {
            try {
                String result = agentOrchestrator.resumePipeline(run.getModule().getId());
                log.info("Health check auto-resumed: runId={}, agent={}, result={}",
                        run.getId(), run.getAgentName(), result);
            } catch (Exception e) {
                log.error("Health check failed to resume runId={}, agent={}: {}",
                        run.getId(), run.getAgentName(), e.getMessage(), e);
            }
        }
    }
}

package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentSignal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AgentSignalProducer.
 * Tests signal creation (Redis stream integration requires running Redis instance).
 */
public class AgentSignalProducerTest {

    @Test
    void testCreateApproveSignal() {
        UUID runId = UUID.randomUUID();
        UUID moduleId = UUID.randomUUID();
        String agentName = "pm";

        AgentSignal signal = AgentSignal.createApproveSignal(
                new AgentSignal.AgentRunInfo(runId, moduleId, agentName)
        );

        assertThat(signal.getSignalId()).isNotNull();
        assertThat(signal.getSignalType()).isEqualTo(AgentSignal.SignalType.APPROVE);
        assertThat(signal.getRunId()).isEqualTo(runId);
        assertThat(signal.getModuleId()).isEqualTo(moduleId);
        assertThat(signal.getAgentName()).isEqualTo(agentName);
        assertThat(signal.getTimestamp()).isNotNull();
    }

    @Test
    void testCreateRejectSignal() {
        UUID runId = UUID.randomUUID();
        UUID moduleId = UUID.randomUUID();
        String agentName = "architect";
        String reason = "Requirements unclear";

        AgentSignal signal = AgentSignal.createRejectSignal(
                new AgentSignal.AgentRunInfo(runId, moduleId, agentName),
                reason
        );

        assertThat(signal.getSignalType()).isEqualTo(AgentSignal.SignalType.REJECT);
        assertThat(signal.getRunId()).isEqualTo(runId);
        assertThat(signal.getModuleId()).isEqualTo(moduleId);
        assertThat(signal.getAgentName()).isEqualTo(agentName);
        assertThat(signal.getData()).containsEntry("reason", reason);
    }

    @Test
    void testCreateCompleteSignal() {
        UUID runId = UUID.randomUUID();
        UUID moduleId = UUID.randomUUID();
        String agentName = "dev";

        AgentSignal signal = AgentSignal.createCompleteSignal(
                new AgentSignal.AgentRunInfo(runId, moduleId, agentName)
        );

        assertThat(signal.getSignalType()).isEqualTo(AgentSignal.SignalType.COMPLETE);
        assertThat(signal.getRunId()).isEqualTo(runId);
        assertThat(signal.getModuleId()).isEqualTo(moduleId);
        assertThat(signal.getAgentName()).isEqualTo(agentName);
    }

    @Test
    void testCreateResumeSignal() {
        UUID moduleId = UUID.randomUUID();
        String reason = "Pipeline restart";

        AgentSignal signal = AgentSignal.createResumeSignal(moduleId, reason);

        assertThat(signal.getSignalType()).isEqualTo(AgentSignal.SignalType.RESUME);
        assertThat(signal.getModuleId()).isEqualTo(moduleId);
        assertThat(signal.getData()).containsEntry("reason", reason);
    }
}

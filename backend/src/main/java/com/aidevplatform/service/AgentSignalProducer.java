package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Producer for agent signals published to Redis Streams.
 *
 * This service is responsible for publishing signals to the Redis Stream
 * that other services (like the Backend Service) can consume.
 *
 * Signal persistence:
 * - Messages are stored in Redis Stream until consumed
 * - Each message has a unique ID
 * - Messages can be truncated by MAXLEN to prevent unbounded growth
 *
 * Signal flow:
 * 1. Agent Service or Backend generates signal
 * 2. Signal published to Redis Stream 'agent:signals'
 * 3. Messages persist until consumed by AgentSignalConsumer
 * 4. Consumer acknowledges processing with XACK
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentSignalProducer {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SIGNAL_STREAM_NAME = "agent:signals";

    /**
     * Maximum number of entries to keep in the stream.
     * Old entries beyond this count will be automatically trimmed.
     */
    private static final long MAX_STREAM_ENTRIES = 10000;

    /**
     * Publish an APPROVE signal to the Redis Stream.
     *
     * @param runId The AgentRun UUID as String
     * @param moduleId The Module UUID as String
     * @param agentName The agent name
     */
    public void publishApproveSignal(String runId, String moduleId, String agentName) {
        AgentSignal signal = AgentSignal.createApproveSignal(
                new AgentSignal.AgentRunInfo(
                        java.util.UUID.fromString(runId),
                        java.util.UUID.fromString(moduleId),
                        agentName
                )
        );
        publishSignal(signal);
    }

    /**
     * Publish a REJECT signal to the Redis Stream.
     *
     * @param runId The AgentRun UUID as String
     * @param moduleId The Module UUID as String
     * @param agentName The agent name
     * @param reason The rejection reason
     */
    public void publishRejectSignal(String runId, String moduleId, String agentName, String reason) {
        AgentSignal signal = AgentSignal.createRejectSignal(
                new AgentSignal.AgentRunInfo(
                        java.util.UUID.fromString(runId),
                        java.util.UUID.fromString(moduleId),
                        agentName
                ),
                reason
        );
        publishSignal(signal);
    }

    /**
     * Publish a COMPLETE signal to the Redis Stream (auto-trigger case).
     *
     * @param runId The AgentRun UUID as String
     * @param moduleId The Module UUID as String
     * @param agentName The agent name
     */
    public void publishCompleteSignal(String runId, String moduleId, String agentName) {
        AgentSignal signal = AgentSignal.createCompleteSignal(
                new AgentSignal.AgentRunInfo(
                        java.util.UUID.fromString(runId),
                        java.util.UUID.fromString(moduleId),
                        agentName
                )
        );
        publishSignal(signal);
    }

    /**
     * Publish a RESUME signal to the Redis Stream for pipeline recovery.
     *
     * @param moduleId The Module UUID as String
     * @param reason The reason for resume
     */
    public void publishResumeSignal(String moduleId, String reason) {
        AgentSignal signal = AgentSignal.createResumeSignal(
                java.util.UUID.fromString(moduleId),
                reason
        );
        publishSignal(signal);
    }

    /**
     * Publish an arbitrary signal to the Redis Stream.
     *
     * @param signal The signal to publish
     */
    public void publishSignal(AgentSignal signal) {
        try {
            // Convert signal to Map for Redis storage
            Map<String, Object> signalData = Map.of(
                "signal_id", signal.getSignalId().toString(),
                "signal_type", signal.getSignalType().name(),
                "run_id", signal.getRunId() != null ? signal.getRunId().toString() : null,
                "module_id", signal.getModuleId() != null ? signal.getModuleId().toString() : null,
                "agent_name", signal.getAgentName(),
                "timestamp", signal.getTimestamp() != null ? signal.getTimestamp().toString() : null,
                "data", signal.getData() != null ? signal.getData() : Map.of()
            );

            // Use StreamOperations to add to stream
            // In Spring Data Redis 3.x, use opsForStream().add() with MapRecord
            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();

            // Convert Map<String, Object> to Map<String, String>
            Map<String, String> stringData = convertToStringMap(signalData);

            MapRecord<String, String, String> record = MapRecord.create(
                SIGNAL_STREAM_NAME,
                stringData
            );

            RecordId recordId = streamOps.add(record);

            log.debug("Published signal: type={}, runId={}, moduleId={}, signalId={}, recordId={}",
                    signal.getSignalType(), signal.getRunId(), signal.getModuleId(), signal.getSignalId(), recordId);

        } catch (Exception e) {
            log.error("Failed to publish signal to Redis Stream: type={}, error={}",
                    signal.getSignalType(), e.getMessage(), e);
        }
    }

    /**
     * Convert Map<String, Object> to Map<String, String>.
     */
    private Map<String, String> convertToStringMap(Map<String, Object> data) {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
        }
        return result;
    }

    /**
     * Get the count of unread messages in the stream.
     * This can be used for monitoring.
     */
    public long getStreamLength() {
        try {
            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            Long length = streamOps.size(SIGNAL_STREAM_NAME);
            return length != null ? length : 0;
        } catch (Exception e) {
            log.warn("Failed to get stream length: {}", e.getMessage());
            return 0;
        }
    }
}

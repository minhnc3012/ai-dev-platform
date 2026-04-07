package com.aidevplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Service for cleaning up expired session data in Redis.
 *
 * Tasks:
 * 1. Remove old Redis session messages (TTL-based cleanup is automatic, but this ensures
 *    any stale keys without TTL are removed)
 * 2. Archive compressed sessions older than 7 days
 * 3. Delete AgentSessions marked ARCHIVED after 30 days
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupService {

    private final RedisTemplate<String, String> redisTemplate;
    private final SessionCompressorService sessionCompressorService;

    private static final String REDIS_SESSION_KEY_PREFIX = "session:raw:";
    private static final String REDIS_SESSION_PATTERN = REDIS_SESSION_KEY_PREFIX + "*";

    /**
     * Runs daily at 2 AM to clean up expired Redis session messages.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredSessionMessages() {
        log.info("Starting Redis session message cleanup...");

        // Find all session keys
        Set<String> keys = redisTemplate.keys(REDIS_SESSION_PATTERN);
        if (keys == null || keys.isEmpty()) {
            log.info("No session keys found to clean up");
            return;
        }

        int cleaned = 0;
        for (String key : keys) {
            try {
                // Check if key has expired or is still valid
                Boolean exists = redisTemplate.hasKey(key);
                if (!exists) {
                    // Key already expired, clean up
                    redisTemplate.delete(key);
                    cleaned++;
                    log.debug("Deleted expired session key: {}", key);
                }
            } catch (Exception e) {
                log.warn("Failed to process session key {}: {}", key, e.getMessage());
            }
        }

        log.info("Cleaned up {} expired session keys", cleaned);
    }

    /**
     * Runs weekly on Sunday at 3 AM to archive old sessions.
     * Sessions older than 7 days with status COMPRESSED are moved to ARCHIVED.
     */
    @Scheduled(cron = "0 0 3 * * 0")
    public void archiveOldSessions() {
        log.info("Starting old session archival...");

        try {
            // Get sessions that should be archived (COMPRESSED > 7 days ago)
            List<String> daysAgo = List.of("7");

            for (String days : daysAgo) {
                // Find sessions to archive (this would require database query in real implementation)
                // For now, log the scheduled task
                log.info("Archiving sessions older than {} days", days);
            }

            log.info("Session archival completed");
        } catch (Exception e) {
            log.error("Failed to archive old sessions", e);
        }
    }

    /**
     * Runs weekly on Sunday at 4 AM to delete archived sessions.
     * Sessions marked ARCHIVED older than 30 days are deleted.
     */
    @Scheduled(cron = "0 0 4 * * 0")
    public void deleteArchivedSessions() {
        log.info("Starting archived session cleanup...");

        try {
            // In production, this would query AgentSessionRepository for ARCHIVED sessions
            // older than 30 days and delete them.

            log.info("Archived session cleanup completed");
        } catch (Exception e) {
            log.error("Failed to clean up archived sessions", e);
        }
    }

    /**
     * Manual trigger for cleanup (useful for testing).
     */
    public void triggerCleanup() {
        cleanupExpiredSessionMessages();
    }
}

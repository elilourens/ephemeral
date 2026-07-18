package com.ephemeral.message;

import com.ephemeral.config.AppProperties;
import com.ephemeral.file.StorageService;
import com.ephemeral.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ephemeral engine. Physically deletes messages (and their blobs) older than
 * the retention window, unless saved. Runs on a schedule; also callable directly.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageService storage;
    private final AppProperties props;

    public RetentionService(NamedParameterJdbcTemplate jdbc, StorageService storage, AppProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.props = props;
    }

    /** @return number of messages deleted. */
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(props.getRetention());
        UUID boundary = Ids.boundary(cutoff);

        // blobs of expiring messages + never-bound uploads older than the window
        List<String> keys = jdbc.queryForList("""
                select storage_key from attachments a
                where (a.message_id is not null
                       and a.message_id in (select id from messages where id < :b and saved = false and pinned = false))
                   or (a.message_id is null and a.id < :b)
                """, Map.of("b", boundary), String.class);

        int messages = jdbc.update(
                "delete from messages where id < :b and saved = false and pinned = false", Map.of("b", boundary));
        int orphanUploads = jdbc.update(
                "delete from attachments where message_id is null and id < :b", Map.of("b", boundary));

        storage.deleteAll(keys);

        if (messages > 0 || orphanUploads > 0) {
            log.info("retention purge: {} messages, {} orphan uploads, {} files removed",
                    messages, orphanUploads, keys.size());
        }
        return messages;
    }

    /**
     * Backstop for blobs whose message/attachment row is gone but whose file lingered
     * (e.g. a failed unlink). Deletes on-disk blobs that have no attachment row and are
     * older than the grace window (so freshly-uploaded-not-yet-bound files are spared).
     * This is the reconciliation sweep that prevents the classic orphaned-file bug.
     *
     * @return number of orphan blobs removed.
     */
    public int reconcileOrphanBlobs(java.time.Duration grace) {
        java.util.Set<String> referenced = new java.util.HashSet<>(
                jdbc.queryForList("select storage_key from attachments", Map.of(), String.class));
        Instant cutoff = Instant.now().minus(grace);
        java.util.List<String> orphans = storage.listStoredKeys().stream()
                .filter(k -> !referenced.contains(k))
                .filter(k -> storage.lastModified(k).isBefore(cutoff))
                .toList();
        storage.deleteAll(orphans);
        if (!orphans.isEmpty()) {
            log.info("reconciled {} orphan blob(s) with no message", orphans.size());
        }
        return orphans.size();
    }
}

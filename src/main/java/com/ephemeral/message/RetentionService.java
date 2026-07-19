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
        int messages = 0;
        // channels with a custom vanish timer purge on their own boundary
        for (Long ms : jdbc.queryForList(
                "select distinct retention_ms from channels where retention_ms is not null",
                Map.of(), Long.class)) {
            messages += purgeChannels("c.retention_ms = :ms",
                    Map.of("ms", ms, "b", Ids.boundary(Instant.now().minusMillis(ms))));
        }
        // everything else (incl. DMs) uses the instance default
        UUID boundary = Ids.boundary(Instant.now().minus(props.getRetention()));
        messages += purgeChannels("c.retention_ms is null", Map.of("b", boundary));

        // never-bound uploads age out on the default window — except attachments
        // still serving as a server icon
        String orphanCond = " message_id is null and id < :b"
                + " and id not in (select icon_id from guilds where icon_id is not null)"
                + " and id not in (select attachment_id from guild_emoji)"
                + " and id not in (select avatar_id from users where avatar_id is not null)"
                + " and id not in (select banner_id from users where banner_id is not null)";
        List<String> orphanKeys = jdbc.queryForList(
                "select storage_key from attachments where" + orphanCond,
                Map.of("b", boundary), String.class);
        int orphanUploads = jdbc.update(
                "delete from attachments where" + orphanCond, Map.of("b", boundary));
        storage.deleteAll(orphanKeys);

        // the admin audit log is itself ephemeral: 30 days
        jdbc.update("delete from audit_log where id < :b",
                Map.of("b", Ids.boundary(Instant.now().minus(java.time.Duration.ofDays(30)))));

        if (messages > 0 || orphanUploads > 0) {
            log.info("retention purge: {} messages, {} orphan uploads", messages, orphanUploads);
        }
        return messages;
    }

    /** Deletes expired, unsaved, unpinned messages in channels matching the condition (+ their blobs). */
    private int purgeChannels(String channelCond, Map<String, ?> params) {
        List<String> keys = jdbc.queryForList("""
                select a.storage_key from attachments a
                join messages m on m.id = a.message_id
                join channels c on c.id = m.channel_id
                where m.id < :b and m.saved = false and m.pinned = false
                """ + " and " + channelCond, params, String.class);
        int n = jdbc.update("""
                delete from messages m using channels c
                where c.id = m.channel_id and m.id < :b and m.saved = false and m.pinned = false
                """ + " and " + channelCond, params);
        storage.deleteAll(keys);
        return n;
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

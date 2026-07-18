package com.ephemeral.user;

import com.ephemeral.dto.UserProfileDto;
import com.ephemeral.file.StorageService;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PresenceService presence;
    private final StorageService storage;

    public UserService(NamedParameterJdbcTemplate jdbc, PresenceService presence, StorageService storage) {
        this.jdbc = jdbc;
        this.presence = presence;
        this.storage = storage;
    }

    public UserProfileDto getProfile(UUID id) {
        List<UserProfileDto> rows = jdbc.query("""
                select id, username, display_name, bio, status, custom_status
                from users where id = :id
                """, Map.of("id", id), (rs, i) -> new UserProfileDto(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getString("bio"), rs.getString("status"), rs.getString("custom_status"),
                Ids.timestampOf(rs.getObject("id", UUID.class))));
        if (rows.isEmpty()) {
            throw ApiException.notFound("user not found");
        }
        return rows.get(0);
    }

    public UserProfileDto updateProfile(UUID userId, String displayName, String bio,
                                        String status, String customStatus) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", userId);
        List<String> sets = new ArrayList<>();
        if (displayName != null && !displayName.isBlank()) {
            sets.add("display_name = :dn");
            p.addValue("dn", displayName.trim());
        }
        if (bio != null) {
            sets.add("bio = :bio");
            p.addValue("bio", bio.isBlank() ? null : bio.trim());
        }
        if (status != null) {
            if (!status.equals("online") && !status.equals("idle") && !status.equals("dnd")) {
                throw ApiException.badRequest("status must be online, idle, or dnd");
            }
            sets.add("status = :st");
            p.addValue("st", status);
        }
        if (customStatus != null) {
            sets.add("custom_status = :cs");
            p.addValue("cs", customStatus.isBlank() ? null : customStatus.trim());
        }
        if (!sets.isEmpty()) {
            jdbc.update("update users set " + String.join(", ", sets) + " where id = :id", p);
        }
        if (status != null || customStatus != null) {
            presence.statusChanged(userId);
        }
        return getProfile(userId);
    }

    /** Persisted per-user settings blob (raw JSON string; defaults to "{}"). */
    public String getSettings(UUID userId) {
        List<String> rows = jdbc.queryForList("select settings::text from users where id = :id",
                Map.of("id", userId), String.class);
        if (rows.isEmpty()) {
            throw ApiException.notFound("user not found");
        }
        return rows.get(0) == null ? "{}" : rows.get(0);
    }

    public void setSettings(UUID userId, String json) {
        jdbc.update("update users set settings = cast(:s as jsonb) where id = :id",
                new MapSqlParameterSource().addValue("s", json).addValue("id", userId));
    }

    /**
     * Permanently deletes the account and EVERYTHING the user ever posted: their
     * messages in every server (cascade via author_id), their uploads, saves,
     * reactions, mentions and read state, plus any servers they OWN (which cascade
     * their channels + all messages within). On-disk blobs are removed too.
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        // Collect blobs that will be orphaned: the user's own uploads (cascade via
        // owner_id) AND uploads by anyone inside servers the user owns (cascade via
        // the guild delete). Gather before deleting rows.
        List<String> keys = jdbc.queryForList("""
                select storage_key from attachments where owner_id = :u
                union
                select a.storage_key from attachments a
                    join messages m on m.id = a.message_id
                    join channels c on c.id = m.channel_id
                    join guilds g on g.id = c.guild_id
                where g.owner_id = :u
                """, Map.of("u", userId), String.class);
        // Owned servers first (owner_id has no cascade), then the user themself.
        jdbc.update("delete from guilds where owner_id = :u", Map.of("u", userId));
        jdbc.update("delete from users where id = :u", Map.of("u", userId));
        storage.deleteAll(keys);
        // Their WebSocket(s) drop when the client reloads after deletion, which
        // fires the normal presence-offline broadcast — nothing to do here.
    }
}

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
                select id, username, display_name, bio, status, custom_status,
                       avatar_id, banner_id, profile_embed
                from users where id = :id
                """, Map.of("id", id), (rs, i) -> new UserProfileDto(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getString("bio"), rs.getString("status"), rs.getString("custom_status"),
                rs.getObject("avatar_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("avatar_id", UUID.class),
                rs.getObject("banner_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("banner_id", UUID.class),
                rs.getString("profile_embed"),
                Ids.timestampOf(rs.getObject("id", UUID.class))));
        if (rows.isEmpty()) {
            throw ApiException.notFound("user not found");
        }
        return rows.get(0);
    }

    public UserProfileDto updateProfile(UUID userId, String displayName, String bio,
                                        String status, String customStatus,
                                        UUID avatarId, boolean clearAvatar,
                                        UUID bannerId, boolean clearBanner,
                                        String profileEmbed) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", userId);
        List<String> sets = new ArrayList<>();
        if (avatarId != null || clearAvatar) {
            if (avatarId != null) requireOwnImageUpload(userId, avatarId);
            sets.add("avatar_id = :av");
            p.addValue("av", clearAvatar ? null : avatarId);
        }
        if (bannerId != null || clearBanner) {
            if (bannerId != null) requireOwnImageUpload(userId, bannerId);
            sets.add("banner_id = :bn");
            p.addValue("bn", clearBanner ? null : bannerId);
        }
        if (profileEmbed != null) {
            String url = profileEmbed.trim();
            if (!url.isEmpty() && (url.length() > 300 || !url.matches("^https?://\\S+$"))) {
                throw ApiException.badRequest("the profile embed must be a http(s) link");
            }
            sets.add("profile_embed = :pe");
            p.addValue("pe", url.isEmpty() ? null : url);
        }
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

    /** Avatar/banner must be the caller's own fresh image upload, max 2 MB. */
    private void requireOwnImageUpload(UUID userId, UUID attachmentId) {
        var rows = jdbc.query("""
                select owner_id, message_id, content_type, size_bytes from attachments where id = :a
                """, Map.of("a", attachmentId), (rs, i) -> new Object[]{
                rs.getObject("owner_id", UUID.class), rs.getObject("message_id", UUID.class),
                rs.getString("content_type"), rs.getLong("size_bytes")});
        if (rows.isEmpty() || !userId.equals(rows.get(0)[0]) || rows.get(0)[1] != null) {
            throw ApiException.badRequest("upload the image first, then set it");
        }
        String ct = (String) rows.get(0)[2];
        if (ct == null || !ct.startsWith("image/")) {
            throw ApiException.badRequest("must be an image");
        }
        if ((long) rows.get(0)[3] > 2_000_000L) {
            throw ApiException.badRequest("images are capped at 2 MB");
        }
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

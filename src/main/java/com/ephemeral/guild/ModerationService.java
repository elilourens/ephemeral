package com.ephemeral.guild;

import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.voice.VoicePresenceService;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin moderation: server bans (kick + can't rejoin) and in-call enforcement
 * (server mute / deafen / disconnect). Voice enforcement is delivered as a
 * {@code voice_force} event that the target's client applies — honest-client
 * enforcement, the right trade-off for a self-hosted friend-group instance.
 * Every action is written to the audit log.
 */
@Service
public class ModerationService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GuildService guilds;
    private final AuditService audit;
    private final RealtimeService realtime;
    private final VoicePresenceService presence;

    public ModerationService(NamedParameterJdbcTemplate jdbc, GuildService guilds, AuditService audit,
                             RealtimeService realtime, VoicePresenceService presence) {
        this.jdbc = jdbc;
        this.guilds = guilds;
        this.audit = audit;
        this.realtime = realtime;
        this.presence = presence;
    }

    // ---- bans ---------------------------------------------------------------

    public void ban(UUID actorId, UUID guildId, UUID targetId, String reason) {
        guilds.requireAdmin(actorId, guildId);
        UUID owner = jdbc.queryForObject("select owner_id from guilds where id = :g",
                Map.of("g", guildId), UUID.class);
        if (targetId.equals(owner)) {
            throw ApiException.badRequest("cannot ban the server owner");
        }
        jdbc.update("""
                insert into guild_bans (guild_id, user_id, banned_by, reason) values (:g, :u, :by, :r)
                on conflict (guild_id, user_id) do update set banned_by = :by, reason = :r
                """, Map.of("g", guildId, "u", targetId, "by", actorId,
                "r", reason == null ? "" : reason.trim()));
        jdbc.update("delete from memberships where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", targetId));
        disconnectFromGuildVoice(guildId, targetId);
        audit.log(guildId, actorId, "member.ban", targetId, reason);
    }

    public void unban(UUID actorId, UUID guildId, UUID targetId) {
        guilds.requireAdmin(actorId, guildId);
        int n = jdbc.update("delete from guild_bans where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", targetId));
        if (n == 0) {
            throw ApiException.notFound("that user is not banned");
        }
        audit.log(guildId, actorId, "member.unban", targetId, null);
    }

    public List<Map<String, Object>> listBans(UUID actorId, UUID guildId) {
        guilds.requireAdmin(actorId, guildId);
        return jdbc.query("""
                select b.user_id, u.username, u.display_name, b.reason
                from guild_bans b join users u on u.id = b.user_id
                where b.guild_id = :g order by u.username
                """, Map.of("g", guildId), (rs, i) -> Map.of(
                "userId", rs.getObject("user_id", UUID.class),
                "username", rs.getString("username"),
                "displayName", rs.getString("display_name"),
                "reason", rs.getString("reason") == null ? "" : rs.getString("reason")));
    }

    // ---- in-call enforcement --------------------------------------------------

    /** Server-mute / unmute someone in a guild voice channel. */
    public void muteInVoice(UUID actorId, UUID channelId, UUID targetId, boolean on) {
        UUID guildId = requireVoiceModeration(actorId, channelId, targetId);
        realtime.voiceForce(targetId, channelId, Map.of("mute", on));
        audit.log(guildId, actorId, on ? "voice.mute" : "voice.unmute", targetId, null);
    }

    /** Server-deafen / undeafen someone in a guild voice channel. */
    public void deafenInVoice(UUID actorId, UUID channelId, UUID targetId, boolean on) {
        UUID guildId = requireVoiceModeration(actorId, channelId, targetId);
        realtime.voiceForce(targetId, channelId, Map.of("deafen", on));
        audit.log(guildId, actorId, on ? "voice.deafen" : "voice.undeafen", targetId, null);
    }

    /** Kick someone out of a guild voice channel (they may rejoin unless banned). */
    public void disconnectFromVoice(UUID actorId, UUID channelId, UUID targetId) {
        UUID guildId = requireVoiceModeration(actorId, channelId, targetId);
        realtime.voiceForce(targetId, channelId, Map.of("disconnect", true));
        audit.log(guildId, actorId, "voice.disconnect", targetId, null);
    }

    private UUID requireVoiceModeration(UUID actorId, UUID channelId, UUID targetId) {
        UUID guildId = guilds.guildIdOfChannel(channelId);
        if (guildId == null) {
            throw ApiException.badRequest("DM calls have no server admins");
        }
        guilds.requireAdmin(actorId, guildId);
        if (!presence.contains(channelId, targetId.toString())) {
            throw ApiException.notFound("they are not in this call");
        }
        return guildId;
    }

    private void disconnectFromGuildVoice(UUID guildId, UUID targetId) {
        for (UUID channelId : jdbc.queryForList(
                "select id from channels where guild_id = :g and type = 'voice'",
                Map.of("g", guildId), UUID.class)) {
            if (presence.contains(channelId, targetId.toString())) {
                realtime.voiceForce(targetId, channelId, Map.of("disconnect", true));
            }
        }
    }
}

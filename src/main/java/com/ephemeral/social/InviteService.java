package com.ephemeral.social;

import com.ephemeral.dto.GuildDto;
import com.ephemeral.dto.InviteDto;
import com.ephemeral.dto.UserBriefDto;
import com.ephemeral.guild.AuditService;
import com.ephemeral.guild.GuildService;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consent-based membership: server invites (admin asks, invitee accepts) and
 * join requests (user asks, admin approves). Both paths run the same ban check
 * as the old direct-add and end in the same memberships insert.
 */
@Service
public class InviteService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GuildService guilds;
    private final AuditService audit;
    private final RealtimeService realtime;

    public InviteService(NamedParameterJdbcTemplate jdbc, GuildService guilds,
                         AuditService audit, RealtimeService realtime) {
        this.jdbc = jdbc;
        this.guilds = guilds;
        this.audit = audit;
        this.realtime = realtime;
    }

    // ---- invites (admin -> user, user consents) ---------------------------

    public InviteDto createInvite(UUID inviterId, UUID guildId, String username) {
        guilds.requireAdmin(inviterId, guildId);
        String uname = username == null ? "" : username.trim().toLowerCase().replaceFirst("^@", "");
        List<UUID> found = jdbc.queryForList("select id from users where username = :u",
                Map.of("u", uname), UUID.class);
        if (found.isEmpty()) {
            throw ApiException.notFound("no such user");
        }
        UUID inviteeId = found.get(0);
        if (guilds.isBanned(inviteeId, guildId)) {
            throw ApiException.badRequest("that user is banned from this server (unban them first)");
        }
        if (guilds.isMember(inviteeId, guildId)) {
            throw ApiException.conflict("@" + uname + " is already a member");
        }
        UUID id = Ids.newId();
        int inserted = jdbc.update("""
                insert into guild_invites (id, guild_id, inviter_id, invitee_id)
                values (:id, :g, :inviter, :invitee)
                on conflict (guild_id, invitee_id) do nothing
                """, Map.of("id", id, "g", guildId, "inviter", inviterId, "invitee", inviteeId));
        if (inserted == 0) {
            throw ApiException.conflict("@" + uname + " already has a pending invite");
        }
        audit.log(guildId, inviterId, "invite.create", inviteeId, "@" + uname);
        realtime.socialUpdate(List.of(inviteeId), "invites", guildId);
        return inviteById(id);
    }

    public List<InviteDto> myInvites(UUID userId) {
        return jdbc.query("""
                select i.id, i.guild_id, g.name, g.icon_id, u.display_name as inviter
                from guild_invites i
                join guilds g on g.id = i.guild_id
                left join users u on u.id = i.inviter_id
                where i.invitee_id = :u
                order by i.id desc
                """, Map.of("u", userId), (rs, n) -> new InviteDto(
                rs.getObject("id", UUID.class), rs.getObject("guild_id", UUID.class), rs.getString("name"),
                rs.getObject("icon_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("icon_id", UUID.class),
                rs.getString("inviter")));
    }

    private InviteDto inviteById(UUID id) {
        return jdbc.queryForObject("""
                select i.id, i.guild_id, g.name, g.icon_id, u.display_name as inviter
                from guild_invites i
                join guilds g on g.id = i.guild_id
                left join users u on u.id = i.inviter_id
                where i.id = :id
                """, Map.of("id", id), (rs, n) -> new InviteDto(
                rs.getObject("id", UUID.class), rs.getObject("guild_id", UUID.class), rs.getString("name"),
                rs.getObject("icon_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("icon_id", UUID.class),
                rs.getString("inviter")));
    }

    public GuildDto acceptInvite(UUID userId, UUID inviteId) {
        List<UUID> g = jdbc.queryForList(
                "select guild_id from guild_invites where id = :id and invitee_id = :u",
                Map.of("id", inviteId, "u", userId), UUID.class);
        if (g.isEmpty()) {
            throw ApiException.notFound("invite not found");
        }
        UUID guildId = g.get(0);
        if (guilds.isBanned(userId, guildId)) { // banned after being invited
            throw ApiException.forbidden("you are banned from this server");
        }
        join(guildId, userId, "via invite");
        jdbc.update("delete from guild_invites where id = :id", Map.of("id", inviteId));
        return guilds.getGuild(userId, guildId);
    }

    /** Decline (invitee) or revoke (a guild admin) a pending invite. */
    public void deleteInvite(UUID userId, UUID inviteId) {
        var rows = jdbc.query("select guild_id, invitee_id from guild_invites where id = :id",
                Map.of("id", inviteId),
                (rs, n) -> new UUID[]{rs.getObject("guild_id", UUID.class), rs.getObject("invitee_id", UUID.class)});
        if (rows.isEmpty()) {
            throw ApiException.notFound("invite not found");
        }
        UUID guildId = rows.get(0)[0], inviteeId = rows.get(0)[1];
        if (!userId.equals(inviteeId) && !guilds.isAdmin(userId, guildId)) {
            throw ApiException.forbidden("not your invite");
        }
        jdbc.update("delete from guild_invites where id = :id", Map.of("id", inviteId));
        realtime.socialUpdate(List.of(inviteeId), "invites", guildId);
    }

    // ---- join requests (user -> guild, admin consents) ---------------------

    /** Ask to join. If an invite is already waiting, that's mutual consent — join now. */
    public Map<String, Object> requestJoin(UUID userId, UUID guildId) {
        Integer exists = jdbc.queryForObject("select count(*) from guilds where id = :g",
                Map.of("g", guildId), Integer.class);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("server not found");
        }
        if (guilds.isBanned(userId, guildId)) {
            throw ApiException.forbidden("you are banned from this server");
        }
        if (guilds.isMember(userId, guildId)) {
            throw ApiException.conflict("you are already a member");
        }
        List<UUID> invite = jdbc.queryForList(
                "select id from guild_invites where guild_id = :g and invitee_id = :u",
                Map.of("g", guildId, "u", userId), UUID.class);
        if (!invite.isEmpty()) {
            GuildDto joined = acceptInvite(userId, invite.get(0));
            return Map.of("status", "joined", "guild", joined);
        }
        int inserted = jdbc.update("""
                insert into guild_join_requests (guild_id, user_id) values (:g, :u)
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", userId));
        if (inserted > 0) {
            realtime.socialUpdate(adminIds(guildId), "join_requests", guildId);
        }
        return Map.of("status", "requested");
    }

    public List<UUID> myJoinRequests(UUID userId) {
        return jdbc.queryForList("select guild_id from guild_join_requests where user_id = :u",
                Map.of("u", userId), UUID.class);
    }

    public List<UserBriefDto> listJoinRequests(UUID adminId, UUID guildId) {
        guilds.requireAdmin(adminId, guildId);
        return jdbc.query("""
                select u.id, u.username, u.display_name, u.avatar_id
                from guild_join_requests r join users u on u.id = r.user_id
                where r.guild_id = :g order by u.username
                """, Map.of("g", guildId), (rs, n) -> new UserBriefDto(
                rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getObject("avatar_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("avatar_id", UUID.class)));
    }

    public void approveJoinRequest(UUID adminId, UUID guildId, UUID targetId) {
        guilds.requireAdmin(adminId, guildId);
        int n = jdbc.update("delete from guild_join_requests where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", targetId));
        if (n == 0) {
            throw ApiException.notFound("no such join request");
        }
        if (guilds.isBanned(targetId, guildId)) { // banned while the request sat pending
            throw ApiException.badRequest("that user is banned from this server (unban them first)");
        }
        join(guildId, targetId, "request approved");
        audit.log(guildId, adminId, "request.approve", targetId, null);
        realtime.socialUpdate(List.of(targetId), "guild_joined", guildId);
    }

    /** Deny (a guild admin) or cancel (the requester) a pending join request. */
    public void deleteJoinRequest(UUID actorId, UUID guildId, UUID targetId) {
        if (!actorId.equals(targetId)) {
            guilds.requireAdmin(actorId, guildId);
        }
        int n = jdbc.update("delete from guild_join_requests where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", targetId));
        if (n > 0 && !actorId.equals(targetId)) {
            audit.log(guildId, actorId, "request.deny", targetId, null);
        }
    }

    // ---- shared ------------------------------------------------------------

    private void join(UUID guildId, UUID userId, String how) {
        int inserted = jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", userId));
        if (inserted > 0) {
            audit.log(guildId, userId, "member.join", userId, how);
        }
        // either path consumes any counterpart consent artifacts left behind
        jdbc.update("delete from guild_join_requests where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", userId));
        jdbc.update("delete from guild_invites where guild_id = :g and invitee_id = :u",
                Map.of("g", guildId, "u", userId));
    }

    private List<UUID> adminIds(UUID guildId) {
        return jdbc.queryForList(
                "select user_id from memberships where guild_id = :g and role = 'admin'",
                Map.of("g", guildId), UUID.class);
    }
}

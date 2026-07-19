package com.ephemeral.dm;

import com.ephemeral.dto.DmDto;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Direct messages: guild-less channels (type 'dm') whose membership is the
 * {@code dm_members} set. 1:1 conversations are deduplicated by {@code dm_key}
 * (sorted "a:b"); group DMs (3..10 people, dm_key null) are never deduplicated
 * and have an owner (the creator) who may remove members. Adding someone to a
 * 1:1 creates a NEW group and leaves the 1:1 untouched — Discord semantics.
 * Messages, attachments, reactions, read-state and calls (one LiveKit room per
 * channel, so a group call needs nothing extra) reuse the channel machinery.
 */
@Service
public class DmService {

    public static final int MAX_MEMBERS = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final RealtimeService realtime;

    public DmService(NamedParameterJdbcTemplate jdbc, RealtimeService realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    /** Canonical, order-independent key for a 1:1 DM so open() is idempotent. */
    private static String dmKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    // ---- queries -----------------------------------------------------------

    /** All DM conversations for a user, most recently active first. */
    public List<DmDto> list(UUID userId) {
        List<UUID> channels = jdbc.queryForList(
                "select channel_id from dm_members where user_id = :u", Map.of("u", userId), UUID.class);
        List<DmDto> out = new ArrayList<>(channels.size());
        for (UUID c : channels) {
            out.add(one(userId, c));
        }
        out.sort((a, b) -> {
            Instant x = a.lastMessageAt(), y = b.lastMessageAt();
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });
        return out;
    }

    /** One conversation from a member's perspective (404 if not a member). */
    public DmDto one(UUID userId, UUID channelId) {
        var head = jdbc.query("""
                select c.dm_key, c.name, c.dm_owner_id,
                       (select m.id from messages m where m.channel_id = c.id order by m.id desc limit 1) as last_id,
                       exists (select 1 from messages m where m.channel_id = c.id
                               and (rs.last_read_id is null or m.id > rs.last_read_id)) as unread
                from channels c
                join dm_members me on me.channel_id = c.id and me.user_id = :u
                left join read_state rs on rs.channel_id = c.id and rs.user_id = :u
                where c.id = :c
                """, Map.of("u", userId, "c", channelId), (rs, i) -> new Object[]{
                rs.getString("dm_key"), rs.getString("name"), rs.getObject("dm_owner_id", UUID.class),
                rs.getObject("last_id", UUID.class), rs.getBoolean("unread")});
        if (head.isEmpty()) {
            throw ApiException.notFound("conversation not found");
        }
        Object[] hd = head.get(0);
        List<DmDto.DmUser> others = jdbc.query("""
                select u.id, u.username, u.display_name from dm_members dm
                join users u on u.id = dm.user_id
                where dm.channel_id = :c and dm.user_id <> :u
                order by u.username
                """, Map.of("c", channelId, "u", userId), (rs, i) ->
                new DmDto.DmUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name")));
        boolean group = hd[0] == null;
        String name = group && hd[1] != null && !"dm".equals(hd[1]) ? (String) hd[1] : "";
        UUID lastId = (UUID) hd[3];
        return new DmDto(channelId, group, name, (UUID) hd[2], others,
                lastId != null ? Ids.timestampOf(lastId) : null, (boolean) hd[4]);
    }

    public List<UUID> memberIds(UUID channelId) {
        return jdbc.queryForList("select user_id from dm_members where channel_id = :c",
                Map.of("c", channelId), UUID.class);
    }

    // ---- 1:1 ----------------------------------------------------------------

    /** Open (creating if needed, idempotently) the 1:1 DM channel with another user. */
    public DmDto open(UUID userId, UUID otherId) {
        if (userId.equals(otherId)) {
            throw ApiException.badRequest("you cannot message yourself");
        }
        Integer exists = jdbc.queryForObject("select count(*) from users where id = :o",
                Map.of("o", otherId), Integer.class);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("user not found");
        }
        String key = dmKey(userId, otherId);
        List<UUID> found = jdbc.queryForList("select id from channels where dm_key = :k", Map.of("k", key), UUID.class);
        UUID channelId = found.isEmpty()
                ? newDmChannel(key, "dm", null, List.of(userId, otherId))
                : found.get(0);
        return one(userId, channelId);
    }

    /** Open a DM by the other person's username (case-insensitive). */
    public DmDto openByUsername(UUID userId, String username) {
        return open(userId, idOf(username));
    }

    // ---- groups --------------------------------------------------------------

    /** Create a group DM with the given usernames (creator included + owner, 3..10 total). */
    public DmDto createGroup(UUID userId, List<String> usernames, String name) {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(userId);
        for (String n : usernames == null ? List.<String>of() : usernames) {
            members.add(idOf(n));
        }
        if (members.size() < 3) {
            throw ApiException.badRequest("a group needs at least 3 people (use a normal DM for 1:1)");
        }
        if (members.size() > MAX_MEMBERS) {
            throw ApiException.badRequest("groups are capped at " + MAX_MEMBERS + " people");
        }
        UUID channelId = newDmChannel(null, cleanName(name), userId, List.copyOf(members));
        DmDto dto = one(userId, channelId);
        realtime.dmUpdated(channelId, memberIds(channelId));
        return dto;
    }

    /**
     * Add a person. To a group: joins in place (any member may add, cap 10). To a
     * 1:1: creates a NEW group owned by the adder with all three people, leaving
     * the original 1:1 untouched (Discord semantics). Returns the conversation
     * the caller should land in.
     */
    public DmDto addMember(UUID userId, UUID channelId, String username) {
        requireMember(userId, channelId);
        UUID targetId = idOf(username);
        List<UUID> current = memberIds(channelId);
        if (isOneToOne(channelId)) {
            Set<UUID> members = new LinkedHashSet<>(current);
            if (!members.add(targetId)) {
                throw ApiException.badRequest("they are already in this conversation");
            }
            UUID groupId = newDmChannel(null, "", userId, List.copyOf(members));
            DmDto dto = one(userId, groupId);
            realtime.dmUpdated(groupId, memberIds(groupId));
            return dto;
        }
        if (current.contains(targetId)) {
            throw ApiException.badRequest("they are already in this group");
        }
        if (current.size() >= MAX_MEMBERS) {
            throw ApiException.badRequest("groups are capped at " + MAX_MEMBERS + " people");
        }
        jdbc.update("insert into dm_members (channel_id, user_id) values (:c, :u)",
                Map.of("c", channelId, "u", targetId));
        realtime.dmUpdated(channelId, memberIds(channelId));
        return one(userId, channelId);
    }

    /** Remove someone from a group DM — the group owner only. */
    public void kick(UUID ownerId, UUID channelId, UUID targetId) {
        requireMember(ownerId, channelId);
        requireGroup(channelId);
        UUID owner = jdbc.queryForObject("select dm_owner_id from channels where id = :c",
                Map.of("c", channelId), UUID.class);
        if (!ownerId.equals(owner)) {
            throw ApiException.forbidden("only the group owner can remove people");
        }
        if (ownerId.equals(targetId)) {
            throw ApiException.badRequest("use leave to remove yourself");
        }
        int n = jdbc.update("delete from dm_members where channel_id = :c and user_id = :u",
                Map.of("c", channelId, "u", targetId));
        if (n == 0) {
            throw ApiException.notFound("they are not in this group");
        }
        // tell the kicked user too, so their client drops the conversation
        List<UUID> notify = new ArrayList<>(memberIds(channelId));
        notify.add(targetId);
        realtime.dmUpdated(channelId, notify);
    }

    /** Leave a group DM (1:1s can't be left). Ownership transfers; last one out deletes it. */
    public void leave(UUID userId, UUID channelId) {
        requireMember(userId, channelId);
        requireGroup(channelId);
        jdbc.update("delete from dm_members where channel_id = :c and user_id = :u",
                Map.of("c", channelId, "u", userId));
        List<UUID> remaining = memberIds(channelId);
        if (remaining.isEmpty()) {
            jdbc.update("delete from channels where id = :c", Map.of("c", channelId));
            return;
        }
        jdbc.update("""
                update channels set dm_owner_id = :next where id = :c and dm_owner_id = :leaver
                """, Map.of("next", remaining.get(0), "c", channelId, "leaver", userId));
        realtime.dmUpdated(channelId, remaining);
    }

    /** Rename a group DM (any member may; "" clears back to joined member names). */
    public DmDto rename(UUID userId, UUID channelId, String name) {
        requireMember(userId, channelId);
        requireGroup(channelId);
        String n = cleanName(name);
        jdbc.update("update channels set name = :n where id = :c",
                Map.of("n", n.isEmpty() ? "dm" : n, "c", channelId));
        DmDto dto = one(userId, channelId);
        realtime.dmUpdated(channelId, memberIds(channelId));
        return dto;
    }

    // ---- internals ------------------------------------------------------------

    private UUID newDmChannel(String dmKey, String name, UUID ownerId, List<UUID> members) {
        UUID channelId = Ids.newId();
        jdbc.update("""
                insert into channels (id, guild_id, name, type, position, dm_key, dm_owner_id)
                values (:id, null, :n, 'dm', 0, :k, :o)
                """, new MapSqlParameterSource().addValue("id", channelId)
                .addValue("n", name == null || name.isBlank() ? "dm" : name)
                .addValue("k", dmKey).addValue("o", ownerId));
        for (UUID m : members) {
            jdbc.update("insert into dm_members (channel_id, user_id) values (:c, :u)",
                    Map.of("c", channelId, "u", m));
        }
        return channelId;
    }

    private boolean isOneToOne(UUID channelId) {
        List<String> k = jdbc.queryForList("select dm_key from channels where id = :c",
                Map.of("c", channelId), String.class);
        if (k.isEmpty()) {
            throw ApiException.notFound("conversation not found");
        }
        return k.get(0) != null;
    }

    private void requireGroup(UUID channelId) {
        if (isOneToOne(channelId)) {
            throw ApiException.badRequest("this is a 1:1 conversation, not a group");
        }
    }

    private void requireMember(UUID userId, UUID channelId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from dm_members where channel_id = :c and user_id = :u",
                Map.of("c", channelId, "u", userId), Integer.class);
        if (n == null || n == 0) {
            throw ApiException.forbidden("not a participant in this conversation");
        }
    }

    private UUID idOf(String username) {
        String n = username == null ? "" : username.trim().replaceFirst("^@", "");
        List<UUID> r = jdbc.queryForList("select id from users where lower(username) = lower(:n)",
                Map.of("n", n), UUID.class);
        if (r.isEmpty()) {
            throw ApiException.notFound("no user named @" + n);
        }
        return r.get(0);
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 80) {
            throw ApiException.badRequest("group name must be at most 80 characters");
        }
        return n;
    }
}

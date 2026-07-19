package com.ephemeral.dm;

import com.ephemeral.dto.DmDto;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct messages. A DM is a guild-less channel (type 'dm') whose membership is
 * the {@code dm_members} set; everything else (messages, attachments, reactions,
 * calls) reuses the normal channel machinery.
 */
@Service
public class DmService {

    private final NamedParameterJdbcTemplate jdbc;

    public DmService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Canonical, order-independent key for a 1:1 DM so open() is idempotent. */
    private static String dmKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    private final RowMapper<DmDto> mapper = (rs, i) -> {
        UUID lastId = rs.getObject("last_id", UUID.class);
        Instant lastAt = lastId != null ? Ids.timestampOf(lastId) : null;
        return new DmDto(
                rs.getObject("cid", UUID.class),
                new DmDto.DmUser(rs.getObject("uid", UUID.class), rs.getString("username"), rs.getString("dname")),
                lastAt,
                rs.getBoolean("unread"));
    };

    // The other participant + last-message + unread, from one user's perspective.
    private static final String SELECT = """
            select c.id as cid, u.id as uid, u.username, u.display_name as dname,
                   (select m.id from messages m where m.channel_id = c.id order by m.id desc limit 1) as last_id,
                   exists (select 1 from messages m where m.channel_id = c.id
                           and (rs.last_read_id is null or m.id > rs.last_read_id)) as unread
            from dm_members me
            join channels c on c.id = me.channel_id
            join dm_members other on other.channel_id = c.id and other.user_id <> me.user_id
            join users u on u.id = other.user_id
            left join read_state rs on rs.channel_id = c.id and rs.user_id = me.user_id
            where me.user_id = :u
            """;

    /** All DM conversations for a user, most recently active first. */
    public List<DmDto> list(UUID userId) {
        return jdbc.query(SELECT + " order by last_id desc nulls last", Map.of("u", userId), mapper);
    }

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
        if (found.isEmpty()) {
            UUID channelId = Ids.newId();
            jdbc.update("""
                    insert into channels (id, guild_id, name, type, position, dm_key)
                    values (:id, null, 'dm', 'dm', 0, :k)
                    """, new MapSqlParameterSource().addValue("id", channelId).addValue("k", key));
            jdbc.update("insert into dm_members (channel_id, user_id) values (:c, :a), (:c, :b)",
                    new MapSqlParameterSource().addValue("c", channelId).addValue("a", userId).addValue("b", otherId));
        }
        return one(userId, otherId);
    }

    /** Open a DM by the other person's username (case-insensitive). */
    public DmDto openByUsername(UUID userId, String username) {
        List<UUID> r = jdbc.queryForList("select id from users where lower(username) = lower(:n)",
                Map.of("n", username), UUID.class);
        if (r.isEmpty()) {
            throw ApiException.notFound("no user named @" + username);
        }
        return open(userId, r.get(0));
    }

    private DmDto one(UUID userId, UUID otherId) {
        return jdbc.query(SELECT + " and other.user_id = :o",
                        new MapSqlParameterSource().addValue("u", userId).addValue("o", otherId), mapper)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("conversation not found"));
    }
}

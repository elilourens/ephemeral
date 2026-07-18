package com.ephemeral.read;

import com.ephemeral.dto.ReadStateDto;
import com.ephemeral.guild.GuildService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-channel unread / mention tracking. */
@Service
public class ReadStateService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GuildService guilds;

    public ReadStateService(NamedParameterJdbcTemplate jdbc, GuildService guilds) {
        this.jdbc = jdbc;
        this.guilds = guilds;
    }

    public List<ReadStateDto> forGuild(UUID userId, UUID guildId) {
        guilds.requireMember(userId, guildId);
        // NB: Postgres has no max() aggregate for uuid, but uuid IS orderable —
        // so take the newest id per channel with order-by-limit-1, not max().
        return jdbc.query("""
                select c.id as channel_id,
                       coalesce(rs.mention_count, 0) as mc,
                       rs.last_read_id,
                       (select mm.id from messages mm where mm.channel_id = c.id
                        order by mm.id desc limit 1) as latest
                from channels c
                left join read_state rs on rs.channel_id = c.id and rs.user_id = :u
                where c.guild_id = :g and (c.admin_only = false or exists (
                    select 1 from memberships m where m.guild_id = c.guild_id
                    and m.user_id = :u and m.role = 'admin'))
                """, new MapSqlParameterSource().addValue("u", userId).addValue("g", guildId),
                (rs, i) -> new ReadStateDto(
                        rs.getObject("channel_id", UUID.class),
                        rs.getInt("mc"),
                        rs.getObject("last_read_id", UUID.class),
                        rs.getObject("latest", UUID.class)));
    }

    public void ack(UUID userId, UUID channelId, UUID lastReadId) {
        guilds.requireChannelMember(userId, channelId);
        jdbc.update("""
                insert into read_state (user_id, channel_id, last_read_id, mention_count)
                values (:u, :c, :lr, 0)
                on conflict (user_id, channel_id) do update set last_read_id = :lr, mention_count = 0
                """, new MapSqlParameterSource()
                .addValue("u", userId).addValue("c", channelId).addValue("lr", lastReadId));
    }
}

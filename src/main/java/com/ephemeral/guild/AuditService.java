package com.ephemeral.guild;

import com.ephemeral.util.Ids;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-server admin log. Every moderation action and server change lands here
 * (actions like {@code member.kick}, {@code voice.mute}, {@code channel.update});
 * rows are uuidv7-ordered, cascade with the guild, and a 30-day sweep in
 * {@link com.ephemeral.message.RetentionService} keeps the log itself ephemeral.
 */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(UUID guildId, UUID actorId, String action, UUID targetUserId, String detail) {
        jdbc.update("""
                insert into audit_log (id, guild_id, actor_id, action, target_user_id, detail)
                values (:id, :g, :a, :act, :t, :d)
                """, new MapSqlParameterSource().addValue("id", Ids.newId()).addValue("g", guildId)
                .addValue("a", actorId).addValue("act", action).addValue("t", targetUserId)
                .addValue("d", detail));
    }

    /** Newest-first entries with actor/target names resolved (admin viewer). */
    public List<Map<String, Object>> list(UUID guildId, int limit) {
        return jdbc.query("""
                select l.id, l.action, l.detail, l.actor_id, l.target_user_id,
                       au.display_name as actor_name, tu.display_name as target_name
                from audit_log l
                left join users au on au.id = l.actor_id
                left join users tu on tu.id = l.target_user_id
                where l.guild_id = :g order by l.id desc limit :lim
                """, new MapSqlParameterSource().addValue("g", guildId)
                .addValue("lim", Math.max(1, Math.min(limit, 200))), (rs, i) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            UUID id = rs.getObject("id", UUID.class);
            row.put("at", Ids.timestampOf(id));
            row.put("action", rs.getString("action"));
            row.put("detail", rs.getString("detail"));
            row.put("actorId", rs.getObject("actor_id", UUID.class));
            row.put("actorName", rs.getString("actor_name"));
            row.put("targetId", rs.getObject("target_user_id", UUID.class));
            row.put("targetName", rs.getString("target_name"));
            return row;
        });
    }
}

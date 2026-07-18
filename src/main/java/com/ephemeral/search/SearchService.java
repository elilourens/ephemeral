package com.ephemeral.search;

import com.ephemeral.dto.SearchHitDto;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Full-text message search over the {@code content_tsv} generated column.
 * Results are scoped to servers the viewer belongs to and never leak admin-only
 * channels to non-admins. Supports {@code from:} (author), {@code in:} (channel)
 * and {@code has:} (link/image/file) filters, and recent-vs-relevant sort.
 */
@Service
public class SearchService {

    private final NamedParameterJdbcTemplate jdbc;

    public SearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchHitDto> search(UUID viewerId, String q, UUID guildId, UUID channelId,
                                     UUID authorId, String has, String sort, int limit, int offset) {
        String text = q == null ? "" : q.trim();
        boolean hasText = !text.isEmpty();
        if (!hasText && authorId == null && has == null && channelId == null) {
            throw ApiException.badRequest("enter a search term or a filter");
        }
        int lim = Math.max(1, Math.min(limit, 50));
        int off = Math.max(0, offset);

        StringBuilder sql = new StringBuilder("""
                select m.id, m.channel_id, c.name as channel_name, c.guild_id,
                       m.author_id, u.username, u.display_name, m.content
                from messages m
                join channels c on c.id = m.channel_id
                join memberships mem on mem.guild_id = c.guild_id and mem.user_id = :viewer
                join users u on u.id = m.author_id
                """);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("viewer", viewerId);
        StringBuilder where = new StringBuilder(
                " where (c.admin_only = false or mem.role = 'admin')");

        if (hasText) {
            sql.append(", websearch_to_tsquery('english', :q) tsq\n");
            where.append(" and m.content_tsv @@ tsq");
            p.addValue("q", text);
        }
        if (guildId != null) {
            where.append(" and c.guild_id = :g");
            p.addValue("g", guildId);
        }
        if (channelId != null) {
            where.append(" and m.channel_id = :c");
            p.addValue("c", channelId);
        }
        if (authorId != null) {
            where.append(" and m.author_id = :a");
            p.addValue("a", authorId);
        }
        if (has != null) {
            switch (has) {
                case "link" -> where.append(" and m.content ~* 'https?://'");
                case "image" -> where.append(" and exists (select 1 from attachments a "
                        + "where a.message_id = m.id and a.content_type like 'image/%')");
                case "file" -> where.append(" and exists (select 1 from attachments a where a.message_id = m.id)");
                default -> { /* ignore unknown filter */ }
            }
        }
        sql.append(where);
        // "relevant" ranks by ts_rank (only meaningful with query text); default recent.
        if ("relevant".equals(sort) && hasText) {
            sql.append(" order by ts_rank(m.content_tsv, tsq) desc, m.id desc");
        } else {
            sql.append(" order by m.id desc");
        }
        sql.append(" limit :lim offset :off");
        p.addValue("lim", lim).addValue("off", off);

        return jdbc.query(sql.toString(), p, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            String dn = rs.getString("display_name");
            String un = rs.getString("username");
            return new SearchHitDto(id,
                    rs.getObject("channel_id", UUID.class),
                    rs.getString("channel_name"),
                    rs.getObject("guild_id", UUID.class),
                    rs.getObject("author_id", UUID.class),
                    dn != null && !dn.isBlank() ? dn : un,
                    rs.getString("content"),
                    Ids.timestampOf(id));
        });
    }
}

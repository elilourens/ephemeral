package com.ephemeral.feedback;

import com.ephemeral.config.AppProperties;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anyone can submit feedback; only the instance operator reads it. The operator
 * is the configured {@code ephemeral.operator-username}, or — the self-hosting
 * default — the first account ever registered (smallest UUIDv7 = earliest).
 */
@Service
public class FeedbackService {

    /** A feedback entry as the operator's inbox shows it. */
    public record FeedbackDto(UUID id, String author, long createdAtMs, String body) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties props;

    public FeedbackService(NamedParameterJdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public boolean isOperator(UUID userId) {
        String configured = props.getOperatorUsername() == null ? "" : props.getOperatorUsername().trim().toLowerCase();
        List<UUID> op = configured.isEmpty()
                ? jdbc.queryForList("select id from users order by id limit 1", Map.of(), UUID.class)
                : jdbc.queryForList("select id from users where username = :u", Map.of("u", configured), UUID.class);
        return !op.isEmpty() && op.get(0).equals(userId);
    }

    private void requireOperator(UUID userId) {
        if (!isOperator(userId)) {
            throw ApiException.forbidden("only the instance operator can do that");
        }
    }

    public void submit(UUID userId, String body) {
        String b = body == null ? "" : body.trim();
        if (b.isEmpty()) {
            throw ApiException.badRequest("say something first");
        }
        if (b.length() > 4000) {
            throw ApiException.badRequest("feedback is capped at 4000 characters");
        }
        jdbc.update("insert into feedback (id, user_id, body) values (:id, :u, :b)",
                Map.of("id", Ids.newId(), "u", userId, "b", b));
    }

    public List<FeedbackDto> list(UUID userId) {
        requireOperator(userId);
        return jdbc.query("""
                select f.id, f.body, u.username from feedback f
                left join users u on u.id = f.user_id
                order by f.id desc
                """, Map.of(), (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            String username = rs.getString("username");
            return new FeedbackDto(id, username == null ? "(deleted account)" : "@" + username,
                    Ids.timestampOf(id).toEpochMilli(), rs.getString("body"));
        });
    }

    public void delete(UUID userId, UUID feedbackId) {
        requireOperator(userId);
        jdbc.update("delete from feedback where id = :id", Map.of("id", feedbackId));
    }
}

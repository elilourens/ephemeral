package com.ephemeral.message;

import com.ephemeral.config.AppProperties;
import com.ephemeral.crypto.CryptoService;
import com.ephemeral.dto.AttachmentDto;
import com.ephemeral.dto.MessageDto;
import com.ephemeral.dto.ReactionDto;
import com.ephemeral.dto.ReplyRef;
import com.ephemeral.file.StorageService;
import com.ephemeral.guild.GuildService;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final GuildService guilds;
    private final StorageService storage;
    private final RealtimeService realtime;
    private final CryptoService crypto;
    private final boolean searchIndex;

    private static final Pattern MENTION = Pattern.compile("<@([0-9a-fA-F-]{36})>");
    private static final Pattern LINK = Pattern.compile("(?i)https?://");

    public MessageService(NamedParameterJdbcTemplate jdbc, GuildService guilds,
                          StorageService storage, RealtimeService realtime, CryptoService crypto,
                          AppProperties props) {
        this.jdbc = jdbc;
        this.guilds = guilds;
        this.storage = storage;
        this.realtime = realtime;
        this.crypto = crypto;
        this.searchIndex = props.isSearchIndex();
    }

    private record Row(UUID id, UUID channelId, UUID authorId, String authorName, String content,
                       boolean saved, boolean pinned, Instant editedAt, UUID replyToId) {
    }

    private static final String SELECT = """
            select m.id, m.channel_id, m.author_id, u.display_name as author_name,
                   m.content, m.saved, m.pinned, m.edited_at, m.reply_to_id
            from messages m join users u on u.id = m.author_id
            """;

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
            rs.getObject("author_id", UUID.class), rs.getString("author_name"),
            rs.getString("content"), rs.getBoolean("saved"), rs.getBoolean("pinned"),
            toInstant(rs.getTimestamp("edited_at")), rs.getObject("reply_to_id", UUID.class));

    public List<MessageDto> list(UUID userId, UUID channelId, UUID before, int limit) {
        guilds.requireChannelMember(userId, channelId);
        int lim = Math.min(Math.max(limit, 1), 100);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("c", channelId).addValue("lim", lim).addValue("before", before);
        String sql = SELECT + " where m.channel_id = :c "
                + (before != null ? " and m.id < :before " : "")
                + " order by m.id desc limit :lim";
        return toDtos(userId, jdbc.query(sql, p, ROW));
    }

    public List<MessageDto> listPins(UUID userId, UUID channelId) {
        guilds.requireChannelMember(userId, channelId);
        return toDtos(userId, jdbc.query(SELECT
                + " where m.channel_id = :c and m.pinned = true order by m.pinned_at desc nulls last, m.id desc",
                Map.of("c", channelId), ROW));
    }

    @Transactional
    public MessageDto send(UUID userId, UUID channelId, String content, List<UUID> attachmentIds, UUID replyToId,
                           Boolean pingReply) {
        UUID guildId = guilds.requireChannelMember(userId, channelId);
        // Messages are allowed in text AND voice channels (text-in-voice chat) —
        // storage channels hold files, not conversation.
        if ("storage".equals(guilds.channelType(channelId))) {
            throw ApiException.badRequest("storage channels don't take messages");
        }
        // Slow mode: non-admins must wait between posts.
        int slow = guilds.slowModeOf(channelId);
        if (slow > 0 && !guilds.isAdmin(userId, guildId)) {
            List<UUID> last = jdbc.queryForList(
                    "select id from messages where channel_id = :c and author_id = :u order by id desc limit 1",
                    Map.of("c", channelId, "u", userId), UUID.class);
            if (!last.isEmpty()) {
                long since = java.time.Duration.between(Ids.timestampOf(last.get(0)), java.time.Instant.now()).getSeconds();
                if (since < slow) {
                    throw ApiException.rateLimited("Slow mode is on — wait " + (slow - since) + "s before posting again");
                }
            }
        }
        String body = content == null ? "" : content.trim();
        boolean hasAttachments = attachmentIds != null && !attachmentIds.isEmpty();
        if (body.isEmpty() && !hasAttachments) {
            throw ApiException.badRequest("message is empty");
        }
        UUID validReply = null;
        if (replyToId != null) {
            Integer n = jdbc.queryForObject(
                    "select count(*) from messages where id = :r and channel_id = :c",
                    Map.of("r", replyToId, "c", channelId), Integer.class);
            if (n != null && n > 0) {
                validReply = replyToId;
            }
        }
        UUID id = Ids.newId();
        // content is encrypted at rest; the search vector + has:link flag are
        // computed from the plaintext bind param and never store readable text
        jdbc.update("""
                insert into messages (id, channel_id, author_id, content, saved, reply_to_id, content_tsv, has_link)
                values (:id, :c, :a, :content, false, :r, to_tsvector('english', :plain), :link)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("c", channelId).addValue("a", userId)
                .addValue("content", crypto.encrypt(body)).addValue("r", validReply)
                .addValue("plain", searchIndex ? body : null).addValue("link", LINK.matcher(body).find()));
        if (hasAttachments) {
            jdbc.update("""
                    update attachments set message_id = :m
                    where id in (:ids) and owner_id = :o and message_id is null
                    """, new MapSqlParameterSource()
                    .addValue("m", id).addValue("ids", attachmentIds).addValue("o", userId));
        }
        Set<UUID> pinged = new LinkedHashSet<>(parseMentions(body, guildId, channelId));
        // replying pings the original author unless the sender opted out (pingReply=false)
        if (validReply != null && !Boolean.FALSE.equals(pingReply)) {
            jdbc.queryForList("select author_id from messages where id = :r",
                    Map.of("r", validReply), UUID.class).forEach(pinged::add);
        }
        for (UUID mentioned : pinged) {
            jdbc.update("insert into message_mention (message_id, user_id) values (:m, :u) on conflict do nothing",
                    Map.of("m", id, "u", mentioned));
            if (!mentioned.equals(userId)) {
                jdbc.update("""
                        insert into read_state (user_id, channel_id, mention_count) values (:u, :c, 1)
                        on conflict (user_id, channel_id) do update set mention_count = read_state.mention_count + 1
                        """, Map.of("u", mentioned, "c", channelId));
            }
        }
        MessageDto dto = getMessage(userId, id);
        realtime.messageCreated(dto);
        return dto;
    }

    private List<UUID> parseMentions(String body, UUID guildId, UUID channelId) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            try {
                ids.add(UUID.fromString(m.group(1)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        // DMs have no guild — resolve the mention against the participant list instead
        if (guildId == null) {
            return jdbc.queryForList("select user_id from dm_members where channel_id = :c and user_id in (:ids)",
                    new MapSqlParameterSource().addValue("c", channelId).addValue("ids", ids), UUID.class);
        }
        return jdbc.queryForList("select user_id from memberships where guild_id = :g and user_id in (:ids)",
                new MapSqlParameterSource().addValue("g", guildId).addValue("ids", ids), UUID.class);
    }

    public MessageDto edit(UUID userId, UUID messageId, String content) {
        guilds.requireChannelMember(userId, channelOf(messageId)); // banned/kicked users can't edit
        List<UUID> author = jdbc.queryForList("select author_id from messages where id = :m",
                Map.of("m", messageId), UUID.class);
        if (author.isEmpty()) {
            throw ApiException.notFound("message not found");
        }
        if (!author.get(0).equals(userId)) {
            throw ApiException.forbidden("you can only edit your own messages");
        }
        String body = content == null ? "" : content.trim();
        if (body.isEmpty()) {
            throw ApiException.badRequest("message is empty");
        }
        // keep the old version (already ciphertext — copied verbatim); it cascades
        // away with the message, so history obeys the same 7-day vanish
        jdbc.update("""
                insert into message_edits (id, message_id, prev_content)
                select :eid, id, content from messages where id = :m
                """, Map.of("eid", Ids.newId(), "m", messageId));
        jdbc.update("""
                update messages set content = :c, edited_at = now(),
                       content_tsv = to_tsvector('english', :plain), has_link = :link
                where id = :m
                """, new MapSqlParameterSource()
                .addValue("c", crypto.encrypt(body)).addValue("plain", searchIndex ? body : null)
                .addValue("link", LINK.matcher(body).find()).addValue("m", messageId));
        MessageDto dto = getMessage(userId, messageId);
        realtime.messageUpdated(dto);
        return dto;
    }

    /** Toggle the viewer's reaction with this emoji on/off. */
    public MessageDto toggleReaction(UUID userId, UUID messageId, String emoji) {
        UUID channelId = channelOf(messageId);
        guilds.requireChannelMember(userId, channelId);
        String e = emoji == null ? "" : emoji.trim();
        if (e.isEmpty() || e.length() > 32) {
            throw ApiException.badRequest("invalid emoji");
        }
        Integer has = jdbc.queryForObject(
                "select count(*) from message_reaction where message_id = :m and user_id = :u and emoji = :e",
                Map.of("m", messageId, "u", userId, "e", e), Integer.class);
        if (has != null && has > 0) {
            jdbc.update("delete from message_reaction where message_id = :m and user_id = :u and emoji = :e",
                    Map.of("m", messageId, "u", userId, "e", e));
        } else {
            jdbc.update("insert into message_reaction (message_id, user_id, emoji) values (:m, :u, :e)",
                    Map.of("m", messageId, "u", userId, "e", e));
        }
        MessageDto dto = getMessage(userId, messageId);
        realtime.messageUpdated(dto);
        return dto;
    }

    public MessageDto setPin(UUID userId, UUID messageId, boolean pinned) {
        var meta = jdbc.query("select channel_id, author_id from messages where id = :m",
                Map.of("m", messageId),
                (rs, i) -> new UUID[]{rs.getObject("channel_id", UUID.class), rs.getObject("author_id", UUID.class)});
        if (meta.isEmpty()) {
            throw ApiException.notFound("message not found");
        }
        UUID channelId = meta.get(0)[0];
        UUID authorId = meta.get(0)[1];
        guilds.requireChannelMember(userId, channelId); // banned/kicked users can't pin
        UUID guildId = guilds.guildIdOfChannel(channelId);
        boolean admin = guilds.role(userId, guildId).map("admin"::equals).orElse(false);
        if (!admin && !authorId.equals(userId)) {
            throw ApiException.forbidden("cannot pin this message");
        }
        jdbc.update("update messages set pinned = :p, pinned_at = case when :p then now() else null end where id = :m",
                new MapSqlParameterSource().addValue("p", pinned).addValue("m", messageId));
        MessageDto dto = getMessage(userId, messageId);
        realtime.messageUpdated(dto);
        return dto;
    }

    public MessageDto save(UUID userId, UUID messageId) {
        UUID channelId = channelOf(messageId);
        guilds.requireChannelMember(userId, channelId);
        // you may only preserve your OWN posts from the 7-day vanish
        List<UUID> author = jdbc.queryForList("select author_id from messages where id = :m",
                Map.of("m", messageId), UUID.class);
        if (author.isEmpty()) {
            throw ApiException.notFound("message not found");
        }
        if (!author.get(0).equals(userId)) {
            throw ApiException.forbidden("you can only save your own messages");
        }
        jdbc.update("""
                insert into saves (user_id, message_id) values (:u, :m)
                on conflict (user_id, message_id) do nothing
                """, Map.of("u", userId, "m", messageId));
        jdbc.update("update messages set saved = true where id = :m", Map.of("m", messageId));
        MessageDto dto = getMessage(userId, messageId);
        realtime.messageUpdated(dto);
        return dto;
    }

    public MessageDto unsave(UUID userId, UUID messageId) {
        UUID channelId = channelOf(messageId);
        guilds.requireChannelMember(userId, channelId);
        jdbc.update("delete from saves where user_id = :u and message_id = :m",
                Map.of("u", userId, "m", messageId));
        Integer remaining = jdbc.queryForObject(
                "select count(*) from saves where message_id = :m", Map.of("m", messageId), Integer.class);
        if (remaining != null && remaining == 0) {
            jdbc.update("update messages set saved = false where id = :m", Map.of("m", messageId));
        }
        MessageDto dto = getMessage(userId, messageId);
        realtime.messageUpdated(dto);
        return dto;
    }

    @Transactional
    public void delete(UUID userId, UUID messageId) {
        var meta = jdbc.query("select channel_id, author_id from messages where id = :m",
                Map.of("m", messageId),
                (rs, i) -> new UUID[]{rs.getObject("channel_id", UUID.class), rs.getObject("author_id", UUID.class)});
        if (meta.isEmpty()) {
            throw ApiException.notFound("message not found");
        }
        UUID channelId = meta.get(0)[0];
        UUID authorId = meta.get(0)[1];
        guilds.requireChannelMember(userId, channelId); // banned/kicked users can't delete
        UUID guildId = guilds.guildIdOfChannel(channelId);
        boolean isAdmin = guilds.role(userId, guildId).map("admin"::equals).orElse(false);
        if (!authorId.equals(userId) && !isAdmin) {
            throw ApiException.forbidden("cannot delete this message");
        }
        List<String> keys = jdbc.queryForList(
                "select storage_key from attachments where message_id = :m",
                Map.of("m", messageId), String.class);
        jdbc.update("delete from messages where id = :m", Map.of("m", messageId));
        storage.deleteAll(keys);
        realtime.messageDeleted(channelId, messageId);
    }

    /** Prior versions of a message, newest edit first (the current text is not included). */
    public List<Map<String, Object>> history(UUID userId, UUID messageId) {
        guilds.requireChannelMember(userId, channelOf(messageId));
        return jdbc.query("""
                select id, prev_content from message_edits where message_id = :m order by id desc
                """, Map.of("m", messageId), (rs, i) -> Map.of(
                "editedAt", Ids.timestampOf(rs.getObject("id", UUID.class)),
                "content", crypto.decrypt(rs.getString("prev_content"))));
    }

    public MessageDto getMessage(UUID viewerId, UUID id) {
        List<Row> rows = jdbc.query(SELECT + " where m.id = :id", Map.of("id", id), ROW);
        if (rows.isEmpty()) {
            throw ApiException.notFound("message not found");
        }
        return toDtos(viewerId, rows).get(0);
    }

    private UUID channelOf(UUID messageId) {
        return jdbc.queryForList("select channel_id from messages where id = :m",
                        Map.of("m", messageId), UUID.class).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("message not found"));
    }

    private List<MessageDto> toDtos(UUID viewerId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Row::id).toList();

        Map<UUID, List<AttachmentDto>> byMessage = jdbc.query("""
                select id, message_id, filename, content_type, size_bytes, duration_ms, waveform
                from attachments where message_id in (:ids)
                """, Map.of("ids", ids), (rs, i) -> Map.entry(
                rs.getObject("message_id", UUID.class),
                new AttachmentDto(rs.getObject("id", UUID.class), rs.getString("filename"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        "/api/files/" + rs.getObject("id", UUID.class),
                        (Integer) rs.getObject("duration_ms"), rs.getString("waveform"))))
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        Map<UUID, ReplyRef> replies = new HashMap<>();
        List<UUID> replyIds = rows.stream().map(Row::replyToId).filter(Objects::nonNull).distinct().toList();
        if (!replyIds.isEmpty()) {
            jdbc.query("""
                    select m.id, m.author_id as auid, u.display_name as an, m.content
                    from messages m join users u on u.id = m.author_id
                    where m.id in (:ids)
                    """, Map.of("ids", replyIds), (rs, i) -> {
                UUID rid = rs.getObject("id", UUID.class);
                String c = crypto.decrypt(rs.getString("content"));
                String snippet = c == null ? "" : (c.length() > 140 ? c.substring(0, 140) + "…" : c);
                replies.put(rid, new ReplyRef(rid, rs.getObject("auid", UUID.class), rs.getString("an"), snippet));
                return null;
            });
        }

        Map<UUID, List<ReactionDto>> reactions = new HashMap<>();
        jdbc.query("""
                select message_id, emoji, count(*) as cnt, bool_or(user_id = :me) as mine
                from message_reaction where message_id in (:ids)
                group by message_id, emoji order by cnt desc, emoji
                """, new MapSqlParameterSource().addValue("me", viewerId).addValue("ids", ids), (rs, i) -> {
            reactions.computeIfAbsent(rs.getObject("message_id", UUID.class), k -> new ArrayList<>())
                    .add(new ReactionDto(rs.getString("emoji"), rs.getInt("cnt"), rs.getBoolean("mine")));
            return null;
        });

        Map<UUID, List<UUID>> mentions = new HashMap<>();
        jdbc.query("select message_id, user_id from message_mention where message_id in (:ids)",
                Map.of("ids", ids), (rs, i) -> {
            mentions.computeIfAbsent(rs.getObject("message_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getObject("user_id", UUID.class));
            return null;
        });

        List<MessageDto> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(new MessageDto(r.id(), r.channelId(), r.authorId(), r.authorName(), crypto.decrypt(r.content()),
                    r.saved(), r.pinned(), Ids.timestampOf(r.id()), r.editedAt(),
                    byMessage.getOrDefault(r.id(), List.of()),
                    r.replyToId() != null ? replies.get(r.replyToId()) : null,
                    reactions.getOrDefault(r.id(), List.of()),
                    mentions.getOrDefault(r.id(), List.of())));
        }
        return out;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

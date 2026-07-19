package com.ephemeral.guild;

import com.ephemeral.dto.ChannelDto;
import com.ephemeral.dto.GuildDto;
import com.ephemeral.dto.MemberDto;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GuildService {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String CHANNEL_COLS =
            "id, guild_id, name, type, position, admin_only, topic, slow_mode_seconds, user_limit, retention_ms";
    private static final RowMapper<ChannelDto> CHANNEL_MAPPER = (rs, i) -> new ChannelDto(
            rs.getObject("id", UUID.class),
            rs.getObject("guild_id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getInt("position"),
            rs.getBoolean("admin_only"),
            rs.getString("topic"),
            rs.getInt("slow_mode_seconds"),
            rs.getInt("user_limit"),
            (Long) rs.getObject("retention_ms"));

    private final AuditService audit;

    public GuildService(NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Banned users cannot join or be added; checked on every entry path. */
    public boolean isBanned(UUID userId, UUID guildId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from guild_bans where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", userId), Integer.class);
        return n != null && n > 0;
    }

    // ---- roles & membership ----------------------------------------------

    public Optional<String> role(UUID userId, UUID guildId) {
        return jdbc.queryForList(
                "select role from memberships where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", userId), String.class).stream().findFirst();
    }

    public void requireMember(UUID userId, UUID guildId) {
        if (role(userId, guildId).isEmpty()) {
            throw ApiException.forbidden("not a member of this server");
        }
    }

    public boolean isMember(UUID userId, UUID guildId) {
        return role(userId, guildId).isPresent();
    }

    public boolean isAdmin(UUID userId, UUID guildId) {
        return role(userId, guildId).map("admin"::equals).orElse(false);
    }

    public void requireAdmin(UUID userId, UUID guildId) {
        if (!role(userId, guildId).map("admin"::equals).orElse(false)) {
            throw ApiException.forbidden("admin only");
        }
    }

    public UUID guildIdOfChannel(UUID channelId) {
        List<UUID> r = jdbc.queryForList("select guild_id from channels where id = :c",
                Map.of("c", channelId), UUID.class);
        if (r.isEmpty()) {
            throw ApiException.notFound("channel not found");
        }
        return r.get(0); // null for a DM channel (avoids Stream.findFirst NPE on null)
    }

    public String channelType(UUID channelId) {
        return jdbc.queryForList("select type from channels where id = :c",
                        Map.of("c", channelId), String.class).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("channel not found"));
    }

    /**
     * Ensures the user may access the channel (member of the server, and — for an
     * admin-only channel — an admin); returns the guild id. Enforced on every read,
     * send, react, pin, etc. via the message service.
     */
    public UUID requireChannelMember(UUID userId, UUID channelId) {
        var rows = jdbc.query("select guild_id, admin_only from channels where id = :c",
                Map.of("c", channelId),
                (rs, i) -> new Object[]{rs.getObject("guild_id", UUID.class), rs.getBoolean("admin_only")});
        if (rows.isEmpty()) {
            throw ApiException.notFound("channel not found");
        }
        UUID guildId = (UUID) rows.get(0)[0];
        boolean adminOnly = (boolean) rows.get(0)[1];
        if (guildId == null) { // DM channel — access is by the participant list
            if (!isDmMember(userId, channelId)) {
                throw ApiException.forbidden("not a participant in this conversation");
            }
            return null;
        }
        requireMember(userId, guildId);
        if (adminOnly && !isAdmin(userId, guildId)) {
            throw ApiException.forbidden("this channel is admin-only");
        }
        return guildId;
    }

    public boolean isDmMember(UUID userId, UUID channelId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from dm_members where channel_id = :c and user_id = :u",
                Map.of("c", channelId, "u", userId), Integer.class);
        return n != null && n > 0;
    }

    /** Participants of a DM channel (empty for guild channels). */
    public List<UUID> dmMemberIds(UUID channelId) {
        return jdbc.queryForList("select user_id from dm_members where channel_id = :c",
                Map.of("c", channelId), UUID.class);
    }

    public boolean isChannelMember(UUID userId, UUID channelId) {
        // DM channels: membership is the participant list, not a guild role
        List<UUID> g = jdbc.queryForList("select guild_id from channels where id = :c",
                Map.of("c", channelId), UUID.class);
        if (!g.isEmpty() && g.get(0) == null) {
            return isDmMember(userId, channelId);
        }
        var rows = jdbc.query("""
                select c.admin_only, m.role from channels c
                join memberships m on m.guild_id = c.guild_id and m.user_id = :u
                where c.id = :c
                """, Map.of("c", channelId, "u", userId),
                (rs, i) -> new Object[]{rs.getBoolean("admin_only"), rs.getString("role")});
        if (rows.isEmpty()) {
            return false;
        }
        boolean adminOnly = (boolean) rows.get(0)[0];
        return !adminOnly || "admin".equals(rows.get(0)[1]);
    }

    // ---- guilds -----------------------------------------------------------

    public GuildDto createGuild(UUID ownerId, String name) {
        UUID id = Ids.newId();
        jdbc.update("insert into guilds (id, name, owner_id) values (:id, :n, :o)",
                Map.of("id", id, "n", name.trim(), "o", ownerId));
        jdbc.update("insert into memberships (guild_id, user_id, role) values (:g, :u, 'admin')",
                Map.of("g", id, "u", ownerId));
        jdbc.update("""
                insert into channels (id, guild_id, name, type, position)
                values (:id, :g, 'general', 'text', 0)
                """, Map.of("id", Ids.newId(), "g", id));
        jdbc.update("""
                insert into channels (id, guild_id, name, type, position)
                values (:id, :g, 'Voice', 'voice', 1)
                """, Map.of("id", Ids.newId(), "g", id));
        audit.log(id, ownerId, "guild.create", null, name.trim());
        return buildGuild(id);
    }

    public GuildDto buildGuild(UUID guildId) {
        return buildGuild(guildId, true); // internal callers see every channel
    }

    /** Build a guild, hiding admin-only channels from non-admin viewers. */
    public GuildDto buildGuild(UUID guildId, boolean includeAdminOnly) {
        var head = jdbc.query("select id, name, owner_id, icon_id from guilds where id = :id",
                Map.of("id", guildId),
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("owner_id", UUID.class), rs.getObject("icon_id", UUID.class)});
        if (head.isEmpty()) {
            throw ApiException.notFound("server not found");
        }
        Object[] g = head.get(0);
        String where = includeAdminOnly ? "" : " and admin_only = false";
        List<ChannelDto> channels = jdbc.query(
                "select " + CHANNEL_COLS + " from channels where guild_id = :id" + where + " order by position, name",
                Map.of("id", guildId), CHANNEL_MAPPER);
        UUID iconId = (UUID) g[3];
        List<GuildDto.EmojiDto> emoji = jdbc.query(
                "select id, name, attachment_id from guild_emoji where guild_id = :g order by name",
                Map.of("g", guildId), (rs, i) -> new GuildDto.EmojiDto(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        "/api/files/" + rs.getObject("attachment_id", UUID.class)));
        return new GuildDto((UUID) g[0], (String) g[1], (UUID) g[2],
                iconId == null ? null : "/api/files/" + iconId, channels, emoji);
    }

    private static final java.util.regex.Pattern EMOJI_NAME = java.util.regex.Pattern.compile("[a-z0-9_]{2,30}");

    /** Add a custom emoji (admin): the caller's own fresh image upload, named :name:. */
    public GuildDto.EmojiDto addEmoji(UUID userId, UUID guildId, String name, UUID attachmentId) {
        requireAdmin(userId, guildId);
        String n = name == null ? "" : name.trim().toLowerCase().replaceAll("^:|:$", "");
        if (!EMOJI_NAME.matcher(n).matches()) {
            throw ApiException.badRequest("emoji names are 2-30 chars: a-z, 0-9, _");
        }
        var rows = jdbc.query("""
                select owner_id, message_id, content_type, size_bytes from attachments where id = :a
                """, Map.of("a", attachmentId), (rs, i) -> new Object[]{
                rs.getObject("owner_id", UUID.class), rs.getObject("message_id", UUID.class),
                rs.getString("content_type"), rs.getLong("size_bytes")});
        if (rows.isEmpty() || !userId.equals(rows.get(0)[0]) || rows.get(0)[1] != null) {
            throw ApiException.badRequest("upload the image first, then name it");
        }
        String ct = (String) rows.get(0)[2];
        if (ct == null || !ct.startsWith("image/")) {
            throw ApiException.badRequest("custom emoji must be an image");
        }
        if ((long) rows.get(0)[3] > 512_000L) {
            throw ApiException.badRequest("custom emoji are capped at 500 KB");
        }
        Integer dup = jdbc.queryForObject(
                "select count(*) from guild_emoji where guild_id = :g and name = :n",
                Map.of("g", guildId, "n", n), Integer.class);
        if (dup != null && dup > 0) {
            throw ApiException.conflict(":" + n + ": already exists on this server");
        }
        UUID id = Ids.newId();
        jdbc.update("insert into guild_emoji (id, guild_id, name, attachment_id) values (:id, :g, :n, :a)",
                Map.of("id", id, "g", guildId, "n", n, "a", attachmentId));
        audit.log(guildId, userId, "emoji.create", null, ":" + n + ":");
        return new GuildDto.EmojiDto(id, n, "/api/files/" + attachmentId);
    }

    /** Remove a custom emoji (admin). The image row/blob becomes an orphan for the sweep. */
    public void deleteEmoji(UUID userId, UUID guildId, UUID emojiId) {
        requireAdmin(userId, guildId);
        var rows = jdbc.query("select name from guild_emoji where id = :e and guild_id = :g",
                Map.of("e", emojiId, "g", guildId), (rs, i) -> rs.getString("name"));
        if (rows.isEmpty()) {
            throw ApiException.notFound("emoji not found");
        }
        jdbc.update("delete from guild_emoji where id = :e", Map.of("e", emojiId));
        audit.log(guildId, userId, "emoji.delete", null, ":" + rows.get(0) + ":");
    }

    /**
     * Set (or clear, with null) the server's custom icon. The attachment must be
     * the caller's own fresh image upload — never someone else's or a message's.
     */
    public GuildDto setIcon(UUID userId, UUID guildId, UUID attachmentId) {
        requireAdmin(userId, guildId);
        if (attachmentId != null) {
            var rows = jdbc.query("""
                    select owner_id, message_id, content_type, size_bytes
                    from attachments where id = :a
                    """, Map.of("a", attachmentId), (rs, i) -> new Object[]{
                    rs.getObject("owner_id", UUID.class), rs.getObject("message_id", UUID.class),
                    rs.getString("content_type"), rs.getLong("size_bytes")});
            if (rows.isEmpty() || !userId.equals(rows.get(0)[0]) || rows.get(0)[1] != null) {
                throw ApiException.badRequest("upload the icon first, then set it");
            }
            String ct = (String) rows.get(0)[2];
            if (ct == null || !ct.startsWith("image/")) {
                throw ApiException.badRequest("the icon must be an image");
            }
            if ((long) rows.get(0)[3] > 2_000_000L) {
                throw ApiException.badRequest("icons are capped at 2 MB");
            }
        }
        jdbc.update("update guilds set icon_id = :a where id = :g",
                new MapSqlParameterSource().addValue("a", attachmentId).addValue("g", guildId));
        audit.log(guildId, userId, "guild.icon", null, attachmentId == null ? "removed" : "changed");
        return buildGuild(guildId);
    }

    private GuildDto buildGuildFor(UUID userId, UUID guildId) {
        return buildGuild(guildId, isAdmin(userId, guildId));
    }

    public List<GuildDto> listMyGuilds(UUID userId) {
        List<UUID> ids = jdbc.queryForList(
                "select guild_id from memberships where user_id = :u",
                Map.of("u", userId), UUID.class);
        return ids.stream().map(id -> buildGuildFor(userId, id)).toList();
    }

    public List<GuildDto> listAllGuilds() {
        List<UUID> ids = jdbc.queryForList("select id from guilds order by name", Map.of(), UUID.class);
        return ids.stream().map(id -> buildGuild(id, false)).toList(); // discovery: public channels only
    }

    public GuildDto getGuild(UUID userId, UUID guildId) {
        requireMember(userId, guildId);
        return buildGuildFor(userId, guildId);
    }

    public GuildDto joinGuild(UUID userId, UUID guildId) {
        Integer exists = jdbc.queryForObject("select count(*) from guilds where id = :g",
                Map.of("g", guildId), Integer.class);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("server not found");
        }
        if (isBanned(userId, guildId)) {
            throw ApiException.forbidden("you are banned from this server");
        }
        int inserted = jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", userId));
        if (inserted > 0) {
            audit.log(guildId, userId, "member.join", userId, null);
        }
        return buildGuildFor(userId, guildId);
    }

    // ---- channels ---------------------------------------------------------

    public ChannelDto createChannel(UUID userId, UUID guildId, String name, String type, boolean adminOnly) {
        requireAdmin(userId, guildId);
        if (!type.equals("text") && !type.equals("voice")) {
            throw ApiException.badRequest("type must be 'text' or 'voice'");
        }
        UUID id = Ids.newId();
        Integer nextPos = jdbc.queryForObject(
                "select coalesce(max(position), -1) + 1 from channels where guild_id = :g",
                Map.of("g", guildId), Integer.class);
        jdbc.update("""
                insert into channels (id, guild_id, name, type, position, admin_only)
                values (:id, :g, :n, :t, :p, :ao)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("g", guildId).addValue("n", name.trim())
                .addValue("t", type).addValue("p", nextPos).addValue("ao", adminOnly));
        audit.log(guildId, userId, "channel.create", null, type + " #" + name.trim());
        return channelById(id);
    }

    /** Update a channel's editable settings (admin only). Null fields are left unchanged. */
    public ChannelDto updateChannel(UUID userId, UUID channelId, String name, String topic,
                                    Integer slowModeSeconds, Integer userLimit, Long retentionMs) {
        UUID guildId = guildIdOfChannel(channelId);
        requireAdmin(userId, guildId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", channelId);
        java.util.List<String> sets = new java.util.ArrayList<>();
        if (retentionMs != null) {
            // 0 = back to the instance default; else clamp to 1 minute .. 30 days
            sets.add("retention_ms = :ret");
            p.addValue("ret", retentionMs == 0 ? null
                    : Math.max(60_000L, Math.min(30L * 86_400_000L, retentionMs)));
        }
        if (name != null) {
            String n = name.trim();
            if (n.isEmpty() || n.length() > 100) {
                throw ApiException.badRequest("channel name must be 1–100 characters");
            }
            sets.add("name = :n");
            p.addValue("n", n);
        }
        if (topic != null) {
            String t = topic.trim();
            if (t.length() > 1024) {
                throw ApiException.badRequest("topic must be at most 1024 characters");
            }
            sets.add("topic = :topic");
            p.addValue("topic", t.isEmpty() ? null : t);
        }
        if (slowModeSeconds != null) {
            sets.add("slow_mode_seconds = :sm");
            p.addValue("sm", Math.max(0, Math.min(21600, slowModeSeconds))); // cap at 6h
        }
        if (userLimit != null) {
            sets.add("user_limit = :ul");
            p.addValue("ul", Math.max(0, Math.min(99, userLimit)));
        }
        if (!sets.isEmpty()) {
            jdbc.update("update channels set " + String.join(", ", sets) + " where id = :c", p);
            ChannelDto c = channelById(channelId);
            audit.log(guildId, userId, "channel.update", null,
                    "#" + c.name() + ": " + String.join(", ", sets).replaceAll(" = :\\w+", ""));
            return c;
        }
        return channelById(channelId);
    }

    public ChannelDto renameChannel(UUID userId, UUID channelId, String name) {
        return updateChannel(userId, channelId, name, null, null, null, null);
    }

    /** Toggle a channel's admin-only visibility. */
    public ChannelDto setChannelAdminOnly(UUID userId, UUID channelId, boolean adminOnly) {
        UUID guildId = guildIdOfChannel(channelId);
        requireAdmin(userId, guildId);
        jdbc.update("update channels set admin_only = :ao where id = :c",
                Map.of("ao", adminOnly, "c", channelId));
        ChannelDto c = channelById(channelId);
        audit.log(guildId, userId, "channel.update", null,
                "#" + c.name() + ": " + (adminOnly ? "admin-only" : "public"));
        return c;
    }

    /** For slow-mode enforcement. */
    public int slowModeOf(UUID channelId) {
        List<Integer> r = jdbc.queryForList("select slow_mode_seconds from channels where id = :c",
                Map.of("c", channelId), Integer.class);
        return r.isEmpty() ? 0 : r.get(0);
    }

    /** For voice user-limit enforcement (0 = unlimited). */
    public int userLimitOf(UUID channelId) {
        List<Integer> r = jdbc.queryForList("select user_limit from channels where id = :c",
                Map.of("c", channelId), Integer.class);
        return r.isEmpty() ? 0 : r.get(0);
    }

    private ChannelDto channelById(UUID channelId) {
        return jdbc.queryForObject(
                "select " + CHANNEL_COLS + " from channels where id = :c",
                Map.of("c", channelId), CHANNEL_MAPPER);
    }

    public void deleteChannel(UUID userId, UUID channelId) {
        UUID guildId = guildIdOfChannel(channelId);
        requireAdmin(userId, guildId);
        String name = jdbc.queryForObject("select name from channels where id = :c",
                Map.of("c", channelId), String.class);
        jdbc.update("delete from channels where id = :c", Map.of("c", channelId));
        audit.log(guildId, userId, "channel.delete", null, "#" + name);
    }

    public GuildDto renameGuild(UUID userId, UUID guildId, String name) {
        requireAdmin(userId, guildId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw ApiException.badRequest("server name must be 1–100 characters");
        }
        jdbc.update("update guilds set name = :n where id = :g", Map.of("n", n, "g", guildId));
        audit.log(guildId, userId, "guild.rename", null, n);
        return buildGuild(guildId);
    }

    /** Leave a server. The owner cannot leave (they must delete it instead). */
    public void leaveGuild(UUID userId, UUID guildId) {
        requireMember(userId, guildId);
        UUID owner = jdbc.queryForObject("select owner_id from guilds where id = :g",
                Map.of("g", guildId), UUID.class);
        if (userId.equals(owner)) {
            throw ApiException.badRequest("the owner cannot leave; delete the server instead");
        }
        jdbc.update("delete from memberships where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", userId));
        audit.log(guildId, userId, "member.leave", userId, null);
    }

    /** Delete a whole server (owner only). Channels, memberships and messages cascade. */
    public void deleteGuild(UUID userId, UUID guildId) {
        UUID owner = jdbc.queryForList("select owner_id from guilds where id = :g",
                Map.of("g", guildId), UUID.class).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("server not found"));
        if (!userId.equals(owner)) {
            throw ApiException.forbidden("only the owner can delete this server");
        }
        jdbc.update("delete from guilds where id = :g", Map.of("g", guildId));
    }

    // ---- members ----------------------------------------------------------

    public List<MemberDto> listMembers(UUID userId, UUID guildId) {
        requireMember(userId, guildId);
        return jdbc.query("""
                select u.id as uid, u.username, u.display_name, m.role, u.avatar_id
                from memberships m join users u on u.id = m.user_id
                where m.guild_id = :g order by m.role, u.username
                """, Map.of("g", guildId),
                (rs, i) -> new MemberDto(rs.getObject("uid", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role"),
                        rs.getObject("avatar_id", UUID.class) == null ? null : "/api/files/" + rs.getObject("avatar_id", UUID.class)));
    }

    public MemberDto addMember(UUID userId, UUID guildId, String username) {
        requireAdmin(userId, guildId);
        var found = jdbc.query("select id, display_name from users where username = :u",
                Map.of("u", username.trim().toLowerCase()),
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("display_name")});
        if (found.isEmpty()) {
            throw ApiException.notFound("no such user");
        }
        UUID targetId = (UUID) found.get(0)[0];
        if (isBanned(targetId, guildId)) {
            throw ApiException.badRequest("that user is banned from this server (unban them first)");
        }
        int inserted = jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", targetId));
        if (inserted > 0) {
            audit.log(guildId, userId, "member.add", targetId, null);
        }
        return new MemberDto(targetId, username.trim().toLowerCase(), (String) found.get(0)[1], "member", null);
    }

    public void setRole(UUID userId, UUID guildId, UUID targetUserId, String newRole) {
        requireAdmin(userId, guildId);
        if (!newRole.equals("admin") && !newRole.equals("member")) {
            throw ApiException.badRequest("role must be 'admin' or 'member'");
        }
        int n = jdbc.update("update memberships set role = :r where guild_id = :g and user_id = :u",
                Map.of("r", newRole, "g", guildId, "u", targetUserId));
        if (n == 0) {
            throw ApiException.notFound("member not found");
        }
        audit.log(guildId, userId, "member.role", targetUserId, newRole);
    }

    public void kick(UUID userId, UUID guildId, UUID targetUserId) {
        requireAdmin(userId, guildId);
        UUID owner = jdbc.queryForObject("select owner_id from guilds where id = :g",
                Map.of("g", guildId), UUID.class);
        if (targetUserId.equals(owner)) {
            throw ApiException.badRequest("cannot remove the server owner");
        }
        jdbc.update("delete from memberships where guild_id = :g and user_id = :u",
                Map.of("g", guildId, "u", targetUserId));
        audit.log(guildId, userId, "member.kick", targetUserId, null);
    }
}

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
            "id, guild_id, name, type, position, admin_only, topic, slow_mode_seconds, user_limit";
    private static final RowMapper<ChannelDto> CHANNEL_MAPPER = (rs, i) -> new ChannelDto(
            rs.getObject("id", UUID.class),
            rs.getObject("guild_id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getInt("position"),
            rs.getBoolean("admin_only"),
            rs.getString("topic"),
            rs.getInt("slow_mode_seconds"),
            rs.getInt("user_limit"));

    public GuildService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        return jdbc.queryForList("select guild_id from channels where id = :c",
                        Map.of("c", channelId), UUID.class).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("channel not found"));
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
        requireMember(userId, guildId);
        if (adminOnly && !isAdmin(userId, guildId)) {
            throw ApiException.forbidden("this channel is admin-only");
        }
        return guildId;
    }

    public boolean isChannelMember(UUID userId, UUID channelId) {
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
        return buildGuild(id);
    }

    public GuildDto buildGuild(UUID guildId) {
        return buildGuild(guildId, true); // internal callers see every channel
    }

    /** Build a guild, hiding admin-only channels from non-admin viewers. */
    public GuildDto buildGuild(UUID guildId, boolean includeAdminOnly) {
        var head = jdbc.query("select id, name, owner_id from guilds where id = :id",
                Map.of("id", guildId),
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("owner_id", UUID.class)});
        if (head.isEmpty()) {
            throw ApiException.notFound("server not found");
        }
        Object[] g = head.get(0);
        String where = includeAdminOnly ? "" : " and admin_only = false";
        List<ChannelDto> channels = jdbc.query(
                "select " + CHANNEL_COLS + " from channels where guild_id = :id" + where + " order by position, name",
                Map.of("id", guildId), CHANNEL_MAPPER);
        return new GuildDto((UUID) g[0], (String) g[1], (UUID) g[2], channels);
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
        jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", userId));
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
        return channelById(id);
    }

    /** Update a channel's editable settings (admin only). Null fields are left unchanged. */
    public ChannelDto updateChannel(UUID userId, UUID channelId, String name, String topic,
                                    Integer slowModeSeconds, Integer userLimit) {
        UUID guildId = guildIdOfChannel(channelId);
        requireAdmin(userId, guildId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", channelId);
        java.util.List<String> sets = new java.util.ArrayList<>();
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
        }
        return channelById(channelId);
    }

    public ChannelDto renameChannel(UUID userId, UUID channelId, String name) {
        return updateChannel(userId, channelId, name, null, null, null);
    }

    /** Toggle a channel's admin-only visibility. */
    public ChannelDto setChannelAdminOnly(UUID userId, UUID channelId, boolean adminOnly) {
        UUID guildId = guildIdOfChannel(channelId);
        requireAdmin(userId, guildId);
        jdbc.update("update channels set admin_only = :ao where id = :c",
                Map.of("ao", adminOnly, "c", channelId));
        return channelById(channelId);
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
        jdbc.update("delete from channels where id = :c", Map.of("c", channelId));
    }

    public GuildDto renameGuild(UUID userId, UUID guildId, String name) {
        requireAdmin(userId, guildId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw ApiException.badRequest("server name must be 1–100 characters");
        }
        jdbc.update("update guilds set name = :n where id = :g", Map.of("n", n, "g", guildId));
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
                select u.id as uid, u.username, u.display_name, m.role
                from memberships m join users u on u.id = m.user_id
                where m.guild_id = :g order by m.role, u.username
                """, Map.of("g", guildId),
                (rs, i) -> new MemberDto(rs.getObject("uid", UUID.class), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("role")));
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
        jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", targetId));
        return new MemberDto(targetId, username.trim().toLowerCase(), (String) found.get(0)[1], "member");
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
    }
}

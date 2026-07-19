package com.ephemeral.storagechannel;

import com.ephemeral.dto.StorageItemDto;
import com.ephemeral.file.StorageService;
import com.ephemeral.guild.AuditService;
import com.ephemeral.guild.GuildService;
import com.ephemeral.realtime.RealtimeService;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storage channels: a folder tree of (encrypted) files inside a server.
 * Any member creates folders and uploads; owners delete their own items,
 * admins delete anything (audited). Files here are the app's one deliberate
 * exception to the 7-day vanish — keeping them is the channel's purpose.
 */
@Service
public class StorageItemService {

    private static final int MAX_DEPTH = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final GuildService guilds;
    private final StorageService storage;
    private final AuditService audit;
    private final RealtimeService realtime;

    public StorageItemService(NamedParameterJdbcTemplate jdbc, GuildService guilds,
                              StorageService storage, AuditService audit, RealtimeService realtime) {
        this.jdbc = jdbc;
        this.guilds = guilds;
        this.storage = storage;
        this.audit = audit;
        this.realtime = realtime;
    }

    /** Items directly inside a folder (or the channel root) — folders first, then by name. */
    public List<StorageItemDto> list(UUID userId, UUID channelId, UUID parentId) {
        requireStorageChannel(userId, channelId);
        if (parentId != null) {
            requireFolderInChannel(channelId, parentId);
        }
        return jdbc.query("""
                select s.id, s.parent_id, s.kind, s.name, s.owner_id, u.display_name as owner_name,
                       s.attachment_id, a.content_type, a.size_bytes
                from storage_items s
                left join users u on u.id = s.owner_id
                left join attachments a on a.id = s.attachment_id
                where s.channel_id = :c and s.parent_id is not distinct from :p
                order by s.kind desc, lower(s.name)
                """, new MapSqlParameterSource().addValue("c", channelId).addValue("p", parentId),
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID att = rs.getObject("attachment_id", UUID.class);
                    return new StorageItemDto(id, rs.getObject("parent_id", UUID.class),
                            rs.getString("kind"), rs.getString("name"),
                            rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                            att == null ? null : "/api/files/" + att,
                            rs.getString("content_type"), (Long) rs.getObject("size_bytes"),
                            Ids.timestampOf(id));
                });
    }

    public StorageItemDto createFolder(UUID userId, UUID channelId, UUID parentId, String name) {
        requireStorageChannel(userId, channelId);
        String n = cleanName(name);
        checkParent(channelId, parentId);
        UUID id = Ids.newId();
        jdbc.update("""
                insert into storage_items (id, channel_id, parent_id, owner_id, kind, name)
                values (:id, :c, :p, :o, 'folder', :n)
                """, new MapSqlParameterSource().addValue("id", id).addValue("c", channelId)
                .addValue("p", parentId).addValue("o", userId).addValue("n", n));
        realtime.storageUpdated(channelId);
        return one(id);
    }

    /** Bind the caller's own fresh upload as a file in the tree. */
    public StorageItemDto addFile(UUID userId, UUID channelId, UUID parentId, UUID attachmentId, String name) {
        requireStorageChannel(userId, channelId);
        checkParent(channelId, parentId);
        var rows = jdbc.query("select owner_id, message_id, filename from attachments where id = :a",
                Map.of("a", attachmentId), (rs, i) -> new Object[]{
                rs.getObject("owner_id", UUID.class), rs.getObject("message_id", UUID.class), rs.getString("filename")});
        if (rows.isEmpty() || !userId.equals(rows.get(0)[0]) || rows.get(0)[1] != null) {
            throw ApiException.badRequest("upload the file first, then add it");
        }
        Integer bound = jdbc.queryForObject("select count(*) from storage_items where attachment_id = :a",
                Map.of("a", attachmentId), Integer.class);
        if (bound != null && bound > 0) {
            throw ApiException.badRequest("that upload is already stored");
        }
        String n = cleanName(name == null || name.isBlank() ? (String) rows.get(0)[2] : name);
        UUID id = Ids.newId();
        jdbc.update("""
                insert into storage_items (id, channel_id, parent_id, owner_id, kind, name, attachment_id)
                values (:id, :c, :p, :o, 'file', :n, :a)
                """, new MapSqlParameterSource().addValue("id", id).addValue("c", channelId)
                .addValue("p", parentId).addValue("o", userId).addValue("n", n).addValue("a", attachmentId));
        realtime.storageUpdated(channelId);
        return one(id);
    }

    public StorageItemDto rename(UUID userId, UUID itemId, String name) {
        Item it = requireItem(itemId);
        requireOwnerOrAdmin(userId, it, "rename");
        jdbc.update("update storage_items set name = :n where id = :i",
                Map.of("n", cleanName(name), "i", itemId));
        realtime.storageUpdated(it.channelId());
        return one(itemId);
    }

    /** Delete an item — folders recursively. Owner or admin; admin deletes of others' items are audited. */
    @Transactional
    public void delete(UUID userId, UUID itemId) {
        Item it = requireItem(itemId);
        boolean admin = requireOwnerOrAdmin(userId, it, "delete");
        // collect every attachment in the subtree, then let the FK cascade fell the tree
        List<Object[]> atts = jdbc.query("""
                with recursive tree as (
                    select id, attachment_id from storage_items where id = :i
                    union all
                    select s.id, s.attachment_id from storage_items s join tree t on s.parent_id = t.id
                )
                select a.id, a.storage_key from tree join attachments a on a.id = tree.attachment_id
                """, Map.of("i", itemId), (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("storage_key")});
        jdbc.update("delete from storage_items where id = :i", Map.of("i", itemId));
        if (!atts.isEmpty()) {
            jdbc.update("delete from attachments where id in (:ids)",
                    new MapSqlParameterSource().addValue("ids", atts.stream().map(a -> (UUID) a[0]).toList()));
            storage.deleteAll(atts.stream().map(a -> (String) a[1]).toList());
        }
        if (admin && !userId.equals(it.ownerId())) {
            audit.log(guilds.guildIdOfChannel(it.channelId()), userId, "storage.delete", it.ownerId(),
                    it.kind() + " '" + it.name() + "'");
        }
        realtime.storageUpdated(it.channelId());
    }

    // ---- internals -----------------------------------------------------------

    private record Item(UUID id, UUID channelId, UUID ownerId, String kind, String name) {}

    private Item requireItem(UUID itemId) {
        List<Item> rows = jdbc.query("select id, channel_id, owner_id, kind, name from storage_items where id = :i",
                Map.of("i", itemId), (rs, i) -> new Item(rs.getObject("id", UUID.class),
                        rs.getObject("channel_id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getString("kind"), rs.getString("name")));
        if (rows.isEmpty()) {
            throw ApiException.notFound("not found");
        }
        return rows.get(0);
    }

    /** @return true when acting as admin rather than owner */
    private boolean requireOwnerOrAdmin(UUID userId, Item it, String verb) {
        UUID guildId = guilds.requireChannelMember(userId, it.channelId());
        boolean admin = guilds.isAdmin(userId, guildId);
        if (!admin && !userId.equals(it.ownerId())) {
            throw ApiException.forbidden("you can only " + verb + " your own files and folders");
        }
        return admin;
    }

    private void requireStorageChannel(UUID userId, UUID channelId) {
        guilds.requireChannelMember(userId, channelId);
        if (!"storage".equals(guilds.channelType(channelId))) {
            throw ApiException.badRequest("not a storage channel");
        }
    }

    private void requireFolderInChannel(UUID channelId, UUID folderId) {
        List<String> k = jdbc.queryForList(
                "select kind from storage_items where id = :i and channel_id = :c",
                Map.of("i", folderId, "c", channelId), String.class);
        if (k.isEmpty() || !"folder".equals(k.get(0))) {
            throw ApiException.badRequest("parent must be a folder in this channel");
        }
    }

    private void checkParent(UUID channelId, UUID parentId) {
        if (parentId == null) {
            return;
        }
        requireFolderInChannel(channelId, parentId);
        Integer depth = jdbc.queryForObject("""
                with recursive up as (
                    select id, parent_id, 1 as d from storage_items where id = :p
                    union all
                    select s.id, s.parent_id, up.d + 1 from storage_items s join up on s.id = up.parent_id
                )
                select max(d) from up
                """, Map.of("p", parentId), Integer.class);
        if (depth != null && depth >= MAX_DEPTH) {
            throw ApiException.badRequest("folders can nest at most " + MAX_DEPTH + " deep");
        }
    }

    private StorageItemDto one(UUID id) {
        return jdbc.query("""
                select s.id, s.parent_id, s.kind, s.name, s.owner_id, u.display_name as owner_name,
                       s.attachment_id, a.content_type, a.size_bytes
                from storage_items s
                left join users u on u.id = s.owner_id
                left join attachments a on a.id = s.attachment_id
                where s.id = :i
                """, Map.of("i", id), (rs, i) -> {
            UUID att = rs.getObject("attachment_id", UUID.class);
            return new StorageItemDto(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                    rs.getString("kind"), rs.getString("name"),
                    rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                    att == null ? null : "/api/files/" + att,
                    rs.getString("content_type"), (Long) rs.getObject("size_bytes"),
                    Ids.timestampOf(rs.getObject("id", UUID.class)));
        }).get(0);
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100 || n.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("names are 1-100 printable characters");
        }
        return n;
    }
}

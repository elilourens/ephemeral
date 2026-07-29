package com.ephemeral.realtime;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fan-out of realtime events to subscribed WebSocket sessions.
 * Single-node only by design — no Redis/bus. Replace with LISTEN/NOTIFY to scale out.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

    private final ObjectMapper mapper;
    private final com.ephemeral.guild.GuildService guilds;
    private final Map<UUID, Set<WebSocketSession>> channelSubs = new ConcurrentHashMap<>();
    private final Map<UUID, Set<WebSocketSession>> guildSubs = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    // channel -> guild and DM membership are immutable, so cache per broadcast.
    private final Map<UUID, UUID> channelGuild = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.List<UUID>> dmMembers = new ConcurrentHashMap<>();

    public RealtimeService(ObjectMapper mapper, com.ephemeral.guild.GuildService guilds) {
        this.mapper = mapper;
        this.guilds = guilds;
    }

    private UUID guildOf(UUID channelId) {
        return channelGuild.computeIfAbsent(channelId, guilds::guildIdOfChannel);
    }

    /** Track every open session, for broadcasts to all clients (e.g. voice presence). */
    public void register(WebSocketSession session) {
        sessions.add(session);
        if (session.getAttributes().get("user") instanceof AuthUser u) {
            userSessions.computeIfAbsent(u.id(), k -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> subsOf(WebSocketSession session) {
        return (Set<UUID>) session.getAttributes()
                .computeIfAbsent("subs", k -> ConcurrentHashMap.newKeySet());
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> guildSubsOf(WebSocketSession session) {
        return (Set<UUID>) session.getAttributes()
                .computeIfAbsent("guildSubs", k -> ConcurrentHashMap.newKeySet());
    }

    public void subscribe(WebSocketSession session, UUID channelId) {
        channelSubs.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(session);
        subsOf(session).add(channelId);
    }

    public void unsubscribe(WebSocketSession session, UUID channelId) {
        Set<WebSocketSession> set = channelSubs.get(channelId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                channelSubs.remove(channelId);
            }
        }
        subsOf(session).remove(channelId);
    }

    /** Subscribe a session to a whole guild — so it hears about messages in ALL its
     *  channels (needed for unread badges / mentions in channels you aren't viewing). */
    public void subscribeGuild(WebSocketSession session, UUID guildId) {
        guildSubs.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet()).add(session);
        guildSubsOf(session).add(guildId);
    }

    public void unsubscribeGuild(WebSocketSession session, UUID guildId) {
        Set<WebSocketSession> set = guildSubs.get(guildId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                guildSubs.remove(guildId);
            }
        }
        guildSubsOf(session).remove(guildId);
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        if (session.getAttributes().get("user") instanceof AuthUser u) {
            Set<WebSocketSession> mine = userSessions.get(u.id());
            if (mine != null) {
                mine.remove(session);
                if (mine.isEmpty()) {
                    userSessions.remove(u.id());
                }
            }
        }
        for (UUID channelId : subsOf(session)) {
            Set<WebSocketSession> set = channelSubs.get(channelId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    channelSubs.remove(channelId);
                }
            }
        }
        subsOf(session).clear();
        for (UUID guildId : guildSubsOf(session)) {
            Set<WebSocketSession> set = guildSubs.get(guildId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    guildSubs.remove(guildId);
                }
            }
        }
        guildSubsOf(session).clear();
    }

    // ---- outbound events --------------------------------------------------

    // Guild channels fan out to guild subscribers (so unread badges work in
    // channels you aren't viewing); DM channels fan out to every session of each
    // PARTICIPANT — no subscription needed, so the very first message of a brand
    // new conversation still reaches the other person live.
    private void broadcastMessageEvent(UUID channelId, Map<String, Object> envelope) {
        UUID g = guildOf(channelId);
        if (g != null) {
            broadcastGuild(g, envelope, null);
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("failed to serialize realtime event", e);
            return;
        }
        for (UUID member : dmMembers.computeIfAbsent(channelId, guilds::dmMemberIds)) {
            for (WebSocketSession s : userSessions.getOrDefault(member, Set.of())) {
                send(s, json);
            }
        }
    }

    public void messageCreated(MessageDto message) {
        broadcastMessageEvent(message.channelId(), Map.of("type", "message", "data", message));
    }

    public void messageDeleted(UUID channelId, UUID messageId) {
        broadcastMessageEvent(channelId, Map.of("type", "message_deleted",
                "data", Map.of("channelId", channelId, "messageId", messageId)));
    }

    public void messageUpdated(MessageDto message) {
        broadcastMessageEvent(message.channelId(), Map.of("type", "message_updated", "data", message));
    }

    public void typing(UUID channelId, AuthUser user, WebSocketSession except) {
        broadcast(channelId, Map.of("type", "typing",
                "data", Map.of("channelId", channelId, "userId", user.id(), "name", user.displayName())), except);
    }

    /** A storage channel's tree changed — open views refresh. */
    public void storageUpdated(UUID channelId) {
        broadcastGuild(guildOf(channelId), Map.of("type", "storage_updated",
                "data", Map.of("channelId", channelId)), null);
    }

    /** A DM conversation changed shape (created / member added / kicked / renamed / left). */
    public void dmUpdated(UUID channelId, java.util.Collection<UUID> memberIds) {
        // the participant set changed — drop the fan-out cache so a newly-added
        // member starts receiving live messages (and a kicked one stops)
        dmMembers.remove(channelId);
        sendToUsers(memberIds, Map.of("type", "dm_updated", "data", Map.of("channelId", channelId)));
    }

    /** Ring: someone started a call in a DM — tell the other participants. */
    public void dmCallStarted(UUID channelId, AuthUser caller, java.util.Collection<UUID> memberIds) {
        List<UUID> others = memberIds.stream().filter(m -> !m.equals(caller.id())).toList();
        sendToUsers(others, Map.of("type", "dm_call", "data", Map.of(
                "channelId", channelId, "fromId", caller.id(), "fromName", caller.displayName())));
    }

    /** Admin voice moderation: force-mute/deafen/disconnect a user's client in a call. */
    public void voiceForce(UUID targetUserId, UUID channelId, Map<String, Object> action) {
        Map<String, Object> data = new java.util.HashMap<>(action);
        data.put("channelId", channelId);
        sendToUsers(List.of(targetUserId), Map.of("type", "voice_force", "data", data));
    }

    /**
     * Voice-channel presence change. Guild channels go to ALL clients (the
     * sidebar shows who's in every call); DM calls are private — participants only.
     */
    public void voicePresence(UUID channelId, Object participants) {
        Map<String, Object> envelope = Map.of("type", "voice_presence",
                "data", Map.of("channelId", channelId, "participants", participants));
        if (guildOf(channelId) == null) {
            sendToUsers(guilds.dmMemberIds(channelId), envelope);
        } else {
            broadcastAll(envelope);
        }
    }

    private void sendToUsers(java.util.Collection<UUID> userIds, Map<String, Object> envelope) {
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("failed to serialize realtime event", e);
            return;
        }
        for (UUID u : userIds) {
            for (WebSocketSession s : userSessions.getOrDefault(u, Set.of())) {
                send(s, json);
            }
        }
    }

    /**
     * Something social changed for these users (friends / invites / join requests /
     * "you're in"). Clients refetch the affected list; {@code guildId} is context
     * for kinds that have one (nullable).
     */
    public void socialUpdate(java.util.Collection<UUID> userIds, String kind, UUID guildId) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("kind", kind);
        data.put("guildId", guildId);
        sendToUsers(userIds, Map.of("type", "social_update", "data", data));
    }

    /** The jukebox queue/now-playing changed — open panels refetch. */
    public void jukeboxUpdate(UUID channelId, UUID guildId) {
        Map<String, Object> envelope = Map.of("type", "jukebox_update",
                "data", Map.of("channelId", channelId));
        if (guildId != null) {
            broadcastGuild(guildId, envelope, null);
        } else {
            broadcastAll(envelope); // dismissal after removal: guild unknown, cheap enough
        }
    }

    /** Online/status/listening change for a user — sent to ALL clients. */
    public void presenceUpdate(UUID userId, boolean online, String status, String customStatus, String listening) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("userId", userId);
        data.put("online", online);
        data.put("status", status);
        data.put("customStatus", customStatus);
        data.put("listening", listening);
        broadcastAll(Map.of("type", "presence_update", "data", data));
    }

    /** Send a single envelope to one session (e.g. the presence snapshot on connect). */
    public void sendTo(WebSocketSession session, Map<String, Object> envelope) {
        try {
            send(session, mapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("failed to send to session {}", session.getId(), e);
        }
    }

    private void broadcastAll(Map<String, Object> envelope) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("failed to serialize broadcast", e);
            return;
        }
        for (WebSocketSession session : sessions) {
            send(session, json);
        }
    }

    private void broadcastGuild(UUID guildId, Map<String, Object> envelope, WebSocketSession except) {
        if (guildId == null) {
            return;
        }
        Set<WebSocketSession> sessions = guildSubs.get(guildId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("failed to serialize realtime event", e);
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session == except) {
                continue;
            }
            send(session, json);
        }
    }

    private void broadcast(UUID channelId, Map<String, Object> envelope, WebSocketSession except) {
        Set<WebSocketSession> sessions = channelSubs.get(channelId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("failed to serialize realtime event", e);
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session == except) {
                continue;
            }
            send(session, json);
        }
    }

    private void send(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.debug("failed to send to session {}", session.getId(), e);
        }
    }
}

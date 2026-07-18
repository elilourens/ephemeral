package com.ephemeral.realtime;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.dto.MessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

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
    // channel -> guild is immutable, so cache to avoid a DB hit per broadcast.
    private final Map<UUID, UUID> channelGuild = new ConcurrentHashMap<>();

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

    public void messageCreated(MessageDto message) {
        broadcastGuild(guildOf(message.channelId()), Map.of("type", "message", "data", message), null);
    }

    public void messageDeleted(UUID channelId, UUID messageId) {
        broadcastGuild(guildOf(channelId), Map.of("type", "message_deleted",
                "data", Map.of("channelId", channelId, "messageId", messageId)), null);
    }

    public void messageUpdated(MessageDto message) {
        broadcastGuild(guildOf(message.channelId()), Map.of("type", "message_updated", "data", message), null);
    }

    public void typing(UUID channelId, AuthUser user, WebSocketSession except) {
        broadcast(channelId, Map.of("type", "typing",
                "data", Map.of("channelId", channelId, "userId", user.id(), "name", user.displayName())), except);
    }

    /** Voice-channel presence change — sent to ALL clients so the sidebar updates. */
    public void voicePresence(UUID channelId, Object participants) {
        broadcastAll(Map.of("type", "voice_presence",
                "data", Map.of("channelId", channelId, "participants", participants)));
    }

    /** Online/status change for a user — sent to ALL clients. */
    public void presenceUpdate(UUID userId, boolean online, String status, String customStatus) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("userId", userId);
        data.put("online", online);
        data.put("status", status);
        data.put("customStatus", customStatus);
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

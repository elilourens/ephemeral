package com.ephemeral.realtime;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.JwtService;
import com.ephemeral.guild.GuildService;
import com.ephemeral.user.PresenceService;
import com.ephemeral.voice.VoicePresenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/** Authenticates the socket by JWT (query param), then relays subscribe/typing. */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final JwtService jwt;
    private final GuildService guilds;
    private final RealtimeService realtime;
    private final VoicePresenceService presence;
    private final PresenceService userPresence;
    private final ObjectMapper mapper;

    public ChatWebSocketHandler(JwtService jwt, GuildService guilds, RealtimeService realtime,
                                VoicePresenceService presence, PresenceService userPresence, ObjectMapper mapper) {
        this.jwt = jwt;
        this.guilds = guilds;
        this.realtime = realtime;
        this.presence = presence;
        this.userPresence = userPresence;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = session.getUri() == null ? null
                : UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("token");
        AuthUser user = null;
        if (token != null) {
            try {
                user = jwt.parse(token);
            } catch (Exception ignored) {
                // invalid
            }
        }
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.getAttributes().put("user", user);
        send(session, "{\"type\":\"ready\"}");                 // always the first frame
        realtime.register(session);
        realtime.sendTo(session, java.util.Map.of("type", "voice_presence_snapshot", "data", presence.snapshot()));
        realtime.sendTo(session, java.util.Map.of("type", "presence_snapshot", "data", userPresence.snapshot()));
        userPresence.connected(user.id());                     // broadcast presence last
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AuthUser user = (AuthUser) session.getAttributes().get("user");
        if (user == null) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "subscribe" -> {
                    UUID channelId = UUID.fromString(node.path("channelId").asText());
                    if (guilds.isChannelMember(user.id(), channelId)) {
                        realtime.subscribe(session, channelId);
                    }
                }
                case "unsubscribe" -> {
                    UUID channelId = UUID.fromString(node.path("channelId").asText());
                    realtime.unsubscribe(session, channelId);
                }
                case "subscribe_guild" -> {
                    UUID guildId = UUID.fromString(node.path("guildId").asText());
                    if (guilds.isMember(user.id(), guildId)) {
                        realtime.subscribeGuild(session, guildId);
                    }
                }
                case "unsubscribe_guild" -> {
                    UUID guildId = UUID.fromString(node.path("guildId").asText());
                    realtime.unsubscribeGuild(session, guildId);
                }
                case "typing" -> {
                    UUID channelId = UUID.fromString(node.path("channelId").asText());
                    if (guilds.isChannelMember(user.id(), channelId)) {
                        realtime.typing(channelId, user, session);
                    }
                }
                case "voice_state" -> {
                    UUID channelId = UUID.fromString(node.path("channelId").asText());
                    if (guilds.isChannelMember(user.id(), channelId)) {
                        presence.setState(channelId, user.id().toString(),
                                node.path("muted").asBoolean(false),
                                node.path("deafened").asBoolean(false),
                                node.path("screensharing").asBoolean(false));
                    }
                }
                default -> { /* ignore unknown */ }
            }
        } catch (Exception e) {
            log.debug("bad ws message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object u = session.getAttributes().get("user");
        if (u instanceof AuthUser au) {
            userPresence.disconnected(au.id());
        }
        realtime.removeSession(session);
    }

    private void send(WebSocketSession session, String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception ignored) {
        }
    }
}

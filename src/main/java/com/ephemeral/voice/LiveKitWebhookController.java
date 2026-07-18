package com.ephemeral.voice;

import com.ephemeral.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Receives LiveKit webhooks (participant_joined / participant_left / room_finished)
 * and updates voice presence. Authenticated by the JWT LiveKit signs with the
 * shared API secret. Public route (no bearer) — see AuthFilter.
 */
@RestController
public class LiveKitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookController.class);

    private final VoicePresenceService presence;
    private final ObjectMapper mapper;
    private final SecretKey key;

    public LiveKitWebhookController(VoicePresenceService presence, ObjectMapper mapper, AppProperties props) {
        this.presence = presence;
        this.mapper = mapper;
        this.key = Keys.hmacShaKeyFor(props.getLivekit().getApiSecret().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/livekit/webhook")
    public String webhook(HttpServletRequest req) {
        try {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                auth = auth.substring(7);
            }
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // authenticity: the header is a JWT LiveKit signed with the shared secret
            Jwts.parser().verifyWith(key).build().parseSignedClaims(auth);

            JsonNode ev = mapper.readTree(body);
            String event = ev.path("event").asText("");
            String room = ev.path("room").path("name").asText(null);
            JsonNode p = ev.path("participant");
            String identity = p.path("identity").asText(null);
            String name = p.path("name").asText(null);
            switch (event) {
                case "participant_joined" -> presence.joined(room, identity, name);
                case "participant_left" -> presence.left(room, identity);
                case "room_finished" -> presence.roomFinished(room);
                default -> { /* ignore other events */ }
            }
        } catch (Exception e) {
            log.debug("ignored livekit webhook", e);
        }
        return "ok";
    }
}

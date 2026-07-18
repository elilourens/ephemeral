package com.ephemeral.voice;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Mints LiveKit access tokens (HS256 JWT with a "video" grant claim), exactly the
 * shape Element Call's lk-jwt-service produces. Signed with the LiveKit API secret.
 */
@Service
public class LiveKitTokenService {

    private final AppProperties.LiveKit cfg;
    private final SecretKey key;

    public LiveKitTokenService(AppProperties props) {
        this.cfg = props.getLivekit();
        this.key = Keys.hmacShaKeyFor(cfg.getApiSecret().getBytes(StandardCharsets.UTF_8));
    }

    public record Token(String url, String token, String room, String identity) {
    }

    public Token mint(AuthUser user, String room, boolean admin) {
        Instant now = Instant.now();
        Map<String, Object> video = new HashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);
        if (admin) {
            video.put("roomAdmin", true);
            video.put("roomCreate", true);
        }
        String jwt = Jwts.builder()
                .issuer(cfg.getApiKey())
                .subject(user.id().toString())
                .claim("name", user.displayName())
                .claim("video", video)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plus(cfg.getTokenTtl())))
                .signWith(key)
                .compact();
        return new Token(cfg.getUrl(), jwt, room, user.id().toString());
    }
}

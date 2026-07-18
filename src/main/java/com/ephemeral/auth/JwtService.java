package com.ephemeral.auth;

import com.ephemeral.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Issues and verifies the app's own auth tokens (HS256). */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.getJwtTtl();
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("username", user.username())
                .claim("displayName", user.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public AuthUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return new AuthUser(
                UUID.fromString(c.getSubject()),
                c.get("username", String.class),
                c.get("displayName", String.class));
    }
}

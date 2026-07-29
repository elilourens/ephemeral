package com.ephemeral.spotify;

import com.ephemeral.config.AppProperties;
import com.ephemeral.crypto.CryptoService;
import com.ephemeral.user.PresenceService;
import com.ephemeral.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spotify "listening to" presence, entirely server-side (no desktop app):
 * Authorization-Code OAuth links an account (refresh token stored app-encrypted),
 * then a scheduled poll fetches currently-playing for ONLINE linked users and
 * feeds {@link PresenceService#setListening}. Nothing is persisted about what
 * anyone played — the listening line lives in memory and vanishes on restart,
 * consistent with the app's ephemerality.
 */
@Service
public class SpotifyService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyService.class);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties props;
    private final CryptoService crypto;
    private final PresenceService presence;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();

    /** Single-use OAuth state nonces -> (user, expiry); in-process like all session state here. */
    private record PendingState(UUID userId, Instant expires) {}
    private final Map<String, PendingState> pendingStates = new ConcurrentHashMap<>();

    /** Short-lived access tokens per user, refreshed from the stored refresh token. */
    private record AccessToken(String token, Instant expires) {}
    private final Map<UUID, AccessToken> accessTokens = new ConcurrentHashMap<>();

    /** App-level (client-credentials) token: catalog search + public playlists, no user. */
    private volatile AccessToken appToken;

    /** All scopes we ever need; changing this list means users must re-connect. */
    static final String SCOPES = "user-read-currently-playing user-read-playback-state "
            + "user-modify-playback-state playlist-read-private";

    public SpotifyService(NamedParameterJdbcTemplate jdbc, AppProperties props, CryptoService crypto,
                          PresenceService presence, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.props = props;
        this.crypto = crypto;
        this.presence = presence;
        this.mapper = mapper;
    }

    private AppProperties.Spotify cfg() {
        return props.getSpotify();
    }

    public boolean configured() {
        return !cfg().getClientId().isBlank() && !cfg().getClientSecret().isBlank()
                && !cfg().getRedirectUri().isBlank();
    }

    public boolean connected(UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from spotify_accounts where user_id = :u",
                Map.of("u", userId), Integer.class);
        return n != null && n > 0;
    }

    /** The accounts.spotify.com consent URL for this user, with a single-use state nonce. */
    public String connectUrl(UUID userId) {
        requireConfigured();
        pendingStates.values().removeIf(s -> s.expires().isBefore(Instant.now()));
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String state = HexFormat.of().formatHex(raw);
        pendingStates.put(state, new PendingState(userId, Instant.now().plus(STATE_TTL)));
        return cfg().getAuthBase() + "/authorize"
                + "?response_type=code"
                + "&client_id=" + url(cfg().getClientId())
                + "&scope=" + url(SCOPES)
                + "&redirect_uri=" + url(cfg().getRedirectUri())
                + "&state=" + state;
    }

    /** OAuth redirect target: swap the code for tokens and link the account. */
    public void handleCallback(String code, String state) {
        requireConfigured();
        PendingState pending = state == null ? null : pendingStates.remove(state);
        if (pending == null || pending.expires().isBefore(Instant.now())) {
            throw ApiException.badRequest("expired or unknown authorization attempt — try connecting again");
        }
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Spotify sent no authorization code");
        }
        JsonNode tokens = tokenRequest("grant_type=authorization_code"
                + "&code=" + url(code) + "&redirect_uri=" + url(cfg().getRedirectUri()));
        String refresh = tokens.path("refresh_token").asText("");
        if (refresh.isEmpty()) {
            throw ApiException.badRequest("Spotify returned no refresh token");
        }
        jdbc.update("""
                insert into spotify_accounts (user_id, refresh_token) values (:u, :t)
                on conflict (user_id) do update set refresh_token = excluded.refresh_token
                """, Map.of("u", pending.userId(), "t", crypto.encrypt(refresh)));
        cacheAccess(pending.userId(), tokens);
        pollUser(pending.userId()); // show the line immediately, not at the next tick
    }

    public void disconnect(UUID userId) {
        jdbc.update("delete from spotify_accounts where user_id = :u", Map.of("u", userId));
        accessTokens.remove(userId);
        presence.setListening(userId, null);
    }

    /** Scheduled: refresh currently-playing for everyone online AND linked. */
    public void pollOnce() {
        if (!configured()) {
            return;
        }
        var online = presence.onlineUsers();
        if (online.isEmpty()) {
            return;
        }
        List<UUID> linked = jdbc.queryForList("select user_id from spotify_accounts", Map.of(), UUID.class);
        for (UUID userId : linked) {
            if (online.contains(userId)) {
                pollUser(userId);
            }
        }
    }

    private void pollUser(UUID userId) {
        try {
            String token = accessToken(userId);
            if (token == null) {
                return;
            }
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                            URI.create(cfg().getApiBase() + "/v1/me/player/currently-playing"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) { // token aged out mid-window: refresh once next tick
                accessTokens.remove(userId);
                return;
            }
            String line = null;
            if (res.statusCode() == 200 && !res.body().isBlank()) {
                JsonNode n = mapper.readTree(res.body());
                JsonNode item = n.path("item");
                if (n.path("is_playing").asBoolean(false) && item.isObject()) {
                    StringBuilder artists = new StringBuilder();
                    for (JsonNode a : item.path("artists")) {
                        if (!artists.isEmpty()) {
                            artists.append(", ");
                        }
                        artists.append(a.path("name").asText());
                    }
                    line = item.path("name").asText("")
                            + (artists.isEmpty() ? "" : " — " + artists);
                }
            }
            presence.setListening(userId, line == null || line.isBlank() ? null : line);
        } catch (Exception e) {
            log.debug("spotify poll failed for {}", userId, e);
        }
    }

    private String accessToken(UUID userId) {
        AccessToken cached = accessTokens.get(userId);
        if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(15))) {
            return cached.token();
        }
        List<String> rows = jdbc.queryForList("select refresh_token from spotify_accounts where user_id = :u",
                Map.of("u", userId), String.class);
        if (rows.isEmpty()) {
            return null;
        }
        String refresh = crypto.decrypt(rows.get(0));
        try {
            JsonNode tokens = tokenRequest("grant_type=refresh_token&refresh_token=" + url(refresh));
            return cacheAccess(userId, tokens);
        } catch (Exception e) {
            log.debug("spotify token refresh failed for {} (revoked?)", userId);
            return null;
        }
    }

    private String cacheAccess(UUID userId, JsonNode tokens) {
        String access = tokens.path("access_token").asText("");
        long ttl = tokens.path("expires_in").asLong(3600);
        if (access.isEmpty()) {
            return null;
        }
        accessTokens.put(userId, new AccessToken(access, Instant.now().plusSeconds(ttl)));
        return access;
    }

    private JsonNode tokenRequest(String form) {
        String basic = Base64.getEncoder().encodeToString(
                (cfg().getClientId() + ":" + cfg().getClientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(cfg().getAuthBase() + "/api/token"))
                    .header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw ApiException.badRequest("Spotify rejected the request (" + res.statusCode() + ")");
            }
            return mapper.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("could not reach Spotify: " + e.getMessage());
        }
    }

    void requireConfigured() {
        if (!configured()) {
            throw ApiException.badRequest("Spotify isn't configured on this instance "
                    + "(set SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET / SPOTIFY_REDIRECT_URI)");
        }
    }

    // ---- catalog + playback control (used by the Jukebox) -------------------

    private String appAccessToken() {
        AccessToken cached = appToken;
        if (cached != null && cached.expires().isAfter(Instant.now().plusSeconds(15))) {
            return cached.token();
        }
        JsonNode tokens = tokenRequest("grant_type=client_credentials");
        String access = tokens.path("access_token").asText("");
        appToken = new AccessToken(access, Instant.now().plusSeconds(tokens.path("expires_in").asLong(3600)));
        return access;
    }

    private JsonNode apiGet(String token, String pathAndQuery) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                            URI.create(cfg().getApiBase() + pathAndQuery))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw ApiException.badRequest("Spotify said " + res.statusCode());
            }
            return mapper.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("could not reach Spotify: " + e.getMessage());
        }
    }

    /** One track as the jukebox knows it. */
    public record Track(String uri, String name, String artists, long durationMs, String imageUrl) {}

    private Track toTrack(JsonNode item) {
        StringBuilder artists = new StringBuilder();
        for (JsonNode a : item.path("artists")) {
            if (!artists.isEmpty()) {
                artists.append(", ");
            }
            artists.append(a.path("name").asText());
        }
        JsonNode images = item.path("album").path("images");
        String img = images.isArray() && images.size() > 0
                ? images.get(images.size() - 1).path("url").asText(null) : null;
        return new Track(item.path("uri").asText(), item.path("name").asText(""),
                artists.toString(), item.path("duration_ms").asLong(0), img);
    }

    /** A playlist search hit (queueing one expands it to its tracks). */
    public record PlaylistHit(String id, String name, String owner, int trackCount) {}

    public record SearchResults(List<Track> tracks, List<PlaylistHit> playlists) {}

    public SearchResults search(String query) {
        requireConfigured();
        JsonNode n = apiGet(appAccessToken(),
                "/v1/search?type=track,playlist&limit=8&q=" + url(query));
        List<Track> tracks = new java.util.ArrayList<>();
        for (JsonNode t : n.path("tracks").path("items")) {
            if (t.isObject()) {
                tracks.add(toTrack(t));
            }
        }
        List<PlaylistHit> playlists = new java.util.ArrayList<>();
        for (JsonNode p : n.path("playlists").path("items")) {
            if (p.isObject()) {
                playlists.add(new PlaylistHit(p.path("id").asText(), p.path("name").asText(""),
                        p.path("owner").path("display_name").asText(""),
                        p.path("tracks").path("total").asInt(0)));
            }
        }
        return new SearchResults(tracks, playlists);
    }

    /** The (first 100) tracks of a playlist, for queueing it wholesale. */
    public List<Track> playlistTracks(String playlistId) {
        requireConfigured();
        JsonNode n = apiGet(appAccessToken(),
                "/v1/playlists/" + url(playlistId) + "/tracks?limit=100");
        List<Track> out = new java.util.ArrayList<>();
        for (JsonNode it : n.path("items")) {
            JsonNode t = it.path("track");
            if (t.isObject() && !t.path("uri").asText("").isEmpty()) {
                out.add(toTrack(t));
            }
        }
        return out;
    }

    /**
     * Start (or reposition) a track on the user's active Spotify device.
     * Throws with a human explanation when there is no device / no Premium.
     */
    public void playOnUserDevice(UUID userId, String trackUri, long positionMs) {
        String token = accessToken(userId);
        if (token == null) {
            throw ApiException.badRequest("Spotify isn't connected");
        }
        playerPut(token, "/v1/me/player/play",
                "{\"uris\":[\"" + trackUri + "\"],\"position_ms\":" + Math.max(0, positionMs) + "}");
    }

    public void pauseUserDevice(UUID userId) {
        String token = accessToken(userId);
        if (token != null) {
            playerPut(token, "/v1/me/player/pause", "");
        }
    }

    private void playerPut(String token, String path, String body) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(cfg().getApiBase() + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                throw ApiException.badRequest("no active Spotify device — open Spotify and play anything once");
            }
            if (res.statusCode() == 403) {
                throw ApiException.badRequest("Spotify Premium is required to control playback");
            }
            if (res.statusCode() >= 400) {
                throw ApiException.badRequest("Spotify said " + res.statusCode());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("could not reach Spotify: " + e.getMessage());
        }
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

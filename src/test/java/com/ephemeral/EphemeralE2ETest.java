package com.ephemeral;

import com.ephemeral.file.StorageService;
import com.ephemeral.message.RetentionService;
import com.ephemeral.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests: real HTTP against the running app, backed by a real (embedded)
 * Postgres. Covers auth, guilds/roles, messaging, save, files, the retention purge,
 * live WebSocket delivery, and LiveKit token issuance. Uses the JDK HttpClient so
 * error statuses (401/403/404/409) are observed cleanly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EphemeralE2ETest {

    static EmbeddedPostgres PG;
    static com.sun.net.httpserver.HttpServer SPOTIFY_FIXTURE;
    static final List<String> SPOTIFY_PLAYER_CALLS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (PG == null) {
            PG = EmbeddedPostgres.builder().start();
        }
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // pin the feedback-inbox operator (the first-registered fallback is
        // nondeterministic here: tests share one DB and run in any order)
        registry.add("ephemeral.operator-username", () -> "opsboss");

        // a loopback stand-in for accounts.spotify.com + api.spotify.com
        if (SPOTIFY_FIXTURE == null) {
            SPOTIFY_FIXTURE = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            SPOTIFY_FIXTURE.createContext("/api/token", ex -> {
                byte[] body = """
                        {"access_token":"fixture-access","token_type":"Bearer","expires_in":3600,
                         "refresh_token":"fixture-refresh","scope":"user-read-currently-playing"}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SPOTIFY_FIXTURE.createContext("/v1/me/player/currently-playing", ex -> {
                byte[] body = """
                        {"is_playing":true,"item":{"name":"Weightless",
                         "artists":[{"name":"Marconi Union"}]}}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SPOTIFY_FIXTURE.createContext("/v1/search", ex -> {
                byte[] body = """
                        {"tracks":{"items":[{"uri":"spotify:track:tr1","name":"Test Song",
                           "artists":[{"name":"Tester"}],"duration_ms":60000,"album":{"images":[]}}]},
                         "playlists":{"items":[{"id":"pl1","name":"Chill",
                           "owner":{"display_name":"Op"},"tracks":{"total":2}}]}}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SPOTIFY_FIXTURE.createContext("/v1/playlists", ex -> {
                byte[] body = """
                        {"items":[
                          {"track":{"uri":"spotify:track:pla","name":"Alpha","artists":[{"name":"A"}],
                            "duration_ms":60000,"album":{"images":[]}}},
                          {"track":{"uri":"spotify:track:plb","name":"Beta","artists":[{"name":"B"}],
                            "duration_ms":60000,"album":{"images":[]}}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            SPOTIFY_FIXTURE.createContext("/v1/me/player/play", ex -> {
                SPOTIFY_PLAYER_CALLS.add("play:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            SPOTIFY_FIXTURE.createContext("/v1/me/player/pause", ex -> {
                SPOTIFY_PLAYER_CALLS.add("pause");
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            SPOTIFY_FIXTURE.start();
        }
        String spotifyBase = "http://127.0.0.1:" + SPOTIFY_FIXTURE.getAddress().getPort();
        registry.add("ephemeral.spotify.client-id", () -> "test-client");
        registry.add("ephemeral.spotify.client-secret", () -> "test-secret");
        registry.add("ephemeral.spotify.redirect-uri", () -> "http://localhost/api/spotify/callback");
        registry.add("ephemeral.spotify.auth-base", () -> spotifyBase);
        registry.add("ephemeral.spotify.api-base", () -> spotifyBase);
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    RetentionService retention;
    @Autowired
    NamedParameterJdbcTemplate jdbc;
    @Autowired
    StorageService storage;
    @Autowired
    com.ephemeral.voice.VoicePresenceService voicePresence;

    final HttpClient http = HttpClient.newHttpClient();

    // ---- helpers ----------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String uniqueName() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private HttpResponse<String> raw(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)));
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode call(String method, String path, String token, Object body, int expectStatus) throws Exception {
        HttpResponse<String> r = raw(method, path, token, body);
        assertThat(r.statusCode()).as("%s %s -> %s", method, path, r.body()).isEqualTo(expectStatus);
        String bod = r.body();
        return (bod == null || bod.isEmpty()) ? null : mapper.readTree(bod);
    }

    private record Session(String token, UUID userId) {
    }

    private Session register(String username) throws Exception {
        JsonNode n = call("POST", "/api/auth/register", null,
                Map.of("username", username, "password", "hunter2pw", "displayName", username), 200);
        return new Session(n.get("token").asText(), UUID.fromString(n.get("user").get("id").asText()));
    }

    /**
     * Test fixture: put a user straight into a guild. Joining via the API is
     * consent-based (invite-accept or request-approve) and covered by its own
     * tests; everything else just needs the membership row.
     */
    private void join(UUID guildId, Session member) {
        jdbc.update("""
                insert into memberships (guild_id, user_id, role) values (:g, :u, 'member')
                on conflict (guild_id, user_id) do nothing
                """, Map.of("g", guildId, "u", member.userId()));
    }

    private UUID channelOfType(JsonNode guild, String type) {
        for (JsonNode c : guild.get("channels")) {
            if (c.get("type").asText().equals(type)) {
                return UUID.fromString(c.get("id").asText());
            }
        }
        throw new AssertionError("no " + type + " channel");
    }

    private JsonNode jwtPayload(String jwt) throws Exception {
        String p = jwt.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(p));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    void authFlow() throws Exception {
        String name = uniqueName();
        Session s = register(name);
        assertThat(s.token()).isNotBlank();

        call("GET", "/api/guilds", null, null, 401);            // unauth
        call("POST", "/api/auth/register", null,
                Map.of("username", name, "password", "hunter2pw"), 409); // duplicate

        JsonNode me = call("GET", "/api/auth/me", s.token(), null, 200);
        assertThat(me.get("username").asText()).isEqualTo(name.toLowerCase());

        JsonNode login = call("POST", "/api/auth/login", null,
                Map.of("username", name, "password", "hunter2pw"), 200);
        assertThat(login.get("token").asText()).isNotBlank();
        call("POST", "/api/auth/login", null,
                Map.of("username", name, "password", "wrongpass"), 401);
    }

    @Test
    void guildRolesAndChannels() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());

        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "My Server"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        assertThat(guild.get("channels")).hasSize(2);

        join(gid, member);
        JsonNode members = call("GET", "/api/guilds/" + gid + "/members", admin.token(), null, 200);
        assertThat(members).hasSize(2);

        // member cannot create a channel; admin can
        call("POST", "/api/guilds/" + gid + "/channels", member.token(),
                Map.of("name", "nope", "type", "text"), 403);
        JsonNode chan = call("POST", "/api/guilds/" + gid + "/channels", admin.token(),
                Map.of("name", "random", "type", "text"), 200);
        assertThat(chan.get("name").asText()).isEqualTo("random");

        // promote member to admin, then they can create
        call("PUT", "/api/guilds/" + gid + "/members/" + member.userId() + "/role",
                admin.token(), Map.of("role", "admin"), 204);
        call("POST", "/api/guilds/" + gid + "/channels", member.token(),
                Map.of("name", "now-i-can", "type", "text"), 200);

        // non-member cannot read the guild
        Session outsider = register(uniqueName());
        call("GET", "/api/guilds/" + gid, outsider.token(), null, 403);
    }

    @Test
    void messagingSaveAndPagination() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Chat"), 200);
        UUID chan = channelOfType(guild, "text");

        UUID[] ids = new UUID[3];
        for (int i = 0; i < 3; i++) {
            JsonNode m = call("POST", "/api/channels/" + chan + "/messages", s.token(),
                    Map.of("content", "msg " + i), 200);
            ids[i] = UUID.fromString(m.get("id").asText());
            assertThat(m.get("createdAt").asText()).isNotBlank(); // derived from UUIDv7
            Thread.sleep(3);
        }

        // newest-first
        JsonNode page1 = call("GET", "/api/channels/" + chan + "/messages?limit=2", s.token(), null, 200);
        assertThat(page1).hasSize(2);
        assertThat(UUID.fromString(page1.get(0).get("id").asText())).isEqualTo(ids[2]);
        assertThat(UUID.fromString(page1.get(1).get("id").asText())).isEqualTo(ids[1]);

        // keyset: before the oldest we have
        JsonNode page2 = call("GET",
                "/api/channels/" + chan + "/messages?limit=2&before=" + ids[1], s.token(), null, 200);
        assertThat(page2).hasSize(1);
        assertThat(UUID.fromString(page2.get(0).get("id").asText())).isEqualTo(ids[0]);

        // save / unsave
        JsonNode saved = call("POST", "/api/messages/" + ids[0] + "/save", s.token(), null, 200);
        assertThat(saved.get("saved").asBoolean()).isTrue();
        JsonNode unsaved = call("DELETE", "/api/messages/" + ids[0] + "/save", s.token(), null, 200);
        assertThat(unsaved.get("saved").asBoolean()).isFalse();

        // delete
        call("DELETE", "/api/messages/" + ids[2], s.token(), null, 204);
        JsonNode after = call("GET", "/api/channels/" + chan + "/messages", s.token(), null, 200);
        assertThat(after).hasSize(2);
    }

    @Test
    void fileUploadAndSend() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Files"), 200);
        UUID chan = channelOfType(guild, "text");

        // multipart upload
        String boundary = "----ephemeralTestBoundary";
        String crlf = "\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"note.txt\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Type: text/plain" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes("hello attachment".getBytes(StandardCharsets.UTF_8));
        body.writeBytes((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(url("/api/uploads")))
                .header("Authorization", "Bearer " + s.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(up.statusCode()).isEqualTo(200);
        JsonNode att = mapper.readTree(up.body());
        UUID attId = UUID.fromString(att.get("id").asText());

        // send message referencing the upload
        JsonNode msg = call("POST", "/api/channels/" + chan + "/messages", s.token(),
                Map.of("content", "see attached", "attachmentIds", List.of(attId.toString())), 200);
        assertThat(msg.get("attachments")).hasSize(1);
        assertThat(msg.get("attachments").get(0).get("filename").asText()).isEqualTo("note.txt");

        // download is public and returns the bytes
        HttpResponse<String> dl = http.send(HttpRequest.newBuilder(URI.create(url("/api/files/" + attId))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(dl.statusCode()).isEqualTo(200);
        assertThat(dl.body()).isEqualTo("hello attachment");
    }

    @Test
    void retentionPurgesOldUnsavedMessagesAndTheirFiles() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Ephemeral"), 200);
        UUID chan = channelOfType(guild, "text");

        Instant eightDaysAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        UUID oldUnsaved = Ids.boundary(eightDaysAgo);
        UUID oldSaved = Ids.boundary(eightDaysAgo.plusMillis(1));
        insertMessage(oldUnsaved, chan, s.userId(), "ancient, will vanish", false);
        insertMessage(oldSaved, chan, s.userId(), "ancient, but SAVED", true);

        // an attachment bound to the doomed message
        UUID attId = Ids.newId();
        insertAttachment(attId, oldUnsaved, s.userId());

        // a fresh message that must survive
        JsonNode fresh = call("POST", "/api/channels/" + chan + "/messages", s.token(),
                Map.of("content", "fresh"), 200);
        UUID freshId = UUID.fromString(fresh.get("id").asText());

        int deleted = retention.purgeExpired();
        assertThat(deleted).isGreaterThanOrEqualTo(1);

        JsonNode remaining = call("GET", "/api/channels/" + chan + "/messages", s.token(), null, 200);
        List<String> ids = remaining.findValuesAsText("id");
        assertThat(ids).contains(oldSaved.toString(), freshId.toString());
        assertThat(ids).doesNotContain(oldUnsaved.toString());

        // the purged message's attachment row is gone (cascade) -> 404
        HttpResponse<String> dl = http.send(HttpRequest.newBuilder(URI.create(url("/api/files/" + attId))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(dl.statusCode()).isEqualTo(404);
    }

    @Test
    void websocketDeliversLiveMessages() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Live"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + s.token()),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                received.add(data.toString());
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        // wait for "ready", then subscribe. Message events fan out guild-wide (so
        // unread/mentions work for channels you aren't viewing); the channel sub is
        // only for typing. Mirror the real client: subscribe to both.
        String ready = received.poll(5, TimeUnit.SECONDS);
        assertThat(ready).contains("ready");
        ws.sendText("{\"type\":\"subscribe_guild\",\"guildId\":\"" + gid + "\"}", true).get(2, TimeUnit.SECONDS);
        ws.sendText("{\"type\":\"subscribe\",\"channelId\":\"" + chan + "\"}", true).get(2, TimeUnit.SECONDS);
        Thread.sleep(300); // let the subscription register

        call("POST", "/api/channels/" + chan + "/messages", s.token(),
                Map.of("content", "hello over the wire"), 200);

        String event = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String frame = received.poll(5, TimeUnit.SECONDS);
            if (frame != null && frame.contains("\"type\":\"message\"")) {
                event = frame;
                break;
            }
        }
        assertThat(event).as("expected a live message frame").isNotNull();
        assertThat(event).contains("hello over the wire");
        ws.abort();
    }

    @Test
    void readStateReactionsAndPins() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Feat"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, member);

        // read-state must not 500 (regression: Postgres has no max(uuid) aggregate)
        JsonNode empty = call("GET", "/api/guilds/" + gid + "/read-state", member.token(), null, 200);
        assertThat(empty.isArray()).isTrue();

        // admin @mentions the member -> member's mention_count for the channel is 1
        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "hey <@" + member.userId() + "> look here"), 200);
        JsonNode rs = call("GET", "/api/guilds/" + gid + "/read-state", member.token(), null, 200);
        JsonNode chanState = null;
        for (JsonNode n : rs) {
            if (n.get("channelId").asText().equals(chan.toString())) chanState = n;
        }
        assertThat(chanState).isNotNull();
        assertThat(chanState.get("mentionCount").asInt()).isEqualTo(1);
        assertThat(chanState.get("latestId").isNull()).isFalse();

        // member acks -> mention_count resets to 0
        call("POST", "/api/channels/" + chan + "/ack", member.token(),
                Map.of("lastReadId", chanState.get("latestId").asText()), 204);
        JsonNode rs2 = call("GET", "/api/guilds/" + gid + "/read-state", member.token(), null, 200);
        for (JsonNode n : rs2) {
            if (n.get("channelId").asText().equals(chan.toString())) {
                assertThat(n.get("mentionCount").asInt()).isEqualTo(0);
            }
        }

        // reactions toggle on/off
        JsonNode msg = call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "react to me"), 200);
        UUID mid = UUID.fromString(msg.get("id").asText());
        String fire = "🔥"; // exercises UTF-8 round-trip through JSON + Postgres text
        JsonNode reacted = call("POST", "/api/messages/" + mid + "/react", member.token(),
                Map.of("emoji", fire), 200);
        assertThat(reacted.get("reactions")).hasSize(1);
        assertThat(reacted.get("reactions").get(0).get("emoji").asText()).isEqualTo(fire);
        assertThat(reacted.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(reacted.get("reactions").get(0).get("mine").asBoolean()).isTrue();
        JsonNode unreacted = call("POST", "/api/messages/" + mid + "/react", member.token(),
                Map.of("emoji", fire), 200);
        assertThat(unreacted.get("reactions")).isEmpty();

        // pin / list pins / unpin
        JsonNode pinned = call("POST", "/api/messages/" + mid + "/pin", admin.token(), null, 200);
        assertThat(pinned.get("pinned").asBoolean()).isTrue();
        JsonNode pins = call("GET", "/api/channels/" + chan + "/pins", member.token(), null, 200);
        assertThat(pins.findValuesAsText("id")).contains(mid.toString());
        JsonNode unpinned = call("DELETE", "/api/messages/" + mid + "/pin", admin.token(), null, 200);
        assertThat(unpinned.get("pinned").asBoolean()).isFalse();

        // a member cannot pin someone else's message
        call("POST", "/api/messages/" + mid + "/pin", member.token(), null, 403);
    }

    @Test
    void renameLeaveAndDeleteServer() throws Exception {
        Session owner = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", owner.token(), Map.of("name", "Original"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, member);

        // rename server (admin/owner only)
        JsonNode renamed = call("PATCH", "/api/guilds/" + gid, owner.token(), Map.of("name", "Renamed HQ"), 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Renamed HQ");
        call("PATCH", "/api/guilds/" + gid, member.token(), Map.of("name", "Nope"), 403);

        // rename channel (admin only)
        JsonNode rc = call("PATCH", "/api/channels/" + chan, owner.token(), Map.of("name", "renamed-chan"), 200);
        assertThat(rc.get("name").asText()).isEqualTo("renamed-chan");
        call("PATCH", "/api/channels/" + chan, member.token(), Map.of("name", "nope"), 403);

        // owner cannot leave; member can
        call("POST", "/api/guilds/" + gid + "/leave", owner.token(), null, 400);
        call("POST", "/api/guilds/" + gid + "/leave", member.token(), null, 204);
        call("GET", "/api/guilds/" + gid, member.token(), null, 403); // no longer a member

        // a non-owner cannot delete; owner can (cascades channels + messages)
        Session other = register(uniqueName());
        join(gid, other);
        call("DELETE", "/api/guilds/" + gid, other.token(), null, 403);
        call("DELETE", "/api/guilds/" + gid, owner.token(), null, 204);
        call("GET", "/api/guilds/" + gid, owner.token(), null, 403); // gone
    }

    @Test
    void adminOnlyChannelsHiddenAndEnforced() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Locked"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, member);

        JsonNode secret = call("POST", "/api/guilds/" + gid + "/channels", admin.token(),
                Map.of("name", "staff", "type", "text", "adminOnly", true), 200);
        UUID secretId = UUID.fromString(secret.get("id").asText());
        assertThat(secret.get("adminOnly").asBoolean()).isTrue();

        // admin sees it in the guild; member does NOT
        JsonNode asAdmin = call("GET", "/api/guilds/" + gid, admin.token(), null, 200);
        assertThat(asAdmin.get("channels").findValuesAsText("id")).contains(secretId.toString());
        JsonNode asMember = call("GET", "/api/guilds/" + gid, member.token(), null, 200);
        assertThat(asMember.get("channels").findValuesAsText("id")).doesNotContain(secretId.toString());

        // member cannot read or post; admin can
        call("GET", "/api/channels/" + secretId + "/messages", member.token(), null, 403);
        call("POST", "/api/channels/" + secretId + "/messages", member.token(), Map.of("content", "hi"), 403);
        call("POST", "/api/channels/" + secretId + "/messages", admin.token(), Map.of("content", "staff only"), 200);

        // toggling it public makes it visible + usable to the member
        call("PUT", "/api/channels/" + secretId + "/admin-only", admin.token(), Map.of("adminOnly", false), 200);
        JsonNode nowMember = call("GET", "/api/guilds/" + gid, member.token(), null, 200);
        assertThat(nowMember.get("channels").findValuesAsText("id")).contains(secretId.toString());
        call("POST", "/api/channels/" + secretId + "/messages", member.token(), Map.of("content", "hello now"), 200);
        // a member can't flip the flag
        call("PUT", "/api/channels/" + secretId + "/admin-only", member.token(), Map.of("adminOnly", true), 403);
    }

    @Test
    void messageSearch() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Searchable"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, member);

        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "the quick brown fox jumps"), 200);
        JsonNode m2 = call("POST", "/api/channels/" + chan + "/messages", member.token(),
                Map.of("content", "lazy dogs sleep all afternoon"), 200);
        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "check https://example.com out"), 200);

        // free-text (websearch_to_tsquery + stemming: "jumps" matches "jumping" etc.)
        JsonNode r1 = call("GET", "/api/search?q=fox&guildId=" + gid, admin.token(), null, 200);
        assertThat(r1).hasSize(1);
        assertThat(r1.get(0).get("content").asText()).contains("brown fox");
        assertThat(r1.get(0).get("channelName").asText()).isEqualTo("general");

        // from: filter
        JsonNode r2 = call("GET", "/api/search?q=sleep&guildId=" + gid + "&authorId=" + member.userId(),
                admin.token(), null, 200);
        assertThat(r2).hasSize(1);
        assertThat(r2.get(0).get("id").asText()).isEqualTo(m2.get("id").asText());

        // has:link filter (no query text)
        JsonNode r3 = call("GET", "/api/search?guildId=" + gid + "&has=link", admin.token(), null, 200);
        assertThat(r3).hasSize(1);
        assertThat(r3.get(0).get("content").asText()).contains("example.com");

        // a non-member of the guild sees nothing
        Session outsider = register(uniqueName());
        JsonNode r4 = call("GET", "/api/search?q=fox&guildId=" + gid, outsider.token(), null, 200);
        assertThat(r4).isEmpty();

        // admin-only channels don't leak into a member's search
        JsonNode secret = call("POST", "/api/guilds/" + gid + "/channels", admin.token(),
                Map.of("name", "vault", "type", "text", "adminOnly", true), 200);
        UUID secretId = UUID.fromString(secret.get("id").asText());
        call("POST", "/api/channels/" + secretId + "/messages", admin.token(),
                Map.of("content", "topsecret password fox"), 200);
        JsonNode adminHits = call("GET", "/api/search?q=topsecret&guildId=" + gid, admin.token(), null, 200);
        assertThat(adminHits).hasSize(1);
        JsonNode memberHits = call("GET", "/api/search?q=topsecret&guildId=" + gid, member.token(), null, 200);
        assertThat(memberHits).isEmpty();
    }

    @Test
    void settingsPersistAndAccountDeletionRemovesEverything() throws Exception {
        Session user = register(uniqueName());
        Session friend = register(uniqueName());

        // settings roundtrip
        call("GET", "/api/users/me/settings", user.token(), null, 200); // defaults to {}
        call("PUT", "/api/users/me/settings", user.token(),
                Map.of("media", Map.of("hqAudio", true), "muted", Map.of()), 204);
        JsonNode s = call("GET", "/api/users/me/settings", user.token(), null, 200);
        assertThat(s.get("media").get("hqAudio").asBoolean()).isTrue();

        // user owns a guild with a message from a friend, and posts in the friend's guild
        JsonNode myGuild = call("POST", "/api/guilds", user.token(), Map.of("name", "Mine"), 200);
        UUID myGid = UUID.fromString(myGuild.get("id").asText());
        UUID myChan = channelOfType(myGuild, "text");
        join(myGid, friend);
        call("POST", "/api/channels/" + myChan + "/messages", friend.token(), Map.of("content", "hi in your server"), 200);

        JsonNode friendGuild = call("POST", "/api/guilds", friend.token(), Map.of("name", "Theirs"), 200);
        UUID friendGid = UUID.fromString(friendGuild.get("id").asText());
        UUID friendChan = channelOfType(friendGuild, "text");
        join(friendGid, user);
        JsonNode postedElsewhere = call("POST", "/api/channels/" + friendChan + "/messages", user.token(),
                Map.of("content", "my message in their server"), 200);
        UUID postedId = UUID.fromString(postedElsewhere.get("id").asText());

        // delete the account
        call("DELETE", "/api/users/me", user.token(), null, 204);

        // the owned guild is gone (friend can't fetch it); the user's message in the
        // friend's server is gone too; the friend's own message survived (their guild).
        call("GET", "/api/guilds/" + myGid, friend.token(), null, 403);
        JsonNode friendMsgs = call("GET", "/api/channels/" + friendChan + "/messages", friend.token(), null, 200);
        assertThat(friendMsgs.findValuesAsText("id")).doesNotContain(postedId.toString());
        // and the profile no longer exists
        call("GET", "/api/users/" + user.userId(), friend.token(), null, 404);
    }

    @Test
    void channelFeaturesTopicSlowModeAndTextInVoice() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Feat5"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        UUID voice = channelOfType(guild, "voice");
        join(gid, member);

        // update topic + slow mode + (voice) user limit — admin only
        JsonNode upd = call("PATCH", "/api/channels/" + chan, admin.token(),
                Map.of("topic", "welcome & rules", "slowModeSeconds", 5), 200);
        assertThat(upd.get("topic").asText()).isEqualTo("welcome & rules");
        assertThat(upd.get("slowModeSeconds").asInt()).isEqualTo(5);
        call("PATCH", "/api/channels/" + chan, member.token(), Map.of("topic", "nope"), 403);
        JsonNode vupd = call("PATCH", "/api/channels/" + voice, admin.token(), Map.of("userLimit", 3), 200);
        assertThat(vupd.get("userLimit").asInt()).isEqualTo(3);

        // slow mode: a member's 2nd quick post is rejected (429); admins are exempt
        call("POST", "/api/channels/" + chan + "/messages", member.token(), Map.of("content", "first"), 200);
        HttpResponse<String> tooFast = raw("POST", "/api/channels/" + chan + "/messages", member.token(),
                Map.of("content", "second too fast"));
        assertThat(tooFast.statusCode()).isEqualTo(429);
        call("POST", "/api/channels/" + chan + "/messages", admin.token(), Map.of("content", "a1"), 200);
        call("POST", "/api/channels/" + chan + "/messages", admin.token(), Map.of("content", "a2 (exempt)"), 200);

        // text-in-voice: messages can be posted to and listed from a voice channel
        JsonNode vm = call("POST", "/api/channels/" + voice + "/messages", member.token(),
                Map.of("content", "hello from the voice chat"), 200);
        JsonNode vlist = call("GET", "/api/channels/" + voice + "/messages", member.token(), null, 200);
        assertThat(vlist.findValuesAsText("id")).contains(vm.get("id").asText());

        // slow-mode cap: values are clamped to 0–21600
        JsonNode capped = call("PATCH", "/api/channels/" + chan, admin.token(),
                Map.of("slowModeSeconds", 999999), 200);
        assertThat(capped.get("slowModeSeconds").asInt()).isEqualTo(21600);
    }

    @Test
    void liveKitTokenGrantsMatchRole() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Voice"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID voice = channelOfType(guild, "voice");
        join(gid, member);

        JsonNode adminTok = call("POST", "/api/channels/" + voice + "/voice-token", admin.token(), null, 200);
        JsonNode ap = jwtPayload(adminTok.get("token").asText());
        assertThat(ap.get("iss").asText()).isEqualTo("devkey");
        assertThat(ap.get("video").get("roomJoin").asBoolean()).isTrue();
        assertThat(ap.get("video").get("roomAdmin").asBoolean()).isTrue();

        JsonNode memberTok = call("POST", "/api/channels/" + voice + "/voice-token", member.token(), null, 200);
        JsonNode mp = jwtPayload(memberTok.get("token").asText());
        assertThat(mp.get("video").get("roomJoin").asBoolean()).isTrue();
        assertThat(mp.get("video").has("roomAdmin")).isFalse();
    }

    @Test
    void adminCanDeleteAnyMessageButMemberCannot() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        Session carol = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Mod"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);
        join(gid, carol);

        UUID bobMsg = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                bob.token(), Map.of("content", "bob speaks"), 200).get("id").asText());

        call("DELETE", "/api/messages/" + bobMsg, carol.token(), null, 403);   // peer member: no
        UUID bobMsg2 = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                bob.token(), Map.of("content", "again"), 200).get("id").asText());
        call("DELETE", "/api/messages/" + bobMsg2, bob.token(), null, 204);     // own: yes
        call("DELETE", "/api/messages/" + bobMsg, admin.token(), null, 204);    // admin: yes
    }

    @Test
    void nonMemberIsBlockedFromChannel() throws Exception {
        Session admin = register(uniqueName());
        Session outsider = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Private"), 200);
        UUID text = channelOfType(guild, "text");
        UUID voice = channelOfType(guild, "voice");

        call("GET", "/api/channels/" + text + "/messages", outsider.token(), null, 403);
        call("POST", "/api/channels/" + text + "/messages", outsider.token(), Map.of("content", "sneak"), 403);
        call("POST", "/api/channels/" + voice + "/voice-token", outsider.token(), null, 403);
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        HttpResponse<String> r = raw("GET", "/api/auth/me", "garbage.token.value", null);
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void websocketRejectsInvalidToken() throws Exception {
        WsConn c = openWs("not-a-real-token");
        // handshake completes, then the server closes without ever sending "ready"
        assertThat(c.frames().poll(3, TimeUnit.SECONDS)).isNull();
        c.ws().abort();
    }

    @Test
    void websocketDoesNotLeakForeignChannelMessages() throws Exception {
        Session admin = register(uniqueName());
        Session outsider = register(uniqueName());   // never joins the guild
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Secret"), 200);
        UUID chan = channelOfType(guild, "text");

        WsConn c = openWs(outsider.token());
        assertThat(c.frames().poll(5, TimeUnit.SECONDS)).contains("ready");
        // try to subscribe to a channel we're not a member of -> server ignores it
        c.ws().sendText("{\"type\":\"subscribe\",\"channelId\":\"" + chan + "\"}", true).get(2, TimeUnit.SECONDS);
        Thread.sleep(300);
        call("POST", "/api/channels/" + chan + "/messages", admin.token(), Map.of("content", "top secret"), 200);

        // outsider must NOT receive the message
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            String frame = c.frames().poll(1, TimeUnit.SECONDS);
            assertThat(frame == null || !frame.contains("\"type\":\"message\"")).isTrue();
        }
        c.ws().abort();
    }

    @Test
    void onlyTheAuthorCanSaveTheirMessage() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Saves"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);

        UUID msg = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                admin.token(), Map.of("content", "keep me"), 200).get("id").asText());

        // privacy rule: nobody else may exempt YOUR words from the 7-day vanish
        call("POST", "/api/messages/" + msg + "/save", bob.token(), null, 403);
        assertThat(call("POST", "/api/messages/" + msg + "/save", admin.token(), null, 200)
                .get("saved").asBoolean()).isTrue();
        // author unsaves -> exemption lifts
        assertThat(call("DELETE", "/api/messages/" + msg + "/save", admin.token(), null, 200)
                .get("saved").asBoolean()).isFalse();
    }

    @Test
    void cannotBindAnotherUsersUpload() throws Exception {
        Session alice = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", alice.token(), Map.of("name", "Uploads"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);

        UUID aliceUpload = uploadFile(alice.token(), "alice.txt", "alice's file");
        // bob tries to attach alice's upload -> silently not bound (owner mismatch)
        JsonNode msg = call("POST", "/api/channels/" + chan + "/messages", bob.token(),
                Map.of("content", "not mine", "attachmentIds", List.of(aliceUpload.toString())), 200);
        assertThat(msg.get("attachments")).isEmpty();
    }

    @Test
    void kickRevokesAccess() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "KickTest"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);

        call("GET", "/api/guilds/" + gid, bob.token(), null, 200);                 // in
        call("POST", "/api/channels/" + chan + "/messages", bob.token(), Map.of("content", "hi"), 200);
        call("DELETE", "/api/guilds/" + gid + "/members/" + bob.userId(), admin.token(), null, 204);
        call("GET", "/api/guilds/" + gid, bob.token(), null, 403);                 // out
        call("POST", "/api/channels/" + chan + "/messages", bob.token(), Map.of("content", "back?"), 403);
    }

    @Test
    void orphanBlobsAreReconciled() throws Exception {
        Session s = register(uniqueName());
        UUID att = uploadFile(s.token(), "kept.txt", "keep me");
        java.nio.file.Path root = storage.root();
        java.nio.file.Path referenced = root.resolve(att.toString());
        assertThat(java.nio.file.Files.exists(referenced)).isTrue();

        // a rogue blob on disk with no attachment row, backdated past the grace window
        java.nio.file.Path orphan = root.resolve("orphan-" + UUID.randomUUID());
        java.nio.file.Files.write(orphan, "orphan".getBytes());
        java.nio.file.Files.setLastModifiedTime(orphan,
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(7200)));

        int removed = retention.reconcileOrphanBlobs(java.time.Duration.ofHours(1));
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(java.nio.file.Files.exists(orphan)).isFalse();     // orphan reconciled away
        assertThat(java.nio.file.Files.exists(referenced)).isTrue();  // referenced blob kept
    }

    // ---- websocket + upload helpers ---------------------------------------

    private record WsConn(WebSocket ws, BlockingQueue<String> frames) {
    }

    private WsConn openWs(String token) throws Exception {
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws" + (token == null ? "" : "?token=" + token)),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                q.add(data.toString());
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);
        return new WsConn(ws, q);
    }

    @Test
    void editHistoryIsKeptEncryptedAndScoped() throws Exception {
        Session s = register(uniqueName());
        Session outsider = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Hist"), 200);
        UUID chan = channelOfType(guild, "text");

        UUID msg = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                s.token(), Map.of("content", "draft one"), 200).get("id").asText());
        assertThat(call("GET", "/api/messages/" + msg + "/history", s.token(), null, 200)).isEmpty();

        call("PATCH", "/api/messages/" + msg, s.token(), Map.of("content", "draft two"), 200);
        call("PATCH", "/api/messages/" + msg, s.token(), Map.of("content", "final"), 200);

        JsonNode hist = call("GET", "/api/messages/" + msg + "/history", s.token(), null, 200);
        assertThat(hist).hasSize(2);
        assertThat(hist.get(0).get("content").asText()).isEqualTo("draft two"); // newest first
        assertThat(hist.get(1).get("content").asText()).isEqualTo("draft one");
        // stored encrypted, like the message itself
        assertThat(jdbc.queryForList("select prev_content from message_edits where message_id = :m",
                Map.of("m", msg), String.class)).allSatisfy(c -> assertThat(c).startsWith("enc:v1:"));
        // non-members can't read it; history dies with the message (cascade)
        call("GET", "/api/messages/" + msg + "/history", outsider.token(), null, 403);
        call("DELETE", "/api/messages/" + msg, s.token(), null, 204);
        assertThat(jdbc.queryForObject("select count(*) from message_edits where message_id = :m",
                Map.of("m", msg), Integer.class)).isZero();
    }

    @Test
    void customServerIcons() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Iconic"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, member);
        assertThat(guild.get("iconUrl").isNull()).isTrue();

        // a text upload is rejected; an image works (admin only)
        UUID txt = uploadFile(admin.token(), "notes.txt", "not an image");
        call("PUT", "/api/guilds/" + gid + "/icon", admin.token(), Map.of("attachmentId", txt), 400);
        UUID img = uploadImage(admin.token(), "icon.png");
        call("PUT", "/api/guilds/" + gid + "/icon", member.token(), Map.of("attachmentId", img), 403);
        JsonNode updated = call("PUT", "/api/guilds/" + gid + "/icon", admin.token(),
                Map.of("attachmentId", img), 200);
        assertThat(updated.get("iconUrl").asText()).isEqualTo("/api/files/" + img);
        // you can't hijack someone else's upload as an icon
        UUID theirs = uploadImage(member.token(), "sneaky.png");
        call("PUT", "/api/guilds/" + gid + "/icon", admin.token(), Map.of("attachmentId", theirs), 400);

        // an OLD icon survives the orphan-upload purge (referenced = exempt),
        // while an equally old unreferenced upload dies
        UUID oldIcon = Ids.boundary(Instant.now().minus(8, ChronoUnit.DAYS));
        UUID oldOrphan = Ids.boundary(Instant.now().minus(8, ChronoUnit.DAYS).plusMillis(1));
        insertAttachment(oldIcon, null, admin.userId());
        insertAttachment(oldOrphan, null, admin.userId());
        jdbc.update("update guilds set icon_id = :a where id = :g", Map.of("a", oldIcon, "g", gid));
        retention.purgeExpired();
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", oldIcon), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", oldOrphan), Integer.class)).isZero();

        // clearing reverts to initials
        assertThat(call("PUT", "/api/guilds/" + gid + "/icon", admin.token(),
                new java.util.HashMap<>() {{ put("attachmentId", null); }}, 200)
                .get("iconUrl").isNull()).isTrue();
    }

    private UUID uploadImage(String token, String filename) throws Exception {
        // tiny valid PNG header + payload is enough — the server checks content type
        String boundary = "----b" + System.nanoTime();
        String crlf = "\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Type: image/png" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3});
        body.writeBytes((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(url("/api/uploads")))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(up.statusCode()).isEqualTo(200);
        return UUID.fromString(mapper.readTree(up.body()).get("id").asText());
    }

    @Test
    void kickedUserCannotEditPinOrDeleteOldMessages() throws Exception {
        // Regression: edit/pin/delete only checked author identity, not current
        // membership — a kicked user with a live JWT could rewrite/broadcast into
        // a server they were removed from.
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Boot"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);
        UUID msg = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                bob.token(), Map.of("content", "mine"), 200).get("id").asText());

        call("DELETE", "/api/guilds/" + gid + "/members/" + bob.userId(), admin.token(), null, 204);
        // now removed — every mutation on the old message must 403, not succeed
        call("PATCH", "/api/messages/" + msg, bob.token(), Map.of("content", "rewritten after kick"), 403);
        call("POST", "/api/messages/" + msg + "/pin", bob.token(), null, 403);
        call("DELETE", "/api/messages/" + msg, bob.token(), null, 403);
    }

    @Test
    void groupDmMentionRegistersAgainstParticipants() throws Exception {
        // Regression: parseMentions resolved against memberships (null guild in a
        // DM) so DM @mentions never produced a mention_count / inbox entry.
        String bn = uniqueName(), dn = uniqueName();
        Session a = register(uniqueName());
        Session b = register(bn);
        register(dn);
        UUID chan = UUID.fromString(call("POST", "/api/dms", a.token(),
                Map.of("usernames", List.of(bn, dn), "name", "grp"), 200).get("channelId").asText());
        call("POST", "/api/channels/" + chan + "/messages", a.token(),
                Map.of("content", "ping <@" + b.userId() + ">"), 200);
        Integer mc = jdbc.queryForObject(
                "select coalesce(max(mention_count),0) from read_state where user_id = :u and channel_id = :c",
                Map.of("u", b.userId(), "c", chan), Integer.class);
        assertThat(mc).isEqualTo(1);
    }

    @Test
    void groupDmOwnershipTransfersWhenOwnerDeletesAccount() throws Exception {
        String bn = uniqueName(), dn = uniqueName();
        Session owner = register(uniqueName());
        Session b = register(bn);
        Session d = register(dn);
        UUID chan = UUID.fromString(call("POST", "/api/dms", owner.token(),
                Map.of("usernames", List.of(bn, dn), "name", "grp"), 200).get("channelId").asText());
        call("DELETE", "/api/users/me", owner.token(), null, 204);
        UUID newOwner = jdbc.queryForObject("select dm_owner_id from channels where id = :c",
                Map.of("c", chan), UUID.class);
        // ownership transfers to a surviving member instead of going null (which
        // would leave the group permanently unmoderatable)
        assertThat(newOwner).as("ownership must transfer, not go null").isNotNull();
        assertThat(List.of(b.userId(), d.userId())).contains(newOwner);
    }

    @Test
    void groupDmAddedMemberGetsLiveMessages() throws Exception {
        // Regression: the DM fan-out cache was never invalidated, so a member
        // added to a group AFTER its first message never received live messages.
        String bn = uniqueName(), dn = uniqueName(), cn = uniqueName();
        Session a = register(uniqueName());
        register(bn); register(dn);
        Session c = register(cn);

        UUID chan = UUID.fromString(call("POST", "/api/dms", a.token(),
                Map.of("usernames", List.of(bn, dn), "name", "grp"), 200).get("channelId").asText());
        // seed a message so the fan-out cache populates with the initial members
        call("POST", "/api/channels/" + chan + "/messages", a.token(), Map.of("content", "seed"), 200);

        // now add c, then connect c's socket and expect the next message live
        call("POST", "/api/dms/" + chan + "/members", a.token(), Map.of("username", cn), 200);

        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?token=" + c.token()),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket s, CharSequence d, boolean l) {
                                got.add(d.toString()); s.request(1); return null;
                            }
                        }).get(5, TimeUnit.SECONDS);
        assertThat(got.poll(5, TimeUnit.SECONDS)).contains("ready");
        Thread.sleep(300); // DM fan-out is user-session based; no explicit subscribe needed

        call("POST", "/api/channels/" + chan + "/messages", a.token(), Map.of("content", "welcome c"), 200);
        String event = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String f = got.poll(5, TimeUnit.SECONDS);
            if (f != null && f.contains("\"type\":\"message\"") && f.contains("welcome c")) { event = f; break; }
        }
        assertThat(event).as("added group-DM member must receive live messages").isNotNull();
        ws.abort();
    }

    @Test
    void orphanSweepRefusesToEraseAForeignStorageDir() throws Exception {
        // Simulate a second instance pointed at another app's uploads: files on
        // disk that THIS database doesn't know. The sweep must refuse (this
        // exact misconfiguration once deleted real user data).
        java.nio.file.Path root = storage.root();
        java.util.List<java.nio.file.Path> ghosts = new java.util.ArrayList<>();
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        for (int i = 0; i < 12; i++) {
            java.nio.file.Path p = root.resolve("ghost-" + UUID.randomUUID());
            java.nio.file.Files.writeString(p, "someone else's data " + i);
            java.nio.file.Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(old));
            ghosts.add(p);
        }
        try {
            // wipe rows so the DB looks like it owns nothing (fresh-instance case)
            jdbc.update("update guilds set icon_id = null", Map.of());
            jdbc.update("update users set avatar_id = null, banner_id = null", Map.of());
            jdbc.update("delete from guild_emoji", Map.of());
            jdbc.update("delete from storage_items", Map.of());
            jdbc.update("delete from attachments", Map.of());
            int removed = retention.reconcileOrphanBlobs(java.time.Duration.ofHours(1));
            assertThat(removed).isZero();
            for (java.nio.file.Path p : ghosts) {
                assertThat(java.nio.file.Files.exists(p)).as("blob %s must survive", p).isTrue();
            }
        } finally {
            for (java.nio.file.Path p : ghosts) java.nio.file.Files.deleteIfExists(p);
        }
    }

    @Test
    void storageChannelLifecycle() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        Session outsider = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Locker"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, bob);
        UUID chan = UUID.fromString(call("POST", "/api/guilds/" + gid + "/channels", admin.token(),
                Map.of("name", "vault", "type", "storage"), 200).get("id").asText());

        // no chatting in the locker; outsiders shut out
        call("POST", "/api/channels/" + chan + "/messages", admin.token(), Map.of("content", "hi"), 400);
        call("GET", "/api/channels/" + chan + "/storage", outsider.token(), null, 403);

        // bob (plain member) builds: folder at root, file inside it
        UUID folder = UUID.fromString(call("POST", "/api/channels/" + chan + "/storage/folders",
                bob.token(), Map.of("name", "memes"), 200).get("id").asText());
        UUID up = uploadFile(bob.token(), "dank.txt", "meme content");
        JsonNode file = call("POST", "/api/channels/" + chan + "/storage/files", bob.token(),
                Map.of("attachmentId", up, "parentId", folder), 200);
        assertThat(file.get("name").asText()).isEqualTo("dank.txt");
        assertThat(file.get("url").asText()).isEqualTo("/api/files/" + up);

        JsonNode root = call("GET", "/api/channels/" + chan + "/storage", admin.token(), null, 200);
        assertThat(root).hasSize(1);
        assertThat(root.get(0).get("kind").asText()).isEqualTo("folder");
        JsonNode inFolder = call("GET", "/api/channels/" + chan + "/storage?parent=" + folder,
                admin.token(), null, 200);
        assertThat(inFolder.get(0).get("ownerName").asText()).isNotBlank();

        // storage files DON'T vanish: an old bound file survives the purge
        UUID oldAtt = Ids.boundary(Instant.now().minus(8, ChronoUnit.DAYS));
        insertAttachment(oldAtt, null, bob.userId());
        jdbc.update("insert into storage_items (id, channel_id, parent_id, owner_id, kind, name, attachment_id)"
                        + " values (:id, :c, null, :o, 'file', 'keeper.txt', :a)",
                Map.of("id", Ids.newId(), "c", chan, "o", bob.userId(), "a", oldAtt));
        retention.purgeExpired();
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", oldAtt), Integer.class)).isEqualTo(1);

        // a third member can't touch bob's stuff; bob can't touch... nothing here of admin's
        Session carol = register(uniqueName());
        join(gid, carol);
        UUID fileId = UUID.fromString(file.get("id").asText());
        call("DELETE", "/api/storage-items/" + fileId, carol.token(), null, 403);
        // bob renames + deletes his own file — attachment row and blob go too
        call("PATCH", "/api/storage-items/" + fileId, bob.token(), Map.of("name", "dankest.txt"), 200);
        call("DELETE", "/api/storage-items/" + fileId, bob.token(), null, 200);
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", up), Integer.class)).isZero();

        // admin recursively deletes bob's folder tree (with a nested file) — audited
        UUID up2 = uploadFile(bob.token(), "nested.txt", "bye");
        call("POST", "/api/channels/" + chan + "/storage/files", bob.token(),
                Map.of("attachmentId", up2, "parentId", folder), 200);
        call("DELETE", "/api/storage-items/" + folder, admin.token(), null, 200);
        assertThat(jdbc.queryForObject("select count(*) from storage_items where channel_id = :c and id <> :k",
                new MapSqlParameterSource().addValue("c", chan).addValue("k", oldAtt), Integer.class))
                .isLessThanOrEqualTo(1); // only the backdated keeper remains
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", up2), Integer.class)).isZero();
        assertThat(call("GET", "/api/guilds/" + gid + "/audit-log", admin.token(), null, 200)
                .findValuesAsText("action")).contains("storage.delete");
    }

    @Test
    void profileAvatarBannerAndEmbed() throws Exception {
        Session s = register(uniqueName());
        Session other = register(uniqueName());
        UUID av = uploadImage(s.token(), "face.png");
        UUID bn = uploadImage(s.token(), "banner.png");

        // text upload rejected; someone else's upload rejected; bad embed rejected
        UUID txt = uploadFile(s.token(), "x.txt", "not an image");
        call("PATCH", "/api/users/me", s.token(), Map.of("avatarId", txt), 400);
        UUID foreign = uploadImage(other.token(), "their.png");
        call("PATCH", "/api/users/me", s.token(), Map.of("avatarId", foreign), 400);
        call("PATCH", "/api/users/me", s.token(), Map.of("profileEmbed", "javascript:alert(1)"), 400);

        JsonNode p = call("PATCH", "/api/users/me", s.token(), Map.of(
                "avatarId", av, "bannerId", bn,
                "profileEmbed", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"), 200);
        assertThat(p.get("avatarUrl").asText()).isEqualTo("/api/files/" + av);
        assertThat(p.get("bannerUrl").asText()).isEqualTo("/api/files/" + bn);
        assertThat(p.get("profileEmbed").asText()).contains("youtube.com");

        // avatars ride the member list; old referenced media survives the purge
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Facewall"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        JsonNode members = call("GET", "/api/guilds/" + gid + "/members", s.token(), null, 200);
        assertThat(members.get(0).get("avatarUrl").asText()).isEqualTo("/api/files/" + av);
        UUID oldAv = Ids.boundary(Instant.now().minus(8, ChronoUnit.DAYS));
        insertAttachment(oldAv, null, s.userId());
        jdbc.update("update users set avatar_id = :a where id = :u", Map.of("a", oldAv, "u", s.userId()));
        retention.purgeExpired();
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", oldAv), Integer.class)).isEqualTo(1);

        // clearing works
        assertThat(call("PATCH", "/api/users/me", s.token(), Map.of("clearAvatar", true, "clearBanner", true,
                "profileEmbed", ""), 200).get("avatarUrl").isNull()).isTrue();
    }

    @Test
    void customEmojiLifecycle() throws Exception {
        Session admin = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Emojihaus"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, member);

        UUID img = uploadImage(admin.token(), "blob.png");
        // members can't add; bad names rejected; text uploads rejected
        call("POST", "/api/guilds/" + gid + "/emoji", member.token(),
                Map.of("name", "party", "attachmentId", img), 403);
        call("POST", "/api/guilds/" + gid + "/emoji", admin.token(),
                Map.of("name", "Party Time!", "attachmentId", img), 400);
        UUID txt = uploadFile(admin.token(), "no.txt", "nope");
        call("POST", "/api/guilds/" + gid + "/emoji", admin.token(),
                Map.of("name", "nope", "attachmentId", txt), 400);

        JsonNode e = call("POST", "/api/guilds/" + gid + "/emoji", admin.token(),
                Map.of("name", ":party:", "attachmentId", img), 200);
        assertThat(e.get("name").asText()).isEqualTo("party");
        // duplicate name -> conflict
        UUID img2 = uploadImage(admin.token(), "blob2.png");
        call("POST", "/api/guilds/" + gid + "/emoji", admin.token(),
                Map.of("name", "party", "attachmentId", img2), 409);
        // exposed on the guild DTO for every member
        JsonNode gv = call("GET", "/api/guilds/" + gid, member.token(), null, 200);
        assertThat(gv.get("emoji").get(0).get("name").asText()).isEqualTo("party");

        // referenced emoji images survive the orphan purge even when old
        UUID oldEmoji = Ids.boundary(Instant.now().minus(8, ChronoUnit.DAYS));
        insertAttachment(oldEmoji, null, admin.userId());
        jdbc.update("insert into guild_emoji (id, guild_id, name, attachment_id) values (:id, :g, 'ancient', :a)",
                Map.of("id", Ids.newId(), "g", gid, "a", oldEmoji));
        retention.purgeExpired();
        assertThat(jdbc.queryForObject("select count(*) from attachments where id = :a",
                Map.of("a", oldEmoji), Integer.class)).isEqualTo(1);

        // delete (admin only) removes it from the set
        UUID eid = UUID.fromString(e.get("id").asText());
        call("DELETE", "/api/guilds/" + gid + "/emoji/" + eid, member.token(), null, 403);
        call("DELETE", "/api/guilds/" + gid + "/emoji/" + eid, admin.token(), null, 200);
        assertThat(call("GET", "/api/guilds/" + gid, admin.token(), null, 200)
                .get("emoji").findValuesAsText("name")).doesNotContain("party");
    }

    @Test
    void ghostTokensAreRejectedNot500() throws Exception {
        // a signed JWT whose user no longer exists (deleted account / reset DB)
        // must be a clean 401 — not an FK-violation 500 on the first write
        Session s = register(uniqueName());
        call("DELETE", "/api/users/me", s.token(), null, 204);
        call("GET", "/api/guilds", s.token(), null, 401);
        call("POST", "/api/guilds", s.token(), Map.of("name", "ghost"), 401);
    }

    @Test
    void groupDmLifecycle() throws Exception {
        String bn = uniqueName(), cn = uniqueName();
        Session a = register(uniqueName());
        Session b = register(bn);
        Session c = register(cn);
        Session outsider = register(uniqueName());

        // 1:1 first
        UUID oneToOne = UUID.fromString(call("POST", "/api/dms", a.token(),
                Map.of("username", bn), 200).get("channelId").asText());

        // adding a third person spawns a NEW group; the 1:1 survives untouched
        JsonNode group = call("POST", "/api/dms/" + oneToOne + "/members", a.token(),
                Map.of("username", cn), 200);
        UUID groupId = UUID.fromString(group.get("channelId").asText());
        assertThat(groupId).isNotEqualTo(oneToOne);
        assertThat(group.get("group").asBoolean()).isTrue();
        assertThat(group.get("ownerId").asText()).isEqualTo(a.userId().toString());
        assertThat(group.get("others")).hasSize(2);
        assertThat(call("GET", "/api/dms", a.token(), null, 200).findValuesAsText("channelId"))
                .contains(oneToOne.toString(), groupId.toString());

        // group chat works for all members, never for outsiders
        call("POST", "/api/channels/" + groupId + "/messages", c.token(), Map.of("content", "hi group"), 200);
        assertThat(call("GET", "/api/channels/" + groupId + "/messages", b.token(), null, 200)
                .findValuesAsText("content")).contains("hi group");
        call("GET", "/api/channels/" + groupId + "/messages", outsider.token(), null, 403);
        // group calls: members mint voice tokens for the DM channel, outsiders don't
        call("POST", "/api/channels/" + groupId + "/voice-token", c.token(), null, 200);
        call("POST", "/api/channels/" + groupId + "/voice-token", outsider.token(), null, 403);

        // any member may rename; the name comes back on the DTO
        assertThat(call("PATCH", "/api/dms/" + groupId, c.token(), Map.of("name", "the squad"), 200)
                .get("name").asText()).isEqualTo("the squad");

        // only the owner kicks; kicked members lose access
        call("DELETE", "/api/dms/" + groupId + "/members/" + b.userId(), c.token(), null, 403);
        call("DELETE", "/api/dms/" + groupId + "/members/" + b.userId(), a.token(), null, 200);
        call("GET", "/api/channels/" + groupId + "/messages", b.token(), null, 403);

        // owner leaves -> ownership transfers to a remaining member (c)
        call("POST", "/api/dms/" + groupId + "/leave", a.token(), null, 200);
        // c is the last one; leaving deletes the conversation entirely
        call("POST", "/api/dms/" + groupId + "/leave", c.token(), null, 200);
        assertThat(jdbc.queryForObject("select count(*) from channels where id = :c",
                Map.of("c", groupId), Integer.class)).isZero();
        // 1:1s cannot be left
        call("POST", "/api/dms/" + oneToOne + "/leave", a.token(), null, 400);

        // direct group creation by usernames, with dedup + size rules
        JsonNode squad = call("POST", "/api/dms", a.token(),
                Map.of("usernames", List.of(bn, cn), "name", "trio"), 200);
        assertThat(squad.get("group").asBoolean()).isTrue();
        assertThat(squad.get("name").asText()).isEqualTo("trio");
        call("POST", "/api/dms", a.token(), Map.of("usernames", List.of(bn, bn)), 400);
    }

    @Test
    void bansKeepPeopleOut() throws Exception {
        String bobName = uniqueName();
        Session admin = register(uniqueName());
        Session bob = register(bobName);
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Banhammer"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, bob);

        // members can't ban; admins can — ban removes membership and blocks rejoin
        call("POST", "/api/guilds/" + gid + "/bans/" + admin.userId(), bob.token(),
                Map.of("reason", "nope"), 403);
        call("POST", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(),
                Map.of("reason", "being rude"), 200);
        call("GET", "/api/guilds/" + gid, bob.token(), null, 403);
        // both consent paths are blocked for a banned user
        call("POST", "/api/guilds/" + gid + "/join-requests", bob.token(), null, 403);
        call("POST", "/api/guilds/" + gid + "/invites", admin.token(),
                Map.of("username", bobName), 400);

        JsonNode bans = call("GET", "/api/guilds/" + gid + "/bans", admin.token(), null, 200);
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).get("reason").asText()).isEqualTo("being rude");

        // unban -> the request/approve path works again
        call("DELETE", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(), null, 200);
        call("POST", "/api/guilds/" + gid + "/join-requests", bob.token(), null, 200);
        call("POST", "/api/guilds/" + gid + "/join-requests/" + bob.userId() + "/approve",
                admin.token(), null, 204);
        call("GET", "/api/guilds/" + gid, bob.token(), null, 200);
    }

    @Test
    void auditLogRecordsModerationAndServerChanges() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Papertrail"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID voiceChan = channelOfType(guild, "voice");
        // join via the real approve flow so the audit trail gets its member.join
        call("POST", "/api/guilds/" + gid + "/join-requests", bob.token(), null, 200);
        call("POST", "/api/guilds/" + gid + "/join-requests/" + bob.userId() + "/approve",
                admin.token(), null, 204);

        call("POST", "/api/guilds/" + gid + "/channels", admin.token(),
                Map.of("name", "logged", "type", "text"), 200);
        call("PATCH", "/api/guilds/" + gid, admin.token(), Map.of("name", "Papertrail 2"), 200);
        call("PUT", "/api/guilds/" + gid + "/members/" + bob.userId() + "/role", admin.token(),
                Map.of("role", "admin"), 204);

        // voice moderation (bob "in" the call via the presence service, as the webhook would report)
        voicePresence.joined("channel-" + voiceChan, bob.userId().toString(), "bob");
        call("POST", "/api/channels/" + voiceChan + "/voice/" + bob.userId() + "/mute",
                admin.token(), Map.of("on", true), 200);
        call("POST", "/api/channels/" + voiceChan + "/voice/" + bob.userId() + "/disconnect",
                admin.token(), null, 200);
        // demote bob again, then ban/unban for the log
        call("PUT", "/api/guilds/" + gid + "/members/" + bob.userId() + "/role", admin.token(),
                Map.of("role", "member"), 204);
        call("POST", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(), null, 200);
        call("DELETE", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(), null, 200);

        JsonNode log = call("GET", "/api/guilds/" + gid + "/audit-log", admin.token(), null, 200);
        List<String> actions = log.findValuesAsText("action");
        assertThat(actions).contains("guild.create", "member.join", "channel.create", "guild.rename",
                "member.role", "voice.mute", "voice.disconnect", "member.ban", "member.unban");
        // newest first
        assertThat(actions.get(actions.size() - 1)).isEqualTo("guild.create");
        // admins only
        call("GET", "/api/guilds/" + gid + "/audit-log", bob.token(), null, 403);
    }

    @Test
    void replyPingsTheOriginalAuthorUnlessSilenced() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Pings"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);

        UUID original = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                bob.token(), Map.of("content", "original"), 200).get("id").asText());

        // default (pingReply omitted) notifies the original author
        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "loud reply", "replyToId", original), 200);
        assertThat(mentionCount(bob, chan)).isEqualTo(1);
        // silent reply doesn't
        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "quiet reply", "replyToId", original, "pingReply", false), 200);
        assertThat(mentionCount(bob, chan)).isEqualTo(1);
        // replying to yourself never self-pings
        call("POST", "/api/channels/" + chan + "/messages", bob.token(),
                Map.of("content", "self reply", "replyToId", original), 200);
        assertThat(mentionCount(bob, chan)).isEqualTo(1);
    }

    private int mentionCount(Session who, UUID chan) throws Exception {
        Integer n = jdbc.queryForObject(
                "select coalesce(max(mention_count), 0) from read_state where user_id = :u and channel_id = :c",
                Map.of("u", who.userId(), "c", chan), Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void perChannelVanishTimerOverridesDefault() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Timers"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID fast = channelOfType(guild, "text");
        UUID slow = UUID.fromString(call("POST", "/api/guilds/" + gid + "/channels", s.token(),
                Map.of("name", "slow", "type", "text"), 200).get("id").asText());

        JsonNode updated = call("PATCH", "/api/channels/" + fast, s.token(), Map.of("retentionMs", 3600000), 200);
        assertThat(updated.get("retentionMs").asLong()).isEqualTo(3600000L);

        // two-hour-old messages: past the fast channel's 1h window, well inside the 7d default
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        UUID doomed = Ids.boundary(twoHoursAgo);
        UUID kept = Ids.boundary(twoHoursAgo.plusMillis(1));
        insertMessage(doomed, fast, s.userId(), "dies at 1h", false);
        insertMessage(kept, slow, s.userId(), "lives for 7d", false);

        retention.purgeExpired();

        assertThat(call("GET", "/api/channels/" + fast + "/messages", s.token(), null, 200)
                .findValuesAsText("id")).doesNotContain(doomed.toString());
        assertThat(call("GET", "/api/channels/" + slow + "/messages", s.token(), null, 200)
                .findValuesAsText("id")).contains(kept.toString());

        // retentionMs = 0 resets the channel to the instance default
        assertThat(call("PATCH", "/api/channels/" + fast, s.token(), Map.of("retentionMs", 0), 200)
                .get("retentionMs").isNull()).isTrue();
    }

    @Test
    void mentionsInboxListsMyPings() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Inbox"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID chan = channelOfType(guild, "text");
        join(gid, bob);

        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "hey <@" + bob.userId() + "> look at this"), 200);
        call("POST", "/api/channels/" + chan + "/messages", admin.token(),
                Map.of("content", "no ping here"), 200);

        JsonNode inbox = call("GET", "/api/mentions", bob.token(), null, 200);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("content").asText()).contains("look at this"); // decrypted
        // admin has no mentions; a kicked user's inbox goes dark
        assertThat(call("GET", "/api/mentions", admin.token(), null, 200)).isEmpty();
        call("DELETE", "/api/guilds/" + gid + "/members/" + bob.userId(), admin.token(), null, 204);
        assertThat(call("GET", "/api/mentions", bob.token(), null, 200)).isEmpty();
    }

    @Test
    void everythingIsEncryptedAtRest() throws Exception {
        Session s = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", s.token(), Map.of("name", "Vault"), 200);
        UUID chan = channelOfType(guild, "text");

        // message content: ciphertext in the DB, plaintext over the API
        String secret = "the secret lasagna recipe uses cardamom";
        UUID msg = UUID.fromString(call("POST", "/api/channels/" + chan + "/messages",
                s.token(), Map.of("content", secret), 200).get("id").asText());
        String raw = jdbc.queryForObject("select content from messages where id = :m",
                Map.of("m", msg), String.class);
        assertThat(raw).startsWith("enc:v1:").doesNotContain("lasagna");
        assertThat(call("GET", "/api/channels/" + chan + "/messages", s.token(), null, 200)
                .get(0).get("content").asText()).isEqualTo(secret);

        // search still works — the tsvector is computed from plaintext at write time
        JsonNode hits = call("GET", "/api/search?q=cardamom", s.token(), null, 200);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("content").asText()).isEqualTo(secret);
        // ...and has:link uses the flag, not a regex over ciphertext
        call("POST", "/api/channels/" + chan + "/messages",
                s.token(), Map.of("content", "see https://example.com/spice"), 200);
        assertThat(call("GET", "/api/search?has=link", s.token(), null, 200)).hasSize(1);

        // uploaded blobs: encrypted on disk, decrypted when served
        String fileBody = "attachment plaintext that must not touch disk";
        UUID att = uploadFile(s.token(), "vault.txt", fileBody);
        byte[] onDisk = java.nio.file.Files.readAllBytes(storage.root().resolve(att.toString()));
        assertThat(new String(onDisk, StandardCharsets.UTF_8)).startsWith("EPHC").doesNotContain("plaintext");
        HttpResponse<String> served = http.send(HttpRequest.newBuilder(URI.create(url("/api/files/" + att)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).isEqualTo(fileBody);
        assertThat(served.headers().firstValueAsLong("Content-Length").orElse(-1))
                .isEqualTo(fileBody.getBytes(StandardCharsets.UTF_8).length);

        // a legacy plaintext row (pre-encryption) still reads back unchanged
        insertMessage(Ids.newId(), chan, s.userId(), "legacy plaintext row", false);
        assertThat(call("GET", "/api/channels/" + chan + "/messages", s.token(), null, 200)
                .findValuesAsText("content")).contains("legacy plaintext row");
    }

    private UUID uploadFile(String token, String filename, String content) throws Exception {
        String boundary = "----b" + System.nanoTime();
        String crlf = "\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Type: text/plain" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content.getBytes(StandardCharsets.UTF_8));
        body.writeBytes((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(url("/api/uploads")))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(up.statusCode()).isEqualTo(200);
        return UUID.fromString(mapper.readTree(up.body()).get("id").asText());
    }

    // ---- direct-insert helpers for backdated rows -------------------------

    private void insertMessage(UUID id, UUID channelId, UUID authorId, String content, boolean saved) {
        jdbc.update("""
                insert into messages (id, channel_id, author_id, content, saved)
                values (:id, :c, :a, :content, :saved)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("c", channelId).addValue("a", authorId)
                .addValue("content", content).addValue("saved", saved));
    }

    private void insertAttachment(UUID id, UUID messageId, UUID ownerId) {
        jdbc.update("""
                insert into attachments (id, message_id, owner_id, filename, content_type, size_bytes, storage_key)
                values (:id, :m, :o, 'doomed.txt', 'text/plain', 3, :k)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("m", messageId).addValue("o", ownerId)
                .addValue("k", id.toString()));
    }

    // ---- social: invites, join requests, friends, role guards -------------

    @Test
    void inviteMustBeAcceptedBeforeMembership() throws Exception {
        String bobName = uniqueName();
        Session admin = register(uniqueName());
        Session bob = register(bobName);
        Session carol = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Consent"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());

        // members can't invite; admins can — and the invite alone grants nothing
        call("POST", "/api/guilds/" + gid + "/invites", bob.token(), Map.of("username", bobName), 403);
        JsonNode inv = call("POST", "/api/guilds/" + gid + "/invites", admin.token(),
                Map.of("username", bobName), 200);
        assertThat(inv.get("guildName").asText()).isEqualTo("Consent");
        call("GET", "/api/guilds/" + gid, bob.token(), null, 403);
        // duplicate invite is a conflict
        call("POST", "/api/guilds/" + gid + "/invites", admin.token(), Map.of("username", bobName), 409);

        // bob sees it and accepts -> member; the invite is consumed
        JsonNode mine = call("GET", "/api/invites", bob.token(), null, 200);
        assertThat(mine).hasSize(1);
        UUID inviteId = UUID.fromString(mine.get(0).get("id").asText());
        // carol can't accept bob's invite
        call("POST", "/api/invites/" + inviteId + "/accept", carol.token(), null, 404);
        JsonNode joined = call("POST", "/api/invites/" + inviteId + "/accept", bob.token(), null, 200);
        assertThat(joined.get("name").asText()).isEqualTo("Consent");
        call("GET", "/api/guilds/" + gid, bob.token(), null, 200);
        assertThat(call("GET", "/api/invites", bob.token(), null, 200)).isEmpty();

        // decline path: carol turns hers down and stays out
        call("POST", "/api/guilds/" + gid + "/invites", admin.token(),
                Map.of("username", usernameOf(carol)), 200);
        JsonNode carolInvites = call("GET", "/api/invites", carol.token(), null, 200);
        call("DELETE", "/api/invites/" + carolInvites.get(0).get("id").asText(), carol.token(), null, 204);
        call("GET", "/api/guilds/" + gid, carol.token(), null, 403);
    }

    @Test
    void joinRequestNeedsAdminApproval() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        Session carol = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Gatekept"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());

        JsonNode res = call("POST", "/api/guilds/" + gid + "/join-requests", bob.token(), null, 200);
        assertThat(res.get("status").asText()).isEqualTo("requested");
        call("GET", "/api/guilds/" + gid, bob.token(), null, 403); // still out
        assertThat(call("GET", "/api/me/join-requests", bob.token(), null, 200)).hasSize(1);

        // only admins see/approve the queue
        call("GET", "/api/guilds/" + gid + "/join-requests", bob.token(), null, 403);
        JsonNode queue = call("GET", "/api/guilds/" + gid + "/join-requests", admin.token(), null, 200);
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("id").asText()).isEqualTo(bob.userId().toString());
        call("POST", "/api/guilds/" + gid + "/join-requests/" + bob.userId() + "/approve",
                bob.token(), null, 403);
        call("POST", "/api/guilds/" + gid + "/join-requests/" + bob.userId() + "/approve",
                admin.token(), null, 204);
        call("GET", "/api/guilds/" + gid, bob.token(), null, 200); // in
        assertThat(call("GET", "/api/guilds/" + gid + "/join-requests", admin.token(), null, 200)).isEmpty();

        // deny path
        call("POST", "/api/guilds/" + gid + "/join-requests", carol.token(), null, 200);
        call("DELETE", "/api/guilds/" + gid + "/join-requests/" + carol.userId(), admin.token(), null, 204);
        call("GET", "/api/guilds/" + gid, carol.token(), null, 403);

        // request + pending invite = mutual consent -> joins immediately
        Session dave = register(uniqueName());
        call("POST", "/api/guilds/" + gid + "/invites", admin.token(),
                Map.of("username", usernameOf(dave)), 200);
        JsonNode direct = call("POST", "/api/guilds/" + gid + "/join-requests", dave.token(), null, 200);
        assertThat(direct.get("status").asText()).isEqualTo("joined");
        call("GET", "/api/guilds/" + gid, dave.token(), null, 200);
    }

    @Test
    void friendsRequestAcceptRemove() throws Exception {
        Session alice = register(uniqueName());
        Session bob = register(uniqueName());
        Session carol = register(uniqueName());

        // alice -> bob: pending on both sides, no friendship yet
        JsonNode a = call("POST", "/api/friends", alice.token(), Map.of("username", usernameOf(bob)), 200);
        assertThat(a.get("outgoing")).hasSize(1);
        assertThat(a.get("friends")).isEmpty();
        JsonNode b = call("GET", "/api/friends", bob.token(), null, 200);
        assertThat(b.get("incoming")).hasSize(1);
        // duplicates + self are rejected
        call("POST", "/api/friends", alice.token(), Map.of("username", usernameOf(bob)), 409);
        call("POST", "/api/friends", alice.token(), Map.of("username", usernameOf(alice)), 400);
        // carol can't accept a request that isn't hers
        call("POST", "/api/friends/" + alice.userId() + "/accept", carol.token(), null, 404);

        // bob accepts -> friends both ways
        b = call("POST", "/api/friends/" + alice.userId() + "/accept", bob.token(), null, 200);
        assertThat(b.get("friends")).hasSize(1);
        a = call("GET", "/api/friends", alice.token(), null, 200);
        assertThat(a.get("friends")).hasSize(1);
        assertThat(a.get("outgoing")).isEmpty();

        // a crossing request auto-accepts: carol -> alice while alice -> carol
        call("POST", "/api/friends", alice.token(), Map.of("username", usernameOf(carol)), 200);
        JsonNode c = call("POST", "/api/friends", carol.token(), Map.of("username", usernameOf(alice)), 200);
        assertThat(c.get("friends")).hasSize(1);

        // remove is symmetric
        call("DELETE", "/api/friends/" + bob.userId(), alice.token(), null, 200);
        b = call("GET", "/api/friends", bob.token(), null, 200);
        assertThat(b.get("friends")).isEmpty();
    }

    @Test
    void onlyTheOwnerCanDemoteAdmins() throws Exception {
        Session owner = register(uniqueName());
        Session admin2 = register(uniqueName());
        Session member = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", owner.token(), Map.of("name", "Hierarchy"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        join(gid, admin2);
        join(gid, member);

        // owner promotes admin2; a (non-owner) admin can promote too
        call("PUT", "/api/guilds/" + gid + "/members/" + admin2.userId() + "/role",
                owner.token(), Map.of("role", "admin"), 204);
        call("PUT", "/api/guilds/" + gid + "/members/" + member.userId() + "/role",
                admin2.token(), Map.of("role", "admin"), 204);

        // but a non-owner admin cannot demote another admin — only the owner can
        call("PUT", "/api/guilds/" + gid + "/members/" + member.userId() + "/role",
                admin2.token(), Map.of("role", "member"), 403);
        call("PUT", "/api/guilds/" + gid + "/members/" + member.userId() + "/role",
                owner.token(), Map.of("role", "member"), 204);

        // nobody touches the owner's role, and plain members touch nothing
        call("PUT", "/api/guilds/" + gid + "/members/" + owner.userId() + "/role",
                admin2.token(), Map.of("role", "member"), 400);
        call("PUT", "/api/guilds/" + gid + "/members/" + admin2.userId() + "/role",
                member.token(), Map.of("role", "member"), 403);
    }

    /** The username a session registered with (usernames are the unique handle). */
    private String usernameOf(Session s) {
        return jdbc.queryForObject("select username from users where id = :id",
                Map.of("id", s.userId()), String.class);
    }

    @Autowired
    com.ephemeral.user.PresenceService presenceService;
    @Autowired
    com.ephemeral.spotify.SpotifyService spotifyService;

    @Test
    void spotifyConnectFeedsListeningPresence() throws Exception {
        Session user = register(uniqueName());

        JsonNode status = call("GET", "/api/spotify/status", user.token(), null, 200);
        assertThat(status.get("configured").asBoolean()).isTrue();
        assertThat(status.get("connected").asBoolean()).isFalse();

        // consent URL carries a single-use state nonce bound to this user
        String url = call("GET", "/api/spotify/connect-url", user.token(), null, 200).get("url").asText();
        assertThat(url).contains("/authorize").contains("user-read-currently-playing");
        String state = url.replaceAll(".*[&?]state=([^&]+).*", "$1");

        // a bogus state is rejected; the real one links the account (no bearer — it's a redirect)
        HttpResponse<String> bad = raw("GET", "/api/spotify/callback?code=x&state=nope", null, null);
        assertThat(bad.statusCode()).isEqualTo(302);
        assertThat(bad.headers().firstValue("Location").orElse("")).isEqualTo("/#spotify-error");
        HttpResponse<String> ok = raw("GET", "/api/spotify/callback?code=fake-code&state=" + state, null, null);
        assertThat(ok.statusCode()).isEqualTo(302);
        assertThat(ok.headers().firstValue("Location").orElse("")).isEqualTo("/#spotify-connected");
        assertThat(call("GET", "/api/spotify/status", user.token(), null, 200)
                .get("connected").asBoolean()).isTrue();

        // while "online", a poll turns currently-playing into listening presence
        presenceService.connected(user.userId());
        try {
            spotifyService.pollOnce();
            Object mine = presenceService.snapshot().get(user.userId().toString());
            assertThat(mine).isNotNull();
            assertThat(((Map<?, ?>) mine).get("listening")).isEqualTo("Weightless — Marconi Union");

            // disconnect clears the line and the link
            call("DELETE", "/api/spotify", user.token(), null, 204);
            assertThat(call("GET", "/api/spotify/status", user.token(), null, 200)
                    .get("connected").asBoolean()).isFalse();
            Object after = presenceService.snapshot().get(user.userId().toString());
            assertThat(((Map<?, ?>) after).get("listening")).isNull();
        } finally {
            presenceService.disconnected(user.userId());
        }
    }

    @Test
    void jukeboxQueuesSearchesAndSyncsListeners() throws Exception {
        Session admin = register(uniqueName());
        Session outsider = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Musicbox"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID voice = channelOfType(guild, "voice");
        String base = "/api/channels/" + voice + "/jukebox";

        // non-members can't even look; members see an inactive box
        call("GET", base, outsider.token(), null, 403);
        JsonNode st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("active").asBoolean()).isFalse();
        assertThat(st.get("configured").asBoolean()).isTrue();

        // summon + search (app token — no user link needed to browse)
        call("POST", base + "/summon", admin.token(), null, 200);
        JsonNode found = call("GET", base + "/search?q=test", admin.token(), null, 200);
        assertThat(found.get("tracks")).hasSize(1);
        assertThat(found.get("playlists")).hasSize(1);
        JsonNode track = found.get("tracks").get(0);

        // queue the track -> it starts "playing" immediately (queue was empty)
        call("POST", base + "/queue", admin.token(), Map.of(
                "uri", track.get("uri").asText(), "name", track.get("name").asText(),
                "artists", track.get("artists").asText(), "durationMs", track.get("durationMs").asLong()), 204);
        st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("now").get("name").asText()).isEqualTo("Test Song");
        assertThat(st.get("queue")).isEmpty();

        // listen-along requires a linked Spotify; link and try again
        call("POST", base + "/listen", admin.token(), Map.of("on", true), 400);
        String url = call("GET", "/api/spotify/connect-url", admin.token(), null, 200).get("url").asText();
        String state = url.replaceAll(".*[&?]state=([^&]+).*", "$1");
        raw("GET", "/api/spotify/callback?code=fake&state=" + state, null, null);
        SPOTIFY_PLAYER_CALLS.clear();
        call("POST", base + "/listen", admin.token(), Map.of("on", true), 204);
        assertThat(SPOTIFY_PLAYER_CALLS).anyMatch(c1 -> c1.startsWith("play:") && c1.contains("spotify:track:tr1"));

        // queue a playlist wholesale, skip into it, pause syncs everyone
        JsonNode added = call("POST", base + "/queue-playlist", admin.token(), Map.of("playlistId", "pl1"), 200);
        assertThat(added.get("added").asInt()).isEqualTo(2);
        call("POST", base + "/skip", admin.token(), null, 204);
        st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("now").get("name").asText()).isEqualTo("Alpha");
        assertThat(st.get("queue")).hasSize(1);
        assertThat(SPOTIFY_PLAYER_CALLS).anyMatch(c1 -> c1.contains("spotify:track:pla"));

        call("POST", base + "/pause", admin.token(), Map.of("paused", true), 204);
        assertThat(SPOTIFY_PLAYER_CALLS).contains("pause");
        st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("now").get("paused").asBoolean()).isTrue();

        // queue edit + dismissal quiets everyone's Spotify
        call("DELETE", base + "/queue/0", admin.token(), null, 204);
        st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("queue")).isEmpty();
        call("DELETE", base, admin.token(), null, 204);
        st = call("GET", base, admin.token(), null, 200);
        assertThat(st.get("active").asBoolean()).isFalse();

        // text channels have no jukebox
        UUID text = channelOfType(guild, "text");
        call("POST", "/api/channels/" + text + "/jukebox/summon", admin.token(), null, 400);
    }

    @Test
    void feedbackGoesOnlyToTheOperator() throws Exception {
        Session operator = register("opsboss"); // matches ephemeral.operator-username
        Session bob = register(uniqueName());

        call("POST", "/api/feedback", bob.token(), Map.of("body", "   "), 400);
        call("POST", "/api/feedback", bob.token(), Map.of("body", "the login page is unreadable"), 204);

        // only the operator sees items (200 for everyone — no console-noise 403s)
        JsonNode asBob = call("GET", "/api/feedback", bob.token(), null, 200);
        assertThat(asBob.get("operator").asBoolean()).isFalse();
        assertThat(asBob.get("items")).isEmpty();
        JsonNode asOp = call("GET", "/api/feedback", operator.token(), null, 200);
        assertThat(asOp.get("operator").asBoolean()).isTrue();
        JsonNode inbox = asOp.get("items");
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("body").asText()).isEqualTo("the login page is unreadable");
        assertThat(inbox.get(0).get("author").asText()).isEqualTo("@" + usernameOf(bob));

        UUID fid = UUID.fromString(inbox.get(0).get("id").asText());
        call("DELETE", "/api/feedback/" + fid, bob.token(), null, 403);
        call("DELETE", "/api/feedback/" + fid, operator.token(), null, 204);
        assertThat(call("GET", "/api/feedback", operator.token(), null, 200).get("items")).isEmpty();
    }
}

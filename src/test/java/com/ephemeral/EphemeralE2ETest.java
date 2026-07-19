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

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (PG == null) {
            PG = EmbeddedPostgres.builder().start();
        }
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
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

        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);
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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", other.token(), null, 200);
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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + myGid + "/join", friend.token(), null, 200);
        call("POST", "/api/channels/" + myChan + "/messages", friend.token(), Map.of("content", "hi in your server"), 200);

        JsonNode friendGuild = call("POST", "/api/guilds", friend.token(), Map.of("name", "Theirs"), 200);
        UUID friendGid = UUID.fromString(friendGuild.get("id").asText());
        UUID friendChan = channelOfType(friendGuild, "text");
        call("POST", "/api/guilds/" + friendGid + "/join", user.token(), null, 200);
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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", member.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);
        call("POST", "/api/guilds/" + gid + "/join", carol.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

        // members can't ban; admins can — ban removes membership and blocks rejoin
        call("POST", "/api/guilds/" + gid + "/bans/" + admin.userId(), bob.token(),
                Map.of("reason", "nope"), 403);
        call("POST", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(),
                Map.of("reason", "being rude"), 200);
        call("GET", "/api/guilds/" + gid, bob.token(), null, 403);
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 403);
        call("POST", "/api/guilds/" + gid + "/members", admin.token(),
                Map.of("username", bobName), 400);

        JsonNode bans = call("GET", "/api/guilds/" + gid + "/bans", admin.token(), null, 200);
        assertThat(bans).hasSize(1);
        assertThat(bans.get(0).get("reason").asText()).isEqualTo("being rude");

        // unban -> rejoin works
        call("DELETE", "/api/guilds/" + gid + "/bans/" + bob.userId(), admin.token(), null, 200);
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);
    }

    @Test
    void auditLogRecordsModerationAndServerChanges() throws Exception {
        Session admin = register(uniqueName());
        Session bob = register(uniqueName());
        JsonNode guild = call("POST", "/api/guilds", admin.token(), Map.of("name", "Papertrail"), 200);
        UUID gid = UUID.fromString(guild.get("id").asText());
        UUID voiceChan = channelOfType(guild, "voice");
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
        call("POST", "/api/guilds/" + gid + "/join", bob.token(), null, 200);

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
}

package com.ephemeral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the ACTUAL scheduled retention job (not a manual call) fires on its own,
 * physically deletes expired messages, and never touches saved ones. Uses a tiny
 * 2s window and 1s cleanup interval, with its own isolated embedded Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"ephemeral.retention=2s", "ephemeral.cleanup-interval=1s"})
class RetentionScheduledTest {

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
    final HttpClient http = HttpClient.newHttpClient();

    private JsonNode call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String bod = r.body();
        return (bod == null || bod.isEmpty()) ? null : mapper.readTree(bod);
    }

    @Test
    void scheduledJobDeletesExpiredButKeepsSaved() throws Exception {
        JsonNode reg = call("POST", "/api/auth/register", null,
                Map.of("username", "sched" + UUID.randomUUID().toString().substring(0, 8),
                        "password", "hunter2pw"));
        String token = reg.get("token").asText();
        JsonNode guild = call("POST", "/api/guilds", token, Map.of("name", "Retention"));
        UUID chan = null;
        for (JsonNode c : guild.get("channels")) {
            if (c.get("type").asText().equals("text")) {
                chan = UUID.fromString(c.get("id").asText());
            }
        }

        JsonNode doomed = call("POST", "/api/channels/" + chan + "/messages", token, Map.of("content", "vanishing"));
        JsonNode kept = call("POST", "/api/channels/" + chan + "/messages", token, Map.of("content", "saved forever"));
        String doomedId = doomed.get("id").asText();
        String keptId = kept.get("id").asText();
        call("POST", "/api/messages/" + keptId + "/save", token, null);

        final UUID channel = chan;
        // the scheduled purge (every 1s, retention 2s) must eventually remove the unsaved one...
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode list = call("GET", "/api/channels/" + channel + "/messages", token, null);
            List<String> ids = list.findValuesAsText("id");
            assertThat(ids).doesNotContain(doomedId);   // auto-deleted by the schedule
            assertThat(ids).contains(keptId);           // saved survives
        });

        // ...and the saved one keeps surviving several more purge cycles
        Thread.sleep(3000);
        JsonNode finalList = call("GET", "/api/channels/" + channel + "/messages", token, null);
        assertThat(finalList.findValuesAsText("id")).contains(keptId).doesNotContain(doomedId);
    }
}

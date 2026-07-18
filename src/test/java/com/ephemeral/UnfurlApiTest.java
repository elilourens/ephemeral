package com.ephemeral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-level unfurl tests. Runs with ephemeral.allow-private-unfurl=true so a
 * loopback fixture can stand in for a public website (production keeps the
 * guard on; SafeUrlFetcherTest proves loopback is blocked there).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ephemeral.allow-private-unfurl=true")
class UnfurlApiTest {

    static EmbeddedPostgres PG;
    static HttpServer fixture;
    static int fixturePort;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        if (PG == null) {
            PG = EmbeddedPostgres.builder().start();
        }
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeAll
    static void startFixture() throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixturePort = fixture.getAddress().getPort();
        fixture.createContext("/article", ex -> {
            byte[] b = """
                    <html><head>
                      <title>Plain Title</title>
                      <meta property="og:title" content="A Great Article">
                      <meta property="og:description" content="Why disappearing messages are neat.">
                      <meta property="og:image" content="/static/hero.jpg">
                      <meta property="og:site_name" content="The Fixture Times">
                      <meta property="og:type" content="article">
                      <meta name="theme-color" content="#e2965a">
                    </head><body>…</body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            ex.close();
        });
        fixture.createContext("/bare", ex -> {
            byte[] b = "<html><head></head><body>nothing here</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            ex.close();
        });
        fixture.start();
    }

    @AfterAll
    static void stopFixture() {
        fixture.stop(0);
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper mapper;

    final HttpClient http = HttpClient.newHttpClient();

    private String register() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("username", name, "password", "hunter2pw", "displayName", name))))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body()).get("token").asText();
    }

    private HttpResponse<String> unfurl(String token, String target) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/unfurl?url=" + URLEncoder.encode(target, StandardCharsets.UTF_8)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void unfurlParsesOpenGraphCard() throws Exception {
        String token = register();
        String target = "http://127.0.0.1:" + fixturePort + "/article";
        HttpResponse<String> resp = unfurl(token, target);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode card = mapper.readTree(resp.body());
        assertThat(card.get("title").asText()).isEqualTo("A Great Article");
        assertThat(card.get("description").asText()).contains("disappearing messages");
        assertThat(card.get("siteName").asText()).isEqualTo("The Fixture Times");
        assertThat(card.get("themeColor").asText()).isEqualTo("#e2965a");
        assertThat(card.get("type").asText()).isEqualTo("article");
        // relative og:image resolved against the final URI
        assertThat(card.get("imageUrl").asText())
                .isEqualTo("http://127.0.0.1:" + fixturePort + "/static/hero.jpg");
    }

    @Test
    void unfurlWithoutUsableMetaIs404() throws Exception {
        String token = register();
        HttpResponse<String> resp = unfurl(token, "http://127.0.0.1:" + fixturePort + "/bare");
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void unfurlRequiresAuth() throws Exception {
        HttpResponse<String> resp = unfurl(null, "http://127.0.0.1:" + fixturePort + "/article");
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void gifEndpointsAre404WithoutKey() throws Exception {
        String token = register();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/gifs/featured"))
                .header("Authorization", "Bearer " + token).GET().build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }
}

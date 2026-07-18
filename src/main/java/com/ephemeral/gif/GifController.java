package com.ephemeral.gif;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.config.AppProperties;
import com.ephemeral.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side proxy for the Tenor v2 GIF API — the key stays on the server and
 * the browser never talks to Google directly (no CORS, no key leak). Disabled
 * (404) unless {@code ephemeral.tenor-key} is configured; pasted GIF links
 * render inline regardless.
 */
@RestController
public class GifController {

    private static final String TENOR = "https://tenor.googleapis.com/v2";
    private static final String MEDIA_FILTER = "tinygif,gif,tinymp4,mp4";

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public GifController(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping("/api/gifs/search")
    public Map<String, Object> search(@CurrentUser AuthUser user,
                                      @RequestParam("q") String q,
                                      @RequestParam(value = "pos", required = false) String pos) {
        requireKey();
        if (q == null || q.isBlank() || q.length() > 100) {
            throw ApiException.badRequest("bad query");
        }
        return call("/search?q=" + enc(q.trim()) + page(pos));
    }

    @GetMapping("/api/gifs/featured")
    public Map<String, Object> featured(@CurrentUser AuthUser user,
                                        @RequestParam(value = "pos", required = false) String pos) {
        requireKey();
        return call("/featured?" + page(pos).replaceFirst("^&", ""));
    }

    private void requireKey() {
        if (props.getTenorKey() == null || props.getTenorKey().isBlank()) {
            throw ApiException.notFound("GIF search is not configured (set ephemeral.tenor-key)");
        }
    }

    private String page(String pos) {
        String s = "&key=" + enc(props.getTenorKey()) + "&client_key=ephemeral"
                + "&limit=24&media_filter=" + MEDIA_FILTER + "&contentfilter=high";
        if (pos != null && !pos.isBlank() && pos.length() < 64) {
            s += "&pos=" + enc(pos);
        }
        return s;
    }

    private Map<String, Object> call(String pathAndQuery) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(TENOR + pathAndQuery))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw ApiException.badRequest("gif search failed (upstream " + resp.statusCode() + ")");
            }
            JsonNode root = mapper.readTree(resp.body());
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                JsonNode media = r.path("media_formats");
                String preview = media.path("tinygif").path("url").asText(null);
                String full = media.path("gif").path("url").asText(
                        media.path("mp4").path("url").asText(null));
                if (preview == null || full == null) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.path("id").asText());
                m.put("preview", preview);
                m.put("url", full);
                JsonNode dims = media.path("tinygif").path("dims");
                if (dims.isArray() && dims.size() == 2) {
                    m.put("w", dims.get(0).asInt());
                    m.put("h", dims.get(1).asInt());
                }
                results.add(m);
            }
            Map<String, Object> out = new HashMap<>();
            out.put("results", results);
            out.put("next", root.path("next").asText(""));
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("gif search failed");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

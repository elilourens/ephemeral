package com.ephemeral.unfurl;

import com.ephemeral.config.AppProperties;
import com.ephemeral.dto.UnfurlDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds link-preview cards. Fetches through {@link SafeUrlFetcher} (SSRF-guarded),
 * parses OpenGraph/twitter-card meta with jsoup, caches results (positive AND
 * negative) so a channel full of the same link doesn't hammer the target site.
 */
@Service
public class UnfurlService {

    private static final Logger log = LoggerFactory.getLogger(UnfurlService.class);
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final int MAX_CACHE = 512;
    private static final int MAX_FIELD = 512; // clamp text fields to keep cards tidy

    private record Entry(UnfurlDto dto, Instant at) {
    }

    private final SafeUrlFetcher fetcher;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public UnfurlService(AppProperties props) {
        this.fetcher = new SafeUrlFetcher(props.isAllowPrivateUnfurl());
    }

    /** @return a card, or null when the page yields nothing usable (or fetching failed). */
    public UnfurlDto unfurl(String url) {
        Entry hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(TTL))) {
            return hit.dto();
        }
        UnfurlDto dto = null;
        try {
            SafeUrlFetcher.FetchResult res = fetcher.fetchHtml(url);
            dto = parse(url, res);
        } catch (Exception e) {
            log.debug("unfurl failed for {}: {}", url, e.getMessage());
        }
        if (cache.size() >= MAX_CACHE) { // crude but bounded: drop an arbitrary older entry
            Iterator<String> it = cache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        cache.put(url, new Entry(dto, Instant.now()));
        return dto;
    }

    private UnfurlDto parse(String requested, SafeUrlFetcher.FetchResult res) {
        Document doc = Jsoup.parse(res.body(), res.finalUri().toString());

        String title = firstOf(doc, "og:title", "twitter:title");
        if (blank(title)) title = doc.title();
        String description = firstOf(doc, "og:description", "twitter:description");
        if (blank(description)) description = metaByName(doc, "description");
        String image = firstAbsUrl(doc, "og:image:secure_url", "og:image", "twitter:image", "twitter:image:src");
        String siteName = firstOf(doc, "og:site_name");
        if (blank(siteName)) siteName = res.finalUri().getHost();
        String themeColor = metaByName(doc, "theme-color");
        String type = firstOf(doc, "og:type");

        if (blank(title) && blank(description) && blank(image)) {
            return null;
        }
        return new UnfurlDto(requested, clamp(siteName), clamp(title), clamp(description),
                image, clamp(themeColor), clamp(type));
    }

    /** meta[property=…] with a meta[name=…] fallback (some sites use name for og:*). */
    private static String firstOf(Document doc, String... keys) {
        for (String k : keys) {
            Element el = doc.selectFirst("meta[property=" + k + "]");
            if (el == null) el = doc.selectFirst("meta[name=" + k + "]");
            if (el != null && !blank(el.attr("content"))) {
                return el.attr("content").trim();
            }
        }
        return null;
    }

    /** Like firstOf, but resolves the value as an absolute http(s) URL. */
    private static String firstAbsUrl(Document doc, String... keys) {
        for (String k : keys) {
            Element el = doc.selectFirst("meta[property=" + k + "]");
            if (el == null) el = doc.selectFirst("meta[name=" + k + "]");
            if (el != null) {
                String abs = el.absUrl("content"); // resolves relative against the final URI
                if (blank(abs)) abs = el.attr("content").trim();
                if (abs.startsWith("http://") || abs.startsWith("https://")) {
                    return abs;
                }
            }
        }
        return null;
    }

    private static String metaByName(Document doc, String name) {
        Element el = doc.selectFirst("meta[name=" + name + "]");
        return el != null && !blank(el.attr("content")) ? el.attr("content").trim() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clamp(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > MAX_FIELD ? s.substring(0, MAX_FIELD - 1) + "…" : s;
    }
}

package com.ephemeral.unfurl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Fetches user-supplied URLs for link previews with an SSRF guard (OWASP SSRF
 * cheat-sheet): http/https only, no userinfo, every hop's hostname resolved and
 * rejected if ANY address is private/loopback/link-local/reserved (IPv4 and
 * IPv6, incl. IPv4-mapped, ULA, 6to4, NAT64 and the 169.254.169.254 cloud
 * metadata range), redirects followed MANUALLY with re-validation per hop,
 * capped redirect count, timeouts, capped body size and an HTML content-type
 * gate before any body is read.
 *
 * <p>Residual risk: the JDK client re-resolves DNS at connect time, so a
 * fast-flux DNS rebind could in theory pass validation then connect elsewhere.
 * Deployments that care should ALSO firewall egress to RFC1918 + 169.254/16
 * from the app container (defense in depth); inside the provided docker-compose
 * the container has no special network access, so the blast radius is the host
 * network only.
 */
public final class SafeUrlFetcher {

    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // we follow manually, re-validating each hop
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** When true (tests only), the private/loopback address check is skipped. */
    private final boolean allowPrivate;

    public SafeUrlFetcher(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public record FetchResult(URI finalUri, String contentType, String body) {
    }

    public FetchResult fetchHtml(String userUrl) throws IOException, InterruptedException {
        URI uri = validateUri(userUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateHost(uri);

            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "ephemeral-link-preview/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int sc = resp.statusCode();

            if (sc >= 300 && sc < 400) {
                String loc = resp.headers().firstValue("location").orElse(null);
                resp.body().close();
                if (loc == null) {
                    throw new IOException("redirect without Location");
                }
                uri = validateUri(uri.resolve(loc).toString()); // re-validate scheme/host of the new target
                continue;
            }
            if (sc != 200) {
                resp.body().close();
                throw new IOException("HTTP " + sc);
            }
            String ct = resp.headers().firstValue("content-type").orElse("")
                    .split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!HTML_TYPES.contains(ct)) { // gate BEFORE reading the body
                resp.body().close();
                throw new IOException("not html: " + ct);
            }
            return new FetchResult(uri, ct, readCapped(resp.body()));
        }
        throw new IOException("too many redirects");
    }

    private static URI validateUri(String raw) throws IOException {
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IOException("bad URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IOException("scheme not allowed");
        }
        if (uri.getHost() == null) {
            throw new IOException("no host");
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("userinfo not allowed"); // blocks http://169.254.169.254@evil/ confusion
        }
        return uri;
    }

    private void validateHost(URI uri) throws IOException {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(uri.getHost()); // all A + AAAA records
        } catch (UnknownHostException e) {
            throw new IOException("cannot resolve host");
        }
        if (addrs.length == 0) {
            throw new IOException("no addresses");
        }
        if (allowPrivate) {
            return;
        }
        for (InetAddress a : addrs) {
            if (isForbidden(a)) {
                throw new IOException("blocked address: " + a.getHostAddress());
            }
        }
    }

    /** Loopback / private / link-local / CGNAT / reserved / metadata — IPv4 and IPv6. */
    static boolean isForbidden(InetAddress addr) {
        if (addr.isAnyLocalAddress()      // 0.0.0.0, ::
                || addr.isLoopbackAddress()   // 127.0.0.0/8, ::1
                || addr.isLinkLocalAddress()  // 169.254.0.0/16 (cloud metadata!), fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            if (o0 == 0 || o0 == 127) return true;                 // 0/8, 127/8
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return true;   // 100.64/10 CGNAT
            if (o0 == 169 && o1 == 254) return true;               // 169.254/16
            if (o0 >= 240) return true;                            // 240/4 reserved + broadcast
            return false;
        }
        if (addr instanceof Inet6Address a6) {
            if (a6.isIPv4CompatibleAddress()) return true;         // ::/96 (deprecated tunnel)
            boolean mapped = true;                                 // ::ffff:0:0/96 → re-check embedded v4
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isForbidden(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            int f0 = b[0] & 0xff, f1 = b[1] & 0xff;
            if ((f0 & 0xfe) == 0xfc) return true;                  // fc00::/7 ULA
            if (f0 == 0x20 && f1 == 0x02) return true;             // 2002::/16 6to4
            if (f0 == 0x00 && f1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return true;                                       // 64:ff9b::/96 NAT64
            }
            return false;
        }
        return true; // unknown address family → deny
    }

    private static String readCapped(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("body too large");
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}

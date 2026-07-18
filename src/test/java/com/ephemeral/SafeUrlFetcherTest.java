package com.ephemeral;

import com.ephemeral.unfurl.SafeUrlFetcher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSRF-guard tests for the link unfurler: every private/reserved range must be
 * refused, redirects must be capped, non-HTML must be refused, and — the big
 * one — a reachable loopback server must still be BLOCKED when the guard is on.
 */
class SafeUrlFetcherTest {

    static HttpServer fixture;
    static int port;

    @BeforeAll
    static void startFixture() throws IOException {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = fixture.getAddress().getPort();
        fixture.createContext("/og", ex -> respond(ex, 200, "text/html", """
                <html><head>
                  <title>Fallback Title</title>
                  <meta property="og:title" content="OG Title">
                  <meta property="og:description" content="OG Description">
                  <meta property="og:image" content="/img/preview.png">
                  <meta property="og:site_name" content="Fixture">
                  <meta name="theme-color" content="#123456">
                </head><body>hi</body></html>
                """));
        fixture.createContext("/redir", ex -> { // one hop to /og
            ex.getResponseHeaders().add("Location", "/og");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        fixture.createContext("/loop", ex -> { // infinite redirect
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        fixture.createContext("/binary", ex -> respond(ex, 200, "application/octet-stream", "MZ..."));
        fixture.start();
    }

    @AfterAll
    static void stopFixture() {
        fixture.stop(0);
    }

    static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
        ex.close();
    }

    // ---- the address classifier -------------------------------------------

    static boolean forbidden(String ip) throws Exception {
        Method m = SafeUrlFetcher.class.getDeclaredMethod("isForbidden", InetAddress.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, InetAddress.getByName(ip));
    }

    @Test
    void privateAndReservedAddressesAreForbidden() throws Exception {
        String[] blocked = {
                "127.0.0.1", "127.8.8.8",             // loopback
                "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", // RFC1918
                "169.254.169.254",                    // link-local / cloud metadata
                "0.0.0.0", "0.1.2.3",                 // "this network"
                "100.64.0.1", "100.127.255.255",      // CGNAT
                "240.0.0.1", "255.255.255.255",       // reserved / broadcast
                "224.0.0.1",                          // multicast
                "::1", "::",                          // v6 loopback / any
                "fe80::1",                            // v6 link-local
                "fc00::1", "fdab::1",                 // v6 ULA
                "2002:7f00:1::1",                     // 6to4
                "64:ff9b::7f00:1",                    // NAT64
                "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", // v4-mapped
        };
        for (String ip : blocked) {
            assertThat(forbidden(ip)).as("%s must be forbidden", ip).isTrue();
        }
    }

    @Test
    void publicAddressesAreAllowed() throws Exception {
        String[] allowed = {"93.184.215.14", "8.8.8.8", "1.1.1.1", "140.82.121.4",
                "2606:4700:4700::1111", "2a00:1450:4001:80e::200e"};
        for (String ip : allowed) {
            assertThat(forbidden(ip)).as("%s must be allowed", ip).isFalse();
        }
    }

    // ---- guard behaviour against a live loopback server --------------------

    @Test
    void guardBlocksReachableLoopbackServer() {
        SafeUrlFetcher guarded = new SafeUrlFetcher(false);
        assertThatThrownBy(() -> guarded.fetchHtml("http://127.0.0.1:" + port + "/og"))
                .hasMessageContaining("blocked address");
        assertThatThrownBy(() -> guarded.fetchHtml("http://localhost:" + port + "/og"))
                .hasMessageContaining("blocked address");
    }

    @Test
    void schemesAndUserinfoAreRejected() {
        SafeUrlFetcher guarded = new SafeUrlFetcher(false);
        assertThatThrownBy(() -> guarded.fetchHtml("file:///etc/passwd")).hasMessageContaining("scheme");
        assertThatThrownBy(() -> guarded.fetchHtml("ftp://example.com/x")).hasMessageContaining("scheme");
        assertThatThrownBy(() -> guarded.fetchHtml("http://169.254.169.254@example.com/"))
                .hasMessageContaining("userinfo");
    }

    @Test
    void followsRedirectAndParsesWhenPrivateAllowed() throws Exception {
        SafeUrlFetcher open = new SafeUrlFetcher(true); // tests only
        SafeUrlFetcher.FetchResult res = open.fetchHtml("http://127.0.0.1:" + port + "/redir");
        assertThat(res.body()).contains("OG Title");
        assertThat(res.finalUri().getPath()).isEqualTo("/og");
    }

    @Test
    void redirectLoopIsCapped() {
        SafeUrlFetcher open = new SafeUrlFetcher(true);
        assertThatThrownBy(() -> open.fetchHtml("http://127.0.0.1:" + port + "/loop"))
                .hasMessageContaining("too many redirects");
    }

    @Test
    void nonHtmlContentIsRefused() {
        SafeUrlFetcher open = new SafeUrlFetcher(true);
        assertThatThrownBy(() -> open.fetchHtml("http://127.0.0.1:" + port + "/binary"))
                .hasMessageContaining("not html");
    }
}

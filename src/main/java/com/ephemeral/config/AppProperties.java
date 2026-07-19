package com.ephemeral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound from the "ephemeral.*" section of application.yml.
 */
@ConfigurationProperties(prefix = "ephemeral")
public class AppProperties {

    /** How long a message lives before it is physically deleted (the sliding window). */
    private Duration retention = Duration.ofDays(7);

    /** How often the retention purge job runs. */
    private Duration cleanupInterval = Duration.ofMinutes(15);

    /** Grace before an unreferenced on-disk blob is treated as an orphan and swept. */
    private Duration orphanGrace = Duration.ofHours(1);

    /** HMAC secret for signing app auth JWTs. Must be >= 32 bytes. */
    private String jwtSecret = "dev-only-insecure-secret-change-me-please-0123456789";

    /**
     * At-rest encryption key: base64 of exactly 32 bytes (openssl rand -base64 32).
     * Blank = derived from the JWT secret (fine for dev; set explicitly in prod).
     */
    private String encryptionKey = "";

    /**
     * Maintain the full-text search index? It stores word STEMS of message
     * plaintext (bodies themselves are encrypted); false = maximum privacy,
     * text search returns nothing.
     */
    private boolean searchIndex = true;

    /** How long an issued app auth token is valid. */
    private Duration jwtTtl = Duration.ofDays(7);

    /** Directory where uploaded files are stored. */
    private String storageDir = "./data/uploads";

    /**
     * TESTS ONLY: lets the link unfurler fetch private/loopback addresses so a
     * local fixture server can be used. Never enable in production — it turns
     * off the SSRF guard.
     */
    private boolean allowPrivateUnfurl = false;

    /** Tenor v2 API key for the GIF picker. Empty = picker disabled (pasted GIF links still render). */
    private String tenorKey = "";

    private final LiveKit livekit = new LiveKit();

    public static class LiveKit {
        /** URL the browser connects to (ws:// or wss://). */
        private String url = "ws://localhost:7880";
        private String apiKey = "devkey";
        private String apiSecret = "livekit-dev-secret-please-change-me-0123456789";
        /** How long a minted LiveKit join token is valid. */
        private Duration tokenTtl = Duration.ofHours(6);

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
        public Duration getTokenTtl() { return tokenTtl; }
        public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
    }

    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    public Duration getOrphanGrace() { return orphanGrace; }
    public void setOrphanGrace(Duration orphanGrace) { this.orphanGrace = orphanGrace; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
    public boolean isSearchIndex() { return searchIndex; }
    public void setSearchIndex(boolean searchIndex) { this.searchIndex = searchIndex; }
    public Duration getJwtTtl() { return jwtTtl; }
    public void setJwtTtl(Duration jwtTtl) { this.jwtTtl = jwtTtl; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public boolean isAllowPrivateUnfurl() { return allowPrivateUnfurl; }
    public void setAllowPrivateUnfurl(boolean allowPrivateUnfurl) { this.allowPrivateUnfurl = allowPrivateUnfurl; }
    public String getTenorKey() { return tenorKey; }
    public void setTenorKey(String tenorKey) { this.tenorKey = tenorKey; }
    public LiveKit getLivekit() { return livekit; }
}

package com.ephemeral.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Under the "dev" profile only, boots a real embedded Postgres (no Docker) and
 * points the datasource at it. Lets the app run standalone on a machine with no
 * Postgres installed. In containers this is inert — the compose file provides PG.
 */
public class DevDatabaseInitializer implements EnvironmentPostProcessor {

    private static EmbeddedPostgres pg;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        boolean dev = Arrays.asList(env.getActiveProfiles()).contains("dev")
                || "dev".equals(env.getProperty("spring.profiles.active"));
        if (!dev) {
            return;
        }
        try {
            if (pg == null) {
                pg = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        pg.close();
                    } catch (Exception ignored) {
                    }
                }));
                System.out.println("[dev] embedded Postgres started at " + pg.getJdbcUrl("postgres", "postgres"));
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to start embedded Postgres", e);
        }
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        props.put("spring.datasource.username", "postgres");
        props.put("spring.datasource.password", "postgres");
        env.getPropertySources().addFirst(new MapPropertySource("embeddedPostgres", props));
    }
}

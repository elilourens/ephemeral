package com.ephemeral.config;

import com.ephemeral.message.RetentionService;
import com.ephemeral.spotify.SpotifyService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Schedules the retention purge (and the Spotify presence poll) at the configured intervals. */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private final RetentionService retention;
    private final SpotifyService spotify;
    private final AppProperties props;

    public SchedulingConfig(RetentionService retention, SpotifyService spotify, AppProperties props) {
        this.retention = retention;
        this.spotify = spotify;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(() -> retention.purgeExpired(), props.getCleanupInterval());
        registrar.addFixedDelayTask(() -> retention.reconcileOrphanBlobs(props.getOrphanGrace()),
                props.getCleanupInterval());
        // no-op unless configured; polls only online, linked users
        registrar.addFixedDelayTask(spotify::pollOnce, props.getSpotify().getPollInterval());
    }
}

package com.ephemeral.config;

import com.ephemeral.message.RetentionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Schedules the retention purge at the configured interval. */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    private final RetentionService retention;
    private final AppProperties props;

    public SchedulingConfig(RetentionService retention, AppProperties props) {
        this.retention = retention;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(() -> retention.purgeExpired(), props.getCleanupInterval());
        registrar.addFixedDelayTask(() -> retention.reconcileOrphanBlobs(props.getOrphanGrace()),
                props.getCleanupInterval());
    }
}

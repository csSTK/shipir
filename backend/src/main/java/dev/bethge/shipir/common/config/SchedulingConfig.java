package dev.bethge.shipir.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods. */
@Configuration
@EnableScheduling
class SchedulingConfig {
}

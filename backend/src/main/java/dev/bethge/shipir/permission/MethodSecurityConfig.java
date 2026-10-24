package dev.bethge.shipir.permission;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Enables {@code @PreAuthorize} (used with {@link PermissionEvaluator}). */
@Configuration
@EnableMethodSecurity
class MethodSecurityConfig {
}

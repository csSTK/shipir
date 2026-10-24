package dev.bethge.shipir.config_editor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound from {@code shipir.config-editor.*}: size cap per file and how many backups are kept per file. */
@ConfigurationProperties("shipir.config-editor")
public record ConfigFileProperties(@DefaultValue("1048576") long maxFileBytes, @DefaultValue("10") int backupsPerFile) {
}

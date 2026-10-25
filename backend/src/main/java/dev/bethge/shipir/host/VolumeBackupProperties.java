package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Bound from {@code shipir.volume-backup.*}: {@code maxBytes} caps one backup stream and one restore upload (the
 * servlet multipart limit in {@code spring.servlet.multipart.*} must allow it too), {@code helperImage} is the image
 * of the short-lived helper container, {@code dir} holds the archives of scheduled backups, {@code tick} is how often
 * due schedules are looked up.
 */
@ConfigurationProperties("shipir.volume-backup")
record VolumeBackupProperties(@DefaultValue("100MB") DataSize maxBytes,
                              @DefaultValue("alpine:3") String helperImage,
                              @DefaultValue("./data/volume-backups") Path dir,
                              @DefaultValue("true") boolean schedulerEnabled,
                              @DefaultValue("1m") Duration tick) {
}

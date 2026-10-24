package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Bound from {@code shipir.container-files.*}: {@code maxBytes} caps one download or upload,
 * {@code maxScanBytes} caps the tar read to list one directory (the daemon sends the whole subtree).
 */
@ConfigurationProperties("shipir.container-files")
record ContainerFilesProperties(@DefaultValue("100MB") DataSize maxBytes,
                                @DefaultValue("64MB") DataSize maxScanBytes) {
}

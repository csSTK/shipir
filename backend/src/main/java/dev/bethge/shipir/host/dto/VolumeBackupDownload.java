package dev.bethge.shipir.host.dto;

import java.io.InputStream;

/** An open backup stream with its suggested file name; closing the stream releases the helper container. */
public record VolumeBackupDownload(InputStream content, String fileName) {
}

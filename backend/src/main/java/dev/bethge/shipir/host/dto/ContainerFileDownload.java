package dev.bethge.shipir.host.dto;

import java.io.InputStream;

/**
 * Opened download: {@code content} is closed by the caller. {@code size} is the exact length or -1 (unknown, tar of a
 * directory); {@code singleFile} means the bytes are the raw file, otherwise a tar archive.
 */
public record ContainerFileDownload(InputStream content, String fileName, long size, boolean singleFile) {
}

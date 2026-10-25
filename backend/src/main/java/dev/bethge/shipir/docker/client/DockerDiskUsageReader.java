package dev.bethge.shipir.docker.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
import dev.bethge.shipir.docker.dto.DiskUsageDto.CategoryUsage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Reads {@code GET /system/df}. docker-java 3.7.1 has no system disk-usage command (only per-object list calls, which
 * carry no build cache and no reliable volume sizes), so the one raw call lives here, the only place that touches
 * {@link DockerClientImpl#getHttpClient()}. Everything read is mapped to {@link DiskUsageDto}; no docker-java or
 * Jackson type leaves this class. The request has no user input and is deliberately unversioned, so the daemon's
 * newest API answers; the parser is tolerant of unknown and missing fields. The response is size-bounded, and the
 * daemon is not fully trusted (a remote TCP host): negative sizes count as 0, a sum that overflows is INVALID_RESPONSE.
 * Timeouts come from the client's transport config.
 *
 * <p>Deviation from {@code docker system df}: a category's reclaimable size is capped at its total, and shared image
 * layers are not subtracted from the reclaimable images (SharedSize is not used), so the image reclaimable number can
 * be an overestimate when images share layers.
 */
final class DockerDiskUsageReader {

    /** Upper bound for the response body; a real daemon answer is far smaller. */
    static final int MAX_BYTES = 16 * 1024 * 1024;

    private static final String PATH = "/system/df";
    private static final ObjectMapper JSON = new ObjectMapper();

    private DockerDiskUsageReader() {
    }

    static DiskUsageDto read(DockerClient client) {
        if (!(client instanceof DockerClientImpl impl)) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_RESPONSE,
                    "Disk usage needs the HTTP transport of the Docker client.", null);
        }
        DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path(PATH)
                .build();
        try (DockerHttpClient.Response response = impl.getHttpClient().execute(request)) {
            return parse(response.getStatusCode(), response.getBody());
        }
    }

    static DiskUsageDto parse(int status, InputStream body) {
        if (status != 200) {
            throw invalid("status " + status);
        }
        RawDiskUsage raw = readBounded(body);
        return new DiskUsageDto(images(raw), containers(raw), volumes(raw), buildCache(raw));
    }

    private static RawDiskUsage readBounded(InputStream body) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid("body larger than " + MAX_BYTES + " bytes");
        }
        try {
            RawDiskUsage raw = JSON.readValue(bytes, RawDiskUsage.class);
            if (raw == null) {
                throw invalid("empty body");
            }
            return raw;
        } catch (JacksonException e) {
            throw invalid("body is not valid JSON");
        }
    }

    private static CategoryUsage images(RawDiskUsage raw) {
        List<RawImage> images = raw.images();
        if (images == null) {
            return new CategoryUsage(null, raw.layersSize(), null);
        }
        Long total = raw.layersSize() == null ? null : size(raw.layersSize());
        long reclaimable = sum(images, i -> i.containers() != null && i.containers() == 0 ? size(i.size()) : 0);
        return new CategoryUsage(images.size(), total, capped(reclaimable, total));
    }

    private static CategoryUsage containers(RawDiskUsage raw) {
        List<RawContainer> containers = raw.containers();
        if (containers == null) {
            return CategoryUsage.UNREPORTED;
        }
        long total = sum(containers, c -> size(c.sizeRw()));
        long reclaimable = sum(containers, c -> !"running".equals(c.state()) ? size(c.sizeRw()) : 0);
        return new CategoryUsage(containers.size(), total, capped(reclaimable, total));
    }

    private static CategoryUsage volumes(RawDiskUsage raw) {
        List<RawVolume> volumes = raw.volumes();
        if (volumes == null) {
            return CategoryUsage.UNREPORTED;
        }
        // Docker reports a size of -1 when it has not computed the usage: that is unknown, not zero
        long total = sum(volumes, v -> v.usageData() == null ? 0 : size(v.usageData().size()));
        long reclaimable = sum(volumes, v -> v.usageData() != null && v.usageData().refCount() != null
                && v.usageData().refCount() == 0 ? size(v.usageData().size()) : 0);
        return new CategoryUsage(volumes.size(), total, capped(reclaimable, total));
    }

    private static CategoryUsage buildCache(RawDiskUsage raw) {
        List<RawBuildCache> entries = raw.buildCache();
        if (entries == null) {
            return CategoryUsage.UNREPORTED;
        }
        long total = sum(entries, e -> size(e.size()));
        long reclaimable = sum(entries, e -> !Boolean.TRUE.equals(e.inUse()) ? size(e.size()) : 0);
        return new CategoryUsage(entries.size(), total, capped(reclaimable, total));
    }

    /** Missing and negative sizes count as 0: Docker uses -1 for "not computed" (volumes), which is not a size. */
    private static long size(Long value) {
        return value == null || value < 0 ? 0 : value;
    }

    /** Reclaimable space cannot exceed the category's total (shared layers are counted once in the total). */
    private static Long capped(long reclaimable, Long total) {
        return total == null ? reclaimable : Math.min(reclaimable, total);
    }

    /** Overflow is an implausible answer from the daemon, so it is refused instead of wrapping into a negative total. */
    private static <T> long sum(List<T> items, ToLongFunction<T> bytes) {
        long total = 0;
        for (T item : items) {
            try {
                total = Math.addExact(total, bytes.applyAsLong(item));
            } catch (ArithmeticException e) {
                throw invalid("sizes overflow");
            }
        }
        return total;
    }

    private static DockerConnectionException invalid(String detail) {
        return new DockerConnectionException(DockerErrorCode.INVALID_RESPONSE,
                "The Docker daemon returned an unexpected disk usage response (" + detail + ").", null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDiskUsage(
            @JsonProperty("LayersSize") Long layersSize,
            @JsonProperty("Images") List<RawImage> images,
            @JsonProperty("Containers") List<RawContainer> containers,
            @JsonProperty("Volumes") List<RawVolume> volumes,
            @JsonProperty("BuildCache") List<RawBuildCache> buildCache) {
    }

    /** {@code Containers} is the number of containers using the image; 0 = unused. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawImage(@JsonProperty("Size") Long size, @JsonProperty("Containers") Long containers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawContainer(@JsonProperty("State") String state, @JsonProperty("SizeRw") Long sizeRw) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVolume(@JsonProperty("UsageData") RawUsageData usageData) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawUsageData(@JsonProperty("Size") Long size, @JsonProperty("RefCount") Long refCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawBuildCache(@JsonProperty("Size") Long size, @JsonProperty("InUse") Boolean inUse) {
    }
}

package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
import dev.bethge.shipir.docker.dto.DiskUsageDto.CategoryUsage;
import dev.bethge.shipir.docker.dto.DiskUsageDto.LargestItem;
import dev.bethge.shipir.docker.dto.DiskUsageDto.LargestItem.Category;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Parsing of {@code GET /system/df} (Docker API sample shape) and the call through the HTTP transport. */
class DockerDiskUsageReaderTest {

    /** Shaped like the Docker API reference for /system/df; one unused image, one stopped container, one unknown volume size. */
    private static final String SAMPLE = """
            {
              "LayersSize": 1092588,
              "Images": [
                {"Id": "sha256:e2f1", "RepoTags": ["alpine:latest"], "Size": 5608, "SharedSize": 0, "Containers": 1},
                {"Id": "sha256:abc0", "RepoTags": ["busybox:old"], "Size": 2000, "SharedSize": 0, "Containers": 0}
              ],
              "Containers": [
                {"Id": "c1", "State": "running", "SizeRw": 100, "SizeRootFs": 5608},
                {"Id": "c2", "State": "exited", "SizeRw": 300, "SizeRootFs": 5000}
              ],
              "Volumes": [
                {"Name": "data", "UsageData": {"Size": 4096, "RefCount": 1}},
                {"Name": "old", "UsageData": {"Size": 1000, "RefCount": 0}},
                {"Name": "unknown", "UsageData": {"Size": -1, "RefCount": 0}}
              ],
              "BuildCache": [
                {"ID": "b1", "Size": 700, "InUse": false, "Shared": true},
                {"ID": "b2", "Size": 300, "InUse": true, "Shared": true}
              ]
            }
            """;

    private static InputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sumsTotalsAndReclaimableSizePerCategory() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(SAMPLE));

        assertThat(usage.images().count()).isEqualTo(2);
        assertThat(usage.images().totalSize()).isEqualTo(1092588L);
        assertThat(usage.images().reclaimableSize()).isEqualTo(2000L);
        assertThat(usage.containers().count()).isEqualTo(2);
        assertThat(usage.containers().totalSize()).isEqualTo(400L);
        assertThat(usage.containers().reclaimableSize()).isEqualTo(300L);
        assertThat(usage.volumes().count()).isEqualTo(3);
        assertThat(usage.volumes().totalSize()).isEqualTo(5096L);
        assertThat(usage.volumes().reclaimableSize()).isEqualTo(1000L);
        assertThat(usage.buildCache().count()).isEqualTo(2);
        assertThat(usage.buildCache().totalSize()).isEqualTo(1000L);
        assertThat(usage.buildCache().reclaimableSize()).isEqualTo(700L);
    }

    @Test
    void largestListsItemsAcrossCategoriesBySizeDescendingWithTiesByCategory() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(SAMPLE));

        // the volume of unknown size (-1) is left out; 300/300 tie: container before build cache
        assertThat(usage.largest()).containsExactly(
                new LargestItem(Category.IMAGE, "alpine:latest", 5608L),
                new LargestItem(Category.VOLUME, "data", 4096L),
                new LargestItem(Category.IMAGE, "busybox:old", 2000L),
                new LargestItem(Category.VOLUME, "old", 1000L),
                new LargestItem(Category.BUILD_CACHE, "Build cache b1", 700L),
                new LargestItem(Category.CONTAINER, "c2", 300L),
                new LargestItem(Category.BUILD_CACHE, "Build cache b2", 300L),
                new LargestItem(Category.CONTAINER, "c1", 100L));
    }

    @Test
    void largestIsCappedAtTenKeepingTheBiggest() {
        String volumes = IntStream.rangeClosed(1, 15)
                .mapToObj(i -> "{\"Name\": \"v" + i + "\", \"UsageData\": {\"Size\": " + i * 10 + ", \"RefCount\": 1}}")
                .collect(Collectors.joining(","));
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body("{\"Volumes\": [" + volumes + "]}"));

        assertThat(usage.largest()).hasSize(10);
        assertThat(usage.largest().getFirst()).isEqualTo(new LargestItem(Category.VOLUME, "v15", 150L));
        assertThat(usage.largest().getLast()).isEqualTo(new LargestItem(Category.VOLUME, "v6", 60L));
    }

    @Test
    void largestSkipsNullAndNegativeSizes() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body("""
                {"Images": [{"Id": "sha256:aaa", "RepoTags": ["a:1"], "Size": -3}, {"Id": "sha256:bbb", "RepoTags": ["b:1"]}],
                 "Containers": [{"Names": ["/web"], "State": "exited", "SizeRw": null}],
                 "BuildCache": [{"ID": "x", "Size": -1}]}
                """));

        assertThat(usage.largest()).isEmpty();
    }

    @Test
    void largestIsEmptyWhenNothingIsReported() {
        assertThat(DockerDiskUsageReader.parse(200, body("{}")).largest()).isEmpty();
    }

    @Test
    void largestNamesAreSafeDisplayStrings() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body("""
                {"Images": [
                   {"Id": "sha256:0123456789abcdef0123", "RepoTags": ["<none>:<none>"], "Size": 50},
                   {"Id": "sha256:fedcba9876543210ffff", "RepoTags": [], "Size": 40}],
                 "Containers": [
                   {"Id": "cafe", "Names": ["/web-1", "/other/alias"], "State": "running", "SizeRw": 30,
                    "Command": "run --password=secret", "Labels": {"token": "secret"}},
                   {"Id": "0123456789abcdef0123456789", "State": "running", "SizeRw": 20}],
                 "BuildCache": [{"ID": "abcdefghijklmnop", "Description": "RUN echo $SECRET_TOKEN", "Size": 10}],
                 "Volumes": [{"Name": "bad\\u0007name\\n", "UsageData": {"Size": 5, "RefCount": 0}}]}
                """));

        assertThat(usage.largest()).extracting(LargestItem::name).containsExactly(
                "0123456789ab", "fedcba987654", "web-1", "0123456789ab", "Build cache abcdefghijkl", "badname");
        assertThat(usage.largest().toString()).doesNotContain("secret", "SECRET");
    }

    @Test
    void largestNamesHaveC1BidiAndFormatCharactersNeutralized() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body("""
                {"Volumes": [
                   {"Name": "a\u202Eevil\u202Cb", "UsageData": {"Size": 50, "RefCount": 0}},
                   {"Name": "c\u0085d\u200Be\u2066f", "UsageData": {"Size": 40, "RefCount": 0}},
                   {"Name": "caf\u00e9-\u202E", "UsageData": {"Size": 30, "RefCount": 0}}]}
                """));

        assertThat(usage.largest()).extracting(LargestItem::name)
                .containsExactly("a evil b", "c d e f", "café-");
    }

    @Test
    void largestNamesAreTruncated() {
        String longName = "v".repeat(500);
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(
                "{\"Volumes\": [{\"Name\": \"" + longName + "\", \"UsageData\": {\"Size\": 5, \"RefCount\": 0}}]}"));

        assertThat(usage.largest().getFirst().name()).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void missingCategoriesAreUnreportedNotZero() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body("{\"LayersSize\": 10}"));

        assertThat(usage.images().totalSize()).isEqualTo(10L);
        assertThat(usage.images().count()).isNull();
        assertThat(usage.images().reclaimableSize()).isNull();
        assertThat(usage.containers()).isEqualTo(CategoryUsage.UNREPORTED);
        assertThat(usage.volumes()).isEqualTo(CategoryUsage.UNREPORTED);
        assertThat(usage.buildCache()).isEqualTo(CategoryUsage.UNREPORTED);
    }

    @Test
    void garbageIsInvalidResponse() {
        assertThatThrownBy(() -> DockerDiskUsageReader.parse(200, body("<html>proxy error</html>")))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }

    @Test
    void truncatedBodyIsInvalidResponse() {
        String truncated = SAMPLE.substring(0, SAMPLE.length() / 2);
        assertThatThrownBy(() -> DockerDiskUsageReader.parse(200, body(truncated)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }

    @Test
    void nonOkStatusIsInvalidResponseEvenWithValidBody() {
        assertThatThrownBy(() -> DockerDiskUsageReader.parse(500, body(SAMPLE)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }

    @Test
    void oversizedBodyIsRefusedBeforeParsing() {
        InputStream huge = new ByteArrayInputStream(new byte[DockerDiskUsageReader.MAX_BYTES + 1]);
        assertThatThrownBy(() -> DockerDiskUsageReader.parse(200, huge))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }

    @Test
    void negativeImageSizeCountsAsZero() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(
                "{\"LayersSize\": 100, \"Images\": [{\"Size\": -5, \"Containers\": 0}, {\"Size\": 40, \"Containers\": 1}]}"));

        assertThat(usage.images().totalSize()).isEqualTo(100L);
        assertThat(usage.images().reclaimableSize()).isEqualTo(0L);
    }

    @Test
    void negativeContainerSizeCountsAsZero() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(
                "{\"Containers\": [{\"State\": \"exited\", \"SizeRw\": -7}, {\"State\": \"running\", \"SizeRw\": 10}]}"));

        assertThat(usage.containers().totalSize()).isEqualTo(10L);
        assertThat(usage.containers().reclaimableSize()).isEqualTo(0L);
    }

    @Test
    void negativeBuildCacheSizeCountsAsZero() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(
                "{\"BuildCache\": [{\"Size\": -9, \"InUse\": false}, {\"Size\": 20, \"InUse\": false}]}"));

        assertThat(usage.buildCache().totalSize()).isEqualTo(20L);
        assertThat(usage.buildCache().reclaimableSize()).isEqualTo(20L);
    }

    @Test
    void overflowingSizesAreInvalidResponseNotAWrappedTotal() {
        String max = Long.toString(Long.MAX_VALUE);
        assertThatThrownBy(() -> DockerDiskUsageReader.parse(200, body(
                "{\"Containers\": [{\"State\": \"running\", \"SizeRw\": " + max + "}, {\"State\": \"running\", \"SizeRw\": " + max + "}]}")))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }

    @Test
    void reclaimableNeverExceedsTotalForImages() {
        DiskUsageDto usage = DockerDiskUsageReader.parse(200, body(
                "{\"LayersSize\": 100, \"Images\": [{\"Size\": 80, \"Containers\": 0}, {\"Size\": 80, \"Containers\": 0}]}"));

        assertThat(usage.images().totalSize()).isEqualTo(100L);
        assertThat(usage.images().reclaimableSize()).isEqualTo(100L);
    }

    @Test
    void readsThroughTheTransportWithAGetOnSystemDf() throws Exception {
        DockerHttpClient http = mock(DockerHttpClient.class);
        DockerHttpClient.Response response = mock(DockerHttpClient.Response.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.getBody()).thenReturn(body(SAMPLE));
        when(http.execute(any())).thenReturn(response);
        DockerClient client = DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").build(), http);

        DiskUsageDto usage = DockerDiskUsageReader.read(client);

        assertThat(usage.containers().reclaimableSize()).isEqualTo(300L);
        ArgumentCaptor<DockerHttpClient.Request> sent = ArgumentCaptor.forClass(DockerHttpClient.Request.class);
        verify(http).execute(sent.capture());
        assertThat(sent.getValue().method()).isEqualTo("GET");
        assertThat(sent.getValue().path()).endsWith("/system/df");
    }

    @Test
    void clientWithoutHttpTransportIsInvalidResponseNotNpe() {
        DockerClient notImpl = mock(DockerClient.class);
        assertThatThrownBy(() -> DockerDiskUsageReader.read(notImpl))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_RESPONSE));
    }
}

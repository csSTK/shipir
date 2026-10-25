package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Info;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DockerSystemServiceImpl implements DockerSystemService {

    private static final Logger log = LoggerFactory.getLogger(DockerSystemServiceImpl.class);

    private final DockerHostConnectionManager manager;
    private final DockerCallSupport calls;

    public DockerSystemServiceImpl(DockerHostConnectionManager manager) {
        this.manager = manager;
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public ConnectionTestResult ping(DockerEndpoint endpoint) {
        try (DockerClient client = manager.createClient(endpoint)) {
            String version = calls.callWithDeadline(() -> client.versionCmd().exec().getVersion(), () -> closeQuietly(client));
            return new ConnectionTestResult(true, version, null, null);
        } catch (Exception e) {
            DockerConnectionException failure = calls.translate(e);
            return new ConnectionTestResult(false, null, failure.getCode(), failure.getMessage());
        }
    }

    @Override
    public DockerSystemInfoDto info(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, DockerSystemServiceImpl::collectInfo);
    }

    @Override
    public DiskUsageDto diskUsage(UUID hostId, DockerEndpoint endpoint) {
        return calls.call(hostId, endpoint, DockerDiskUsageReader::read);
    }

    private static DockerSystemInfoDto collectInfo(DockerClient client) {
        Info info = client.infoCmd().exec();
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        // docker-java returns null instead of an empty list when the daemon has no volumes
        List<InspectVolumeResponse> volumeList = client.listVolumesCmd().exec().getVolumes();
        int volumes = volumeList == null ? 0 : volumeList.size();
        return new DockerSystemInfoDto(
                info.getServerVersion(),
                nullToZero(info.getNCPU()),
                nullToZero(info.getMemTotal()),
                nullToZero(info.getContainersRunning()),
                nullToZero(info.getContainersStopped()),
                countStatus(containers, "(healthy)"),
                countStatus(containers, "(unhealthy)"),
                nullToZero(info.getImages()),
                volumes);
    }

    private static void closeQuietly(DockerClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Closing Docker client failed: {}", e.toString());
        }
    }

    /** The health state is only available as suffix of the status text, e.g. {@code Up 5 seconds (healthy)}. */
    private static int countStatus(List<Container> containers, String marker) {
        return (int) containers.stream()
                .filter(c -> c.getStatus() != null && c.getStatus().contains(marker))
                .count();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0 : value;
    }
}

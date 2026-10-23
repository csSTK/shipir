package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;

import java.util.UUID;

public interface DockerSystemService {

    /** Connection test with a throwaway client; never throws for connection problems. */
    ConnectionTestResult ping(DockerEndpoint endpoint);

    /** Overview of the host, using the cached client for {@code hostId}. */
    DockerSystemInfoDto info(UUID hostId, DockerEndpoint endpoint);
}

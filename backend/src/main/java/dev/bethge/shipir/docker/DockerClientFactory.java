package dev.bethge.shipir.docker;

import com.github.dockerjava.api.DockerClient;

/** Creates a docker-java client for an endpoint; a seam so the manager can be tested with fakes. */
interface DockerClientFactory {

    DockerClient create(DockerEndpoint endpoint);
}

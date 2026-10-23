package dev.bethge.shipir.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.net.URI;

/** Builds docker-java clients over the httpclient5 transport with the configured timeouts. */
final class DefaultDockerClientFactory implements DockerClientFactory {

    private final DockerProperties properties;

    DefaultDockerClientFactory(DockerProperties properties) {
        this.properties = properties;
    }

    @Override
    public DockerClient create(DockerEndpoint endpoint) {
        return switch (endpoint) {
            case DockerEndpoint.Local local -> build(URI.create(local.socketPath()));
            case DockerEndpoint.Tcp ignored ->
                    throw new UnsupportedOperationException("TCP endpoints are not supported yet");
        };
    }

    private DockerClient build(URI dockerHost) {
        // Ignore DOCKER_HOST / DOCKER_TLS_VERIFY etc. from the process environment: the endpoint is explicit.
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost.toString())
                .withDockerTlsVerify(false)
                .withDockerCertPath(null)
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(properties.connectTimeout())
                .responseTimeout(properties.readTimeout())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}

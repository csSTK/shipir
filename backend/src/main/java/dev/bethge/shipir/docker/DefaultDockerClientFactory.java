package dev.bethge.shipir.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.SSLConfig;
import dev.bethge.shipir.docker.tls.PemSslConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.net.URI;
import java.net.URISyntaxException;

/** Builds docker-java clients over the httpclient5 transport with the configured timeouts. */
final class DefaultDockerClientFactory implements DockerClientFactory {

    private final DockerProperties properties;

    DefaultDockerClientFactory(DockerProperties properties) {
        this.properties = properties;
    }

    @Override
    public DockerClient create(DockerEndpoint endpoint) {
        return switch (endpoint) {
            case DockerEndpoint.Local local -> build(URI.create(local.socketPath()), null);
            case DockerEndpoint.Tcp tcp -> build(tcpUri(tcp), tcp.tls() == null ? null : new PemSslConfig(tcp.tls()));
        };
    }

    private static URI tcpUri(DockerEndpoint.Tcp tcp) {
        String host = tcp.host();
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]"; // IPv6 literal
        }
        try {
            return new URI("tcp", null, host, tcp.port(), null, null, null);
        } catch (URISyntaxException | RuntimeException e) {
            throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "The Docker host address is invalid.", null);
        }
    }

    private DockerClient build(URI dockerHost, SSLConfig sslConfig) {
        // Ignore DOCKER_HOST / DOCKER_TLS_VERIFY etc. from the process environment: the endpoint is explicit.
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost.toString())
                .withDockerTlsVerify(false)
                .withDockerCertPath(null)
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(sslConfig)
                .connectionTimeout(properties.connectTimeout())
                .responseTimeout(properties.readTimeout())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}

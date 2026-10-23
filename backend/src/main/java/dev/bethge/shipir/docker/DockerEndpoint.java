package dev.bethge.shipir.docker;

/** How to reach a Docker daemon; our own type, so callers never see docker-java. */
public sealed interface DockerEndpoint {

    /** Unix socket on the machine Shipir runs on, e.g. {@code unix:///var/run/docker.sock}. */
    record Local(String socketPath) implements DockerEndpoint {
    }

    /** Remote daemon over TCP, optionally with TLS client certificates. */
    record Tcp(String host, int port, TlsMaterial tls) implements DockerEndpoint {
    }

    /** PEM-encoded TLS material, held in memory only. {@code toString} never prints it. */
    record TlsMaterial(String caPem, String certPem, String keyPem) {

        @Override
        public String toString() {
            return "TlsMaterial[<hidden>]";
        }
    }
}

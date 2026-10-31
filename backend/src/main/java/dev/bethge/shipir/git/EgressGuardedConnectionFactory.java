package dev.bethge.shipir.git;

import dev.bethge.shipir.common.net.EgressPolicy;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.time.Duration;

/**
 * JGit opens one connection per HTTP request, including each redirect hop. Every connection is created here: the host
 * is resolved once through the {@link EgressPolicy} (all addresses must be allowed) and the returned
 * {@link PinnedHttpConnection} connects to exactly those addresses, so redirects are re-validated and a DNS answer that
 * flips after the check cannot redirect the request. A configured proxy is ignored (the connection is always direct).
 */
final class EgressGuardedConnectionFactory implements HttpConnectionFactory2 {

    private final EgressPolicy policy;
    private final Duration connectTimeout;

    EgressGuardedConnectionFactory(EgressPolicy policy, Duration connectTimeout) {
        this.policy = policy;
        this.connectTimeout = connectTimeout;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        String protocol = url.getProtocol();
        if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol)) {
            throw new IOException("Unsupported protocol");
        }
        InetAddress[] validated = policy.resolve(url.getHost());
        return new PinnedHttpConnection(url, validated, connectTimeout);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        return create(url);
    }

    @Override
    public GitSession newSession() {
        return new GitSession() {
            @Override
            public HttpConnection configure(HttpConnection connection, boolean sslVerify) {
                // verification is never relaxed, whatever the git configuration says
                return connection;
            }

            @Override
            public void close() {
                // nothing is shared between connections
            }
        };
    }
}

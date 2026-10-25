package dev.bethge.shipir.git;

import dev.bethge.shipir.common.net.EgressPolicy;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;

/**
 * JGit opens one connection per HTTP request, including each redirect hop. Checking the {@link EgressPolicy} here
 * therefore also re-validates redirect targets. JGit resolves the name itself afterwards, so unlike
 * {@code EgressHttpClient} a DNS answer that flips between this check and the connect is not excluded (JGit offers no
 * resolver hook); the window is the time between two calls inside one request.
 */
final class EgressGuardedConnectionFactory implements HttpConnectionFactory2 {

    private final EgressPolicy policy;
    private final HttpConnectionFactory2 delegate = new JDKHttpConnectionFactory();

    EgressGuardedConnectionFactory(EgressPolicy policy) {
        this.policy = policy;
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        policy.check(url.getHost());
        return delegate.create(url);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        policy.check(url.getHost());
        return delegate.create(url, proxy);
    }

    @Override
    public GitSession newSession() {
        GitSession session = delegate.newSession();
        return new GitSession() {
            @Override
            public HttpConnection configure(HttpConnection connection, boolean sslVerify)
                    throws IOException, GeneralSecurityException {
                return session.configure(connection, sslVerify);
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }
}

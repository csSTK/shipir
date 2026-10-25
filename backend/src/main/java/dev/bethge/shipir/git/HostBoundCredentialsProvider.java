package dev.bethge.shipir.git;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Hands out the username and token only to the host the credential is bound to. JGit asks the provider for every
 * request, also after a redirect, with the URI it is about to contact; for any other host nothing is filled in and the
 * request goes out unauthenticated, so a redirect can never carry the token to another host.
 */
final class HostBoundCredentialsProvider extends CredentialsProvider {

    private final String boundHost;
    private final CredentialsProvider delegate;

    HostBoundCredentialsProvider(String boundHost, String username, String token) {
        this.boundHost = boundHost;
        this.delegate = new UsernamePasswordCredentialsProvider(username, token);
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        return delegate.supports(items);
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        if (uri == null || !GitHostBinding.matches(boundHost, uri.getHost(), uri.getPort(), uri.getScheme())) {
            return false;
        }
        return delegate.get(uri, items);
    }
}

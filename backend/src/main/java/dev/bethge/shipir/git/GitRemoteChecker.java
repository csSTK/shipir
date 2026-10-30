package dev.bethge.shipir.git;

import dev.bethge.shipir.common.net.EgressPolicy;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.transport.TransportHttp;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/** Runs {@code ls-remote} through JGit (no git binary, no shell) and classifies the outcome. */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(GitProperties.class)
class GitRemoteChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String UNREACHABLE = "UNREACHABLE";

    private final GitProperties properties;
    private final EgressGuardedConnectionFactory connections;

    GitRemoteChecker(GitProperties properties, EgressPolicy policy) {
        this.properties = properties;
        this.connections = new EgressGuardedConnectionFactory(policy);
    }

    /** Throws {@link GitException} (400 INVALID_REPO_URL) unless the URL is an absolute https URL with a host and no userinfo. */
    void validateUrl(String repoUrl) {
        URI uri;
        try {
            uri = new URI(repoUrl.trim());
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme)
                || (properties.allowInsecureHttp() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw invalid();
        }
    }

    /**
     * Routes every HTTP connection of {@code command} through the egress policy: loopback, private, link-local and
     * metadata targets (also reached by a redirect) fail like any unreachable host.
     */
    void guard(TransportCommand<?, ?> command) {
        command.setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp http) {
                http.setHttpConnectionFactory(connections);
            }
        });
    }

    String check(String repoUrl, String boundHost, String username, String token) {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(repoUrl.trim())
                .setHeads(true)
                .setTimeout((int) Math.max(1, properties.connectTimeout().toSeconds()))
                .setCredentialsProvider(new HostBoundCredentialsProvider(boundHost, username, token));
        guard(cmd);
        try {
            cmd.call();
            return OK;
        } catch (TransportException e) {
            return classify(e);
        } catch (Exception e) {
            return UNREACHABLE;
        }
    }

    /**
     * Classifies by exception type, never by searching the message: JGit echoes the repository URL in its messages, so
     * a URL like {@code https://h/401/not-found.git} must not decide the outcome. 404 surfaces as
     * {@link NoRemoteRepositoryException}. JGit has no typed exception for 401/403, only a fixed reason after the
     * URL ({@code "<uri>: <reason>"}); the URL contains no whitespace, so the reason is everything after the first one.
     */
    static String classify(TransportException e) {
        boolean authRejected = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof NoRemoteRepositoryException) {
                return NOT_FOUND;
            }
            if (c instanceof org.eclipse.jgit.errors.TransportException && isAuthReason(reason(c.getMessage()))) {
                authRejected = true;
            }
        }
        return authRejected ? AUTH_FAILED : UNREACHABLE;
    }

    /** True if {@code message} is JGit's "Remote does not have &lt;sha&gt; available for fetch." (no URL in it). */
    static boolean isMissingFetchSpec(TransportException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.eclipse.jgit.errors.TransportException
                    && c.getMessage() != null && c.getMessage().startsWith("Remote does not have ")
                    && c.getMessage().endsWith(" available for fetch.")) {
                return true;
            }
        }
        return e.getMessage() != null && e.getMessage().startsWith("Remote does not have ")
                && e.getMessage().endsWith(" available for fetch.");
    }

    private static String reason(String message) {
        if (message == null) {
            return "";
        }
        int ws = -1;
        for (int i = 0; i < message.length(); i++) {
            if (Character.isWhitespace(message.charAt(i))) {
                ws = i;
                break;
            }
        }
        return ws < 0 ? "" : message.substring(ws + 1);
    }

    // JGitText: notAuthorized (401 with credential), authMissing (401 without), authenticationNotSupported, serviceNotPermitted (403)
    private static boolean isAuthReason(String reason) {
        return reason.equals("not authorized") || reason.equals("authentication not supported")
                || reason.equals("Authentication is required but no CredentialsProvider has been registered")
                || reason.matches("(?s)[\\w-]+ not permitted on '.*");
    }

    private static GitException invalid() {
        return new GitException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REPO_URL",
                "The repository URL must be an absolute https URL without embedded credentials");
    }
}

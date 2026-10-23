package dev.bethge.shipir.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Runs {@code ls-remote} through JGit (no git binary, no shell) and classifies the outcome. */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(GitProperties.class)
class GitRemoteChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String UNREACHABLE = "UNREACHABLE";

    private final GitProperties properties;

    GitRemoteChecker(GitProperties properties) {
        this.properties = properties;
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

    String check(String repoUrl, String username, String token) {
        LsRemoteCommand cmd = Git.lsRemoteRepository()
                .setRemote(repoUrl.trim())
                .setHeads(true)
                .setTimeout((int) Math.max(1, properties.connectTimeout().toSeconds()))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
        try {
            cmd.call();
            return OK;
        } catch (TransportException e) {
            return classify(e);
        } catch (Exception e) {
            return UNREACHABLE;
        }
    }

    private static String classify(TransportException e) {
        if (e.getCause() instanceof NoRemoteRepositoryException || hasMessage(e, "not found")) {
            return NOT_FOUND;
        }
        if (hasMessage(e, "not authorized") || hasMessage(e, "authentication") || hasMessage(e, "forbidden")
                || hasMessage(e, "401") || hasMessage(e, "403")) {
            return AUTH_FAILED;
        }
        return UNREACHABLE;
    }

    private static boolean hasMessage(Throwable t, String part) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().toLowerCase(Locale.ROOT).contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static GitException invalid() {
        return new GitException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REPO_URL",
                "The repository URL must be an absolute https URL without embedded credentials");
    }
}

package dev.bethge.shipir.git;

import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JGit echoes the repository URL in its messages, so classification must rely on exception types and the fixed JGit
 * reason text, never on substrings anywhere in the message.
 */
class GitRemoteCheckerTest {

    /** A URL that contains every word the old substring matching reacted to. */
    private static final String NASTY = "https://git.example/401/403/forbidden/not-found/authentication.git";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static TransportException wrap(org.eclipse.jgit.errors.TransportException cause) {
        return new TransportException(cause.getMessage(), cause);
    }

    private static TransportException unreachable() throws Exception {
        return wrap(new org.eclipse.jgit.errors.TransportException(new URIish(NASTY), "Connection refused"));
    }

    @Test
    void unreachableHostWithMisleadingUrlIsNotClassifiedByUrlText() throws Exception {
        assertThat(GitRemoteChecker.classify(unreachable())).isEqualTo(GitRemoteChecker.UNREACHABLE);
    }

    @Test
    void noRemoteRepositoryIsNotFoundByType() throws Exception {
        var cause = new NoRemoteRepositoryException(new URIish(NASTY), "gone");
        assertThat(GitRemoteChecker.classify(wrap(cause))).isEqualTo(GitRemoteChecker.NOT_FOUND);
    }

    @Test
    void unauthorizedIsAuthFailed() throws Exception {
        var cause = new org.eclipse.jgit.errors.TransportException(new URIish(NASTY), "not authorized");
        assertThat(GitRemoteChecker.classify(wrap(cause))).isEqualTo(GitRemoteChecker.AUTH_FAILED);
    }

    @Test
    void serviceNotPermittedIsAuthFailed() throws Exception {
        var cause = new org.eclipse.jgit.errors.TransportException(new URIish(NASTY),
                "git-upload-pack not permitted on '" + NASTY + "'");
        assertThat(GitRemoteChecker.classify(wrap(cause))).isEqualTo(GitRemoteChecker.AUTH_FAILED);
    }

    @Test
    void authenticationNotSupportedIsAuthFailed() throws Exception {
        var cause = new org.eclipse.jgit.errors.TransportException(new URIish(NASTY), "authentication not supported");
        assertThat(GitRemoteChecker.classify(wrap(cause))).isEqualTo(GitRemoteChecker.AUTH_FAILED);
    }

    @Test
    void fetchBySha_unreachableWithMisleadingUrlIsNotRefNotFound() throws Exception {
        assertThat(GitRepositoryService.map(unreachable(), true).code()).isEqualTo("GIT_UNREACHABLE");
    }

    @Test
    void fetchBySha_remoteWithoutThatCommitIsRefNotFound() {
        var e = new TransportException("Remote does not have " + SHA + " available for fetch.");
        assertThat(GitRepositoryService.map(e, true).code()).isEqualTo("GIT_REF_NOT_FOUND");
    }

    @Test
    void transportFailureWithMisleadingUrlMapsToUnreachable() throws Exception {
        assertThat(GitRepositoryService.map(unreachable(), false).code()).isEqualTo("GIT_UNREACHABLE");
    }
}

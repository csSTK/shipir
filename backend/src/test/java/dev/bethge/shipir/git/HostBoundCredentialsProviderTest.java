package dev.bethge.shipir.git;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostBoundCredentialsProviderTest {

    private final HostBoundCredentialsProvider provider = new HostBoundCredentialsProvider("github.com", "octocat", "tok");

    private boolean ask(String uri, CredentialItem.Username user, CredentialItem.Password pass) throws Exception {
        return provider.get(new URIish(uri), user, pass);
    }

    @Test
    void fillsUsernameAndTokenForTheBoundHost() throws Exception {
        var user = new CredentialItem.Username();
        var pass = new CredentialItem.Password();
        assertThat(ask("https://GitHub.com./o/r.git", user, pass)).isTrue();
        assertThat(user.getValue()).isEqualTo("octocat");
        assertThat(new String(pass.getValue())).isEqualTo("tok");
    }

    @Test
    void fillsNothingForAnyOtherHostSoARedirectCannotCarryTheToken() throws Exception {
        for (String foreign : new String[]{"https://evil.example/x.git", "https://api.github.com/x.git",
                "https://github.com.evil.example/x.git", "http://localhost:8080/x.git"}) {
            var user = new CredentialItem.Username();
            var pass = new CredentialItem.Password();
            assertThat(ask(foreign, user, pass)).as(foreign).isFalse();
            assertThat(user.getValue()).isNull();
            assertThat(pass.getValue()).isNull();
        }
    }

    @Test
    void isNeverInteractive() {
        assertThat(provider.isInteractive()).isFalse();
    }
}

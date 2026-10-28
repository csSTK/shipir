package dev.bethge.shipir.config_editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EnvLikeFilesTest {

    @ParameterizedTest
    @ValueSource(strings = {".env", ".ENV", ".env.local", "repo/.env", "db.env", "DB.Env", "a\\b\\.env", "secrets/x.conf",
            "my_SECRET_file.txt", "a.pem", "A.PEM", "tls/b.key", "dir/.envrc",
            "a.p12", "A.P12", "tls/b.pfx", "B.PFX", "id_rsa", "ID_RSA", ".ssh/id_rsa", "credentials", "credentials.json",
            "Credentials.yaml", "aws/credentials", "credentials/x.txt"})
    void matchesEnvLikePaths(String path) {
        assertThat(EnvLikeFiles.matches(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nginx/site.conf", "app.yaml", "environment.txt", "repo/README", "keystore.txt", "monkey.json", "pem.txt",
            "mycredentials.txt", "p12.txt", "x.p12x", "id_rsa.pub", "my_id_rsa"})
    void ordinaryFilesDoNotMatch(String path) {
        assertThat(EnvLikeFiles.matches(path)).as(path).isFalse();
    }

    @Test
    void nullDoesNotMatch() {
        assertThat(EnvLikeFiles.matches(null)).isFalse();
    }
}

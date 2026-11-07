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
            "Credentials.yaml", "aws/credentials", "credentials/x.txt",
            "id_ed25519", "ID_ED25519", ".ssh/id_ecdsa", "id_dsa", "a.jks", "A.JKS", "tls/b.keystore", "db.kdbx", "DB.KDBX",
            ".netrc", ".NETRC", "home/.pgpass", ".PGPASS",
            "id_rsa.bak", "id_ed25519_sk", "ID_ECDSA_SK", ".ssh/id_rsa.old", "id_ed25519-backup", ".netrc.bak", ".pgpass.bak", ".PGPASS~",
            "AuthKey.p8", "k.P8", "putty/k.ppk", "K.PPK",
            "id_rsa.", "id_rsa ", "id_rsa. .", "a.pem.", "db.kdbx ", ".netrc.", "x.p8 ", "k.ppk..", ".env."})
    void matchesEnvLikePaths(String path) {
        assertThat(EnvLikeFiles.matches(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nginx/site.conf", "app.yaml", "environment.txt", "repo/README", "keystore.txt", "monkey.json", "pem.txt",
            "mycredentials.txt", "p12.txt", "x.p12x", "id_rsa.pub", "my_id_rsa", "id_ed25519.pub", "id_ecdsa.pub", "id_dsa.pub", "my_id_ed25519", "x.jks.txt", "netrc", "pgpass", "a.netrc", "x.pgpass", "kdbx.txt", "id_rsa.pub", "id_ed25519_sk.pub", ".ssh/id_rsa.pub ", "id_rsa.pub.", "x.p8x", "p8.txt", "ppk.txt", "a.ppk.txt", "my.netrc.bak", "pgpass.bak"})
    void ordinaryFilesDoNotMatch(String path) {
        assertThat(EnvLikeFiles.matches(path)).as(path).isFalse();
    }

    @Test
    void nullDoesNotMatch() {
        assertThat(EnvLikeFiles.matches(null)).isFalse();
    }
}

package dev.bethge.shipir.compose.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliPluginDirsTest {

    @TempDir
    Path tmp;

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name));
    }

    @Test
    void usesHomeDockerDirWhenNoDockerConfigEnv() throws IOException {
        Path plugins = dir("home/.docker/cli-plugins");
        assertThat(CliPluginDirs.resolve(Map.of(), tmp.resolve("home"), List.of()))
                .containsExactly(plugins.toString());
    }

    @Test
    void dockerConfigEnvWinsOverHome() throws IOException {
        dir("home/.docker/cli-plugins");
        Path plugins = dir("cfg/cli-plugins");
        assertThat(CliPluginDirs.resolve(Map.of("DOCKER_CONFIG", tmp.resolve("cfg").toString()),
                tmp.resolve("home"), List.of())).containsExactly(plugins.toString());
    }

    @Test
    void addsExtraDirsFromConfigJsonAndSystemDirsInOrderSkippingMissing() throws IOException {
        Path own = dir("cfg/cli-plugins");
        Path extra = dir("extra");
        Path system = dir("system");
        Files.writeString(tmp.resolve("cfg/config.json"), "{\"auths\":{},\"cliPluginsExtraDirs\":[\""
                + extra + "\",\"" + tmp.resolve("gone") + "\",7]}");
        assertThat(CliPluginDirs.resolve(Map.of("DOCKER_CONFIG", tmp.resolve("cfg").toString()), tmp,
                List.of(system, tmp.resolve("missing"), extra)))
                .containsExactly(own.toString(), extra.toString(), system.toString());
    }

    @Test
    void unreadableConfigJsonIsIgnored() throws IOException {
        dir("cfg");
        Files.writeString(tmp.resolve("cfg/config.json"), "not json");
        Path system = dir("system");
        assertThat(CliPluginDirs.resolve(Map.of("DOCKER_CONFIG", tmp.resolve("cfg").toString()), tmp, List.of(system)))
                .containsExactly(system.toString());
    }
}

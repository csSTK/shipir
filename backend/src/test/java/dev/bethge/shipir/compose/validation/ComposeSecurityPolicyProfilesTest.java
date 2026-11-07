package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.DeploymentProperties;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The policy must see services behind a compose profile too, so it asks compose for the config with all profiles on. */
class ComposeSecurityPolicyProfilesTest {

    @TempDir
    Path tmp;

    @Test
    void readsTheConfigWithAllProfilesEnabled() {
        UUID host = UUID.randomUUID();
        ComposeFileRepository files = new ComposeFileRepository(new DeploymentProperties(tmp));
        files.write(host, "web", "services: {}\n", null);
        ComposeExecutionService execution = mock(ComposeExecutionService.class);
        when(execution.execute(any(), any(), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), any()))
                .thenReturn(new ComposeProcessResult(0, false, List.of("{\"name\":\"web\",\"services\":{}}")));

        List<String> violations = new ComposeSecurityPolicy(execution, files, mock(dev.bethge.shipir.docker.client.VolumeService.class))
                .violations(host, "web", new DockerEndpoint.Local("unix:///var/run/docker.sock"), null);

        assertThat(violations).isEmpty();
        ArgumentCaptor<ComposeOptions> options = ArgumentCaptor.forClass(ComposeOptions.class);
        verify(execution).execute(eq(host), eq("web"), eq(ComposeCommand.CONFIG_JSON), any(), any(), any(), any(), options.capture());
        assertThat(options.getValue().profiles()).containsExactly("*");
        assertThat(options.getValue().services()).isEmpty();
    }
}

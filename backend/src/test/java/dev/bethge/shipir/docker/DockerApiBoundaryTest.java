package dev.bethge.shipir.docker;

import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No docker-java type may be part of the API other features use. {@code DockerHostConnectionManager.client/createClient}
 * hand the client to docker/ internals and are excluded; everything else must be free of docker-java types.
 */
class DockerApiBoundaryTest {

    private static final Set<String> INTERNAL_METHODS = Set.of("client", "createClient");

    @Test
    void publicApiExposesNoDockerJavaTypes() {
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : List.of(DockerEndpoint.class, DockerEndpoint.Local.class, DockerEndpoint.Tcp.class,
                DockerEndpoint.TlsMaterial.class, DockerErrorCode.class, DockerConnectionException.class,
                DockerProperties.class, DockerHostConnectionManager.class, DockerSystemService.class,
                ConnectionTestResult.class, DockerSystemInfoDto.class, ContainerService.class,
                ContainerDto.class, DeploymentSummaryDto.class, ImageService.class, NetworkService.class,
                VolumeService.class, ImageDto.class, NetworkDto.class, VolumeDto.class)) {
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || INTERNAL_METHODS.contains(m.getName())) continue;
                check(leaks, type, m.getName(), m.getReturnType());
                for (Class<?> p : m.getParameterTypes()) check(leaks, type, m.getName(), p);
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(c.getModifiers())) continue;
                for (Class<?> p : c.getParameterTypes()) check(leaks, type, "<init>", p);
            }
            if (type.isRecord()) {
                for (RecordComponent rc : type.getRecordComponents()) check(leaks, type, rc.getName(), rc.getType());
            }
        }
        assertThat(leaks).isEmpty();
    }

    private static void check(List<String> leaks, Class<?> owner, String member, Class<?> type) {
        if (type.getName().startsWith("com.github.dockerjava")) {
            leaks.add(owner.getSimpleName() + "." + member + " -> " + type.getName());
        }
    }
}

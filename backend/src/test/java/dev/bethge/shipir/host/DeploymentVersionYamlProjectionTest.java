package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.DeploymentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** shipr-0qbh.79: the system-managed guard reads only the yaml of each version (no env ciphertext), newest first. */
class DeploymentVersionYamlProjectionTest extends HostApiTestBase {

    @Autowired DeploymentVersionRepository versions;

    @Test
    void yamlProjectionReturnsOnlyYamlNewestFirst() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'projhost', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'projhost'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'projdep', ?, 'x', id FROM users WHERE username = 'admin'", host);
        UUID dep = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'projdep'", UUID.class);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, env_ciphertext, created_by, created_at) "
                + "SELECT ?, 'old', '\\x0102'::bytea, id, now() - interval '1 day' FROM users WHERE username = 'admin'", dep);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, env_ciphertext, created_by, created_at) "
                + "SELECT ?, 'new', '\\x0304'::bytea, id, now() FROM users WHERE username = 'admin'", dep);
        List<String> yaml = versions.findYamlContentByDeploymentId(dep);
        assertThat(yaml).containsExactly("new", "old");
    }
}

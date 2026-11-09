package dev.bethge.shipir.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemasTest {

    @Test
    void uuidPropertyIsAStringWithUuidFormat() {
        assertThat(ToolSchemas.UUID_PROP).containsExactlyInAnyOrderEntriesOf(
                Map.of("type", "string", "format", "uuid"));
    }

    @Test
    void schemaWithoutRequiredArgumentsOmitsTheRequiredKey() {
        Map<String, Object> props = Map.of("page", Map.of("type", "integer"));

        assertThat(ToolSchemas.schema(props))
                .containsExactlyInAnyOrderEntriesOf(Map.of("type", "object", "properties", props))
                .doesNotContainKey("required");
    }

    @Test
    void schemaListsTheRequiredArgumentsInOrder() {
        Map<String, Object> props = Map.of("hostId", ToolSchemas.UUID_PROP, "name", Map.of("type", "string"));

        Map<String, Object> schema = ToolSchemas.schema(props, "name", "hostId");

        assertThat(schema).containsEntry("type", "object").containsEntry("properties", props)
                .containsEntry("required", List.of("name", "hostId"));
    }

    @Test
    void hostSchemaRequiresTheHostId() {
        assertThat(ToolSchemas.hostSchema())
                .containsEntry("properties", Map.of("hostId", ToolSchemas.UUID_PROP))
                .containsEntry("required", List.of("hostId"));
    }

    @Test
    void deploymentSchemaRequiresTheHostAndDeploymentId() {
        assertThat(ToolSchemas.deploymentSchema())
                .containsEntry("properties", Map.of("hostId", ToolSchemas.UUID_PROP, "deploymentId", ToolSchemas.UUID_PROP))
                .containsEntry("required", List.of("hostId", "deploymentId"));
    }
}

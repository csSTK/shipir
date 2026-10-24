package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.ComposeGeneratorService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateCatalogTest {

    private final ComposeGeneratorService generator = new ComposeGeneratorService();
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    private TemplateCatalog bundled() {
        return new TemplateCatalog(generator, new ClassPathResource("templates/apps.json"));
    }

    private TemplateCatalog load(String json) {
        return new TemplateCatalog(generator, new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledCatalogHasAtLeastSixTemplates() {
        assertThat(bundled().all()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void everyBundledTemplateGeneratesValidYaml() {
        for (AppTemplate template : bundled().all()) {
            String generated = generator.generate(template.spec());
            var tree = yaml.readTree(generated);
            assertThat(tree.get("services").properties())
                    .as("services of %s", template.id())
                    .extracting(e -> e.getKey())
                    .containsExactlyElementsOf(template.spec().services().stream().map(s -> s.name()).toList());
            for (var service : template.spec().services()) {
                assertThat(tree.get("services").get(service.name()).get("image").asString())
                        .isEqualTo(service.image());
            }
        }
    }

    @Test
    void bundledTemplatesHaveUniqueIdsAndDescriptions() {
        var templates = bundled().all();
        assertThat(templates.stream().map(AppTemplate::id).distinct()).hasSize(templates.size());
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.name()).isNotBlank();
            assertThat(t.description()).isNotBlank();
            assertThat(t.logo()).isNotBlank();
        });
    }

    @Test
    void invalidBundledSpecFailsCatalogLoad() {
        String json = """
                [{"id":"broken","name":"Broken","description":"d","logo":"x",
                  "spec":{"services":[{"name":"web","image":"nginx","ports":[{"container":0}]}]}}]""";
        assertThatThrownBy(() -> load(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("container port must be 1-65535");
    }

    @Test
    void malformedJsonFailsCatalogLoad() {
        assertThatThrownBy(() -> load("[{"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("templates/apps.json");
    }

    @Test
    void unknownPropertyFailsCatalogLoad() {
        String json = """
                [{"id":"typo","name":"T","description":"d","logo":"x","colour":"red",
                  "spec":{"services":[{"name":"web","image":"nginx"}]}}]""";
        assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateIdFailsCatalogLoad() {
        String one = """
                {"id":"same","name":"T","description":"d","logo":"x",
                 "spec":{"services":[{"name":"web","image":"nginx"}]}}""";
        assertThatThrownBy(() -> load("[" + one + "," + one + "]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    void invalidIdFailsCatalogLoad() {
        String json = """
                [{"id":"Bad Id","name":"T","description":"d","logo":"x",
                  "spec":{"services":[{"name":"web","image":"nginx"}]}}]""";
        assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bad Id");
    }
}

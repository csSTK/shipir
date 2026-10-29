package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.ComposeGeneratorService;
import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import dev.bethge.shipir.compose.generator.model.EnvVarSpec;
import dev.bethge.shipir.compose.generator.model.PortSpec;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        });
    }

    @Test
    void bundledCatalogIncludesTheCuratedSelfHostedApps() {
        assertThat(bundled().all().stream().map(AppTemplate::id).toList())
                .contains("vaultwarden", "gitea", "grafana", "jellyfin", "home-assistant", "nextcloud",
                        "pihole", "nginx-proxy-manager", "homepage", "filebrowser", "syncthing",
                        "mysql", "mongodb", "rabbitmq", "minio", "influxdb",
                        "ghost", "n8n", "paperless-ngx", "bookstack", "linkding");
    }

    @Test
    void bundledImagesArePinnedAndNeverLatest() {
        for (AppTemplate t : bundled().all()) {
            for (ServiceSpec s : services(t)) {
                assertThat(s.image())
                        .as("image of %s/%s", t.id(), s.name())
                        .matches(".+:[^:/]+")
                        .doesNotEndWith(":latest");
            }
        }
    }

    @Test
    void bundledHostPortsAreUniqueAcrossTemplates() {
        assertThat(hostPortCollisions(bundled().all())).isEmpty();
    }

    @Test
    void hostPortCollisionNamesBothOwners() {
        AppTemplate a = template("a", 8080);
        AppTemplate b = template("b", 8080);
        assertThat(hostPortCollisions(List.of(a, b)))
                .containsExactly("host port 8080/tcp used by a/web and b/web");
    }

    /** One message per host port claimed by a second service; each names the earlier and the later owner. */
    static List<String> hostPortCollisions(List<AppTemplate> templates) {
        Map<String, String> owners = new HashMap<>();
        List<String> collisions = new ArrayList<>();
        for (AppTemplate t : templates) {
            for (ServiceSpec s : services(t)) {
                if (s.ports() == null) {
                    continue;
                }
                for (PortSpec port : s.ports()) {
                    if (port.host() != null) {
                        String owner = t.id() + "/" + s.name();
                        String protocol = port.protocol() == null ? "tcp" : port.protocol();
                        String key = port.host() + "/" + protocol;
                        String previous = owners.put(key, owner);
                        if (previous != null) {
                            collisions.add(String.format("host port %s used by %s and %s", key, previous, owner));
                        }
                    }
                }
            }
        }
        return collisions;
    }

    private static AppTemplate template(String id, int hostPort) {
        ServiceSpec web = new ServiceSpec("web", "nginx:1.27", null, null,
                List.of(new PortSpec(hostPort, 80, "tcp")), null, null, null, null, null);
        return new AppTemplate(id, id, "d", List.of(), new ComposeSpec(List.of(web), null, null));
    }

    @Test
    void credentialsAreReferencedFromSecretHintsNeverWrittenIntoTheSpec() {
        for (AppTemplate t : bundled().all()) {
            for (ServiceSpec s : services(t)) {
                for (EnvVarSpec env : environment(s)) {
                    if (env.key().matches("(?i).*(PASSWORD|TOKEN|SECRET|KEY).*")) {
                        assertThat(env.value())
                                .as("%s/%s env %s must not carry a literal value", t.id(), s.name(), env.key())
                                .isNull();
                        assertThat(env.ref())
                                .as("%s/%s env %s must reference a secret hint", t.id(), s.name(), env.key())
                                .isNotBlank();
                        assertThat(t.env().stream().anyMatch(h -> h.key().equals(env.ref()) && h.secret()))
                                .as("%s declares secret hint %s", t.id(), env.ref())
                                .isTrue();
                    }
                }
            }
        }
    }

    private static List<ServiceSpec> services(AppTemplate t) {
        return t.spec().services();
    }

    private static List<EnvVarSpec> environment(ServiceSpec s) {
        return s.environment() == null ? List.of() : s.environment();
    }

    @Test
    void invalidBundledSpecFailsCatalogLoad() {
        String json = """
                [{"id":"broken","name":"Broken","description":"d",
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
                [{"id":"typo","name":"T","description":"d","colour":"red",
                  "spec":{"services":[{"name":"web","image":"nginx"}]}}]""";
        assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateIdFailsCatalogLoad() {
        String one = """
                {"id":"same","name":"T","description":"d",
                 "spec":{"services":[{"name":"web","image":"nginx"}]}}""";
        assertThatThrownBy(() -> load("[" + one + "," + one + "]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    void invalidIdFailsCatalogLoad() {
        String json = """
                [{"id":"Bad Id","name":"T","description":"d",
                  "spec":{"services":[{"name":"web","image":"nginx"}]}}]""";
        assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bad Id");
    }

    private static String withHint(String hint) {
        return """
                [{"id":"hinted","name":"T","description":"d","logo":"x",
                  "env":[%s],
                  "spec":{"services":[{"name":"web","image":"nginx","environment":[{"key":"A","ref":"A"}]}]}}]""".formatted(hint);
    }

    @Test
    void parsesDefaultValueAndGenerateOnEnvHints() {
        var t = load(withHint("""
                {"key":"A","description":"d","secret":false,"defaultValue":"x"},
                {"key":"S","description":"d","secret":true,"generate":true}""")).all().get(0);
        assertThat(t.env().get(0).defaultValue()).isEqualTo("x");
        assertThat(t.env().get(0).generate()).isFalse();
        assertThat(t.env().get(1).generate()).isTrue();
        assertThat(t.env().get(1).defaultValue()).isNull();
    }

    @Test
    void generateWithoutSecretFailsCatalogLoad() {
        assertThatThrownBy(() -> load(withHint("{\"key\":\"A\",\"description\":\"d\",\"secret\":false,\"generate\":true}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hinted")
                .hasMessageContaining("generate");
    }

    @Test
    void defaultValueOnSecretFailsCatalogLoad() {
        assertThatThrownBy(() -> load(withHint("{\"key\":\"A\",\"description\":\"d\",\"secret\":true,\"defaultValue\":\"pw\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hinted")
                .hasMessageContaining("defaultValue");
    }

    @Test
    void defaultValueForKeyNotInSpecFailsCatalogLoad() {
        assertThatThrownBy(() -> load(withHint("{\"key\":\"Z\",\"description\":\"d\",\"secret\":false,\"defaultValue\":\"x\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hinted")
                .hasMessageContaining("Z");
    }

    @Test
    void unknownEnvHintPropertyFailsCatalogLoad() {
        assertThatThrownBy(() -> load(withHint("{\"key\":\"A\",\"description\":\"d\",\"secret\":false,\"colour\":\"x\"}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyBundledEnvHintHasADefaultOrIsGenerated() {
        for (AppTemplate t : bundled().all()) {
            for (AppTemplate.EnvHint h : t.env()) {
                if (h.secret()) {
                    assertThat(h.generate()).as("%s/%s secret must be generated", t.id(), h.key()).isTrue();
                    assertThat(h.defaultValue()).as("%s/%s", t.id(), h.key()).isNull();
                } else {
                    assertThat(h.defaultValue()).as("%s/%s needs a default", t.id(), h.key()).isNotBlank();
                }
            }
        }
    }
}

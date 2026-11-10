package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.testcontainers.DockerClientFactory;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Create endpoints against the real local daemon. */
class HostCreateResourcesRealDockerTest extends HostApiTestBase {

    private final DockerClient docker = DockerClientFactory.lazyClient();

    private String localHostId() {
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private String create(Login login, String host, String collection, String body) throws Exception {
        return mvc.perform(login.write(post("/api/hosts/{h}/{c}", host, collection))
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"')))
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * A random /24 in 10.0.0.0/8 (outside Docker's default pools) that no existing network overlaps. A fixed subnet
     * collides with other runs sharing the daemon ("Pool overlaps", surfaced as 502).
     */
    private String freeSubnet() {
        var used = docker.listNetworksCmd().exec().stream()
                .flatMap(n -> n.getIpam() == null || n.getIpam().getConfig() == null
                        ? Stream.<String>empty()
                        : n.getIpam().getConfig().stream().map(c -> c.getSubnet()))
                .filter(Objects::nonNull).toList();
        return pickFreeSubnet(used, ThreadLocalRandom.current());
    }

    static String pickFreeSubnet(List<String> used, Random rnd) {
        // IPv6 pools (if the daemon reports any) cannot overlap an IPv4 candidate, so they are skipped
        List<String> ipv4Used = used.stream().filter(u -> IPV4_CIDR.matcher(u).matches()).toList();
        for (int i = 0; i < 200; i++) {
            String candidate = "10." + rnd.nextInt(100, 250) + "." + rnd.nextInt(0, 256) + ".0/24";
            if (ipv4Used.stream().noneMatch(u -> overlaps(u, candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free subnet found");
    }

    /** True when two IPv4 CIDRs share at least one address. A wider mask covers every subnet inside it. */
    static boolean overlaps(String a, String b) {
        Cidr ca = Cidr.parse(a);
        Cidr cb = Cidr.parse(b);
        int prefix = Math.min(ca.prefix(), cb.prefix());
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ca.address() & mask) == (cb.address() & mask);
    }

    private static final Pattern IPV4_CIDR =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})");

    private record Cidr(long address, int prefix) {
        static Cidr parse(String cidr) {
            Matcher m = IPV4_CIDR.matcher(cidr);
            if (!m.matches()) {
                throw new IllegalArgumentException("not an IPv4 CIDR: " + cidr);
            }
            long address = 0;
            for (int g = 1; g <= 4; g++) {
                address = (address << 8) | Integer.parseInt(m.group(g));
            }
            return new Cidr(address, Integer.parseInt(m.group(5)));
        }
    }

    @Test
    void overlapsIsSymmetricForNestedSubnets() {
        assertThat(overlaps("10.150.7.0/24", "10.150.0.0/16")).isTrue();
    }

    @Test
    void pickFreeSubnetIgnoresIpv6Subnets() {
        assertThat(pickFreeSubnet(List.of("fd00:1::/64"), new Random(7))).startsWith("10.");
    }

    @Test
    void overlapsDetectsSubnetInsideWiderNetwork() {
        assertThat(overlaps("10.150.0.0/16", "10.150.7.0/24")).isTrue();
    }

    @Test
    void overlapsIgnoresDisjointSubnets() {
        assertThat(overlaps("10.150.0.0/16", "10.151.0.0/24")).isFalse();
    }

    @Test
    void pickFreeSubnetAvoidsWiderNetwork() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String subnet = pickFreeSubnet(List.of("10.150.0.0/16"), rnd);
            assertThat(subnet).doesNotStartWith("10.150.");
        }
    }

    @Test
    void pickFreeSubnetFailsWhenEveryCandidateIsCovered() {
        assertThatThrownBy(() -> pickFreeSubnet(List.of("10.0.0.0/8"), new Random(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void networkAndVolumeCreateThenNameTaken() throws Exception {
        String host = localHostId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String net = "shipir-net-" + suffix;
        String vol = "shipir-vol-" + suffix;
        String subnet = freeSubnet();
        Login op = login("operator");
        String netId = null;
        try {
            mvc.perform(op.write(post("/api/hosts/{h}/networks", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + net + "\",\"internal\":true,\"subnet\":\"" + subnet + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value(net));
            var inspected = docker.listNetworksCmd().withNameFilter(net).exec().get(0);
            netId = inspected.getId();
            assertThat(inspected.getInternal()).isTrue();
            mvc.perform(op.write(post("/api/hosts/{h}/networks", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + net + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));

            mvc.perform(op.write(post("/api/hosts/{h}/volumes", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + vol + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(vol));
            mvc.perform(op.write(post("/api/hosts/{h}/volumes", host)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + vol + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        } finally {
            if (netId != null) docker.removeNetworkCmd(netId).exec();
            try {
                docker.removeVolumeCmd(vol).exec();
            } catch (RuntimeException ignored) {
                // never created
            }
        }
    }

    @Test
    void containerCreatedStartedAndDuplicateNameRefused() throws Exception {
        String host = localHostId();
        try {
            docker.inspectImageCmd("busybox:1.36").exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd("busybox:1.36").start().awaitCompletion();
        }
        String name = "shipir-create-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{'name':'" + name + "','image':'busybox:1.36','command':['sleep','300'],"
                + "'env':{'FOO':'bar'},'restartPolicy':'no','start':true}";
        Login op = login("operator");
        String id = null;
        try {
            String response = create(op, host, "containers", body);
            id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("id").asText();
            var inspect = docker.inspectContainerCmd(id).exec();
            assertThat(inspect.getState().getRunning()).isTrue();
            assertThat(inspect.getConfig().getEnv()).contains("FOO=bar");
            assertThat(inspect.getHostConfig().getPrivileged()).isNotEqualTo(true);

            mvc.perform(op.write(post("/api/hosts/{h}/containers", host)).contentType(MediaType.APPLICATION_JSON)
                            .content(body.replace('\'', '"')))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_TAKEN"));
        } finally {
            if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        }
    }

    @Test
    void unknownImageIs404() throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers", host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"shipir-no-such-image-xyz:1\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void startFailureRemovesTheContainer() throws Exception {
        String host = localHostId();
        try {
            docker.inspectImageCmd("busybox:1.36").exec();
        } catch (RuntimeException notPulled) {
            docker.pullImageCmd("busybox:1.36").start().awaitCompletion();
        }
        String name = "shipir-fail-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(login("operator").write(post("/api/hosts/{h}/containers", host))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"image\":\"busybox:1.36\","
                                + "\"command\":[\"/does-not-exist\"],\"start\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTAINER_START_FAILED"));
        assertThat(docker.listContainersCmd().withShowAll(true).withNameFilter(java.util.List.of(name)).exec()).isEmpty();
    }
}

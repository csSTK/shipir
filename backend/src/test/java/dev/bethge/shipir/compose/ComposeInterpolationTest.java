package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeInterpolationTest {

    private static final Map<String, String> ENV = Map.of("IMG", "traefik:3", "EMPTY", "", "TAG", "v1");

    @Test
    void substitutesBracedAndBareVariables() {
        assertThat(ComposeInterpolation.apply("image: ${IMG}", ENV)).isEqualTo("image: traefik:3");
        assertThat(ComposeInterpolation.apply("image: $IMG", ENV)).isEqualTo("image: traefik:3");
        assertThat(ComposeInterpolation.apply("image: repo/x:${TAG}-$TAG", ENV)).isEqualTo("image: repo/x:v1-v1");
    }

    @Test
    void defaultsApplyLikeCompose() {
        assertThat(ComposeInterpolation.apply("${NOPE:-d}", ENV)).isEqualTo("d");
        assertThat(ComposeInterpolation.apply("${EMPTY:-d}", ENV)).isEqualTo("d");
        assertThat(ComposeInterpolation.apply("${EMPTY-d}", ENV)).isEmpty();
        assertThat(ComposeInterpolation.apply("${NOPE-d}", ENV)).isEqualTo("d");
        assertThat(ComposeInterpolation.apply("${IMG:-d}", ENV)).isEqualTo("traefik:3");
        assertThat(ComposeInterpolation.apply("${IMG:+alt}", ENV)).isEqualTo("alt");
        assertThat(ComposeInterpolation.apply("${NOPE:+alt}", ENV)).isEmpty();
    }

    @Test
    void escapedDollarIsLiteralAndNotSubstituted() {
        assertThat(ComposeInterpolation.apply("$${IMG}", ENV)).isEqualTo("${IMG}");
    }

    @Test
    void unresolvableReferencesStayAsTheyAre() {
        assertThat(ComposeInterpolation.apply("image: ${NOPE}", ENV)).isEqualTo("image: ${NOPE}");
        assertThat(ComposeInterpolation.apply("image: ${NOPE:?must be set}", ENV)).isEqualTo("image: ${NOPE:?must be set}");
    }

    @Test
    void substitutedValuesAreNotExpandedAgain() {
        // a value that itself looks like a reference must not recurse (no loops, no reading of other variables)
        Map<String, String> env = Map.of("A", "${B}", "B", "secret");
        assertThat(ComposeInterpolation.apply("${A}", env)).isEqualTo("${B}");
    }

    @Test
    void nestedDefaultsAreResolved() {
        Map<String, String> aSet = Map.of("A", "a-img", "B", "b-img");
        assertThat(ComposeInterpolation.apply("${A:-${B}}", aSet)).isEqualTo("a-img");
        assertThat(ComposeInterpolation.apply("${A:-${B}}", Map.of("B", "b-img"))).isEqualTo("b-img");
        assertThat(ComposeInterpolation.apply("${A:-${B}}", Map.of())).isEqualTo("${B}");
        assertThat(ComposeInterpolation.apply("x/${A:-${B:-deep}}:1", Map.of())).isEqualTo("x/deep:1");
    }

    @Test
    void unbalancedBracesDoNotHangOrThrow() {
        assertThat(ComposeInterpolation.apply("${A:-${B}", Map.of("B", "b"))).isEqualTo("${A:-b");
        assertThat(ComposeInterpolation.apply("${", Map.of())).isEqualTo("${");
        assertThat(ComposeInterpolation.apply("$", Map.of())).isEqualTo("$");
    }

    @Test
    void nullTextAndEnvAreTolerated() {
        assertThat(ComposeInterpolation.apply("x: 1", null)).isEqualTo("x: 1");
        assertThat(ComposeInterpolation.apply(null, ENV)).isEmpty();
    }
}

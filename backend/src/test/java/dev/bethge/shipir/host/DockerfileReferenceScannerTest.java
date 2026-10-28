package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Registries named in a Dockerfile are connection targets of the daemon and go through the egress policy. */
class DockerfileReferenceScannerTest {

    private static final EgressPolicy POLICY = new EgressPolicy(new EgressProperties(false), host -> switch (host) {
        case "registry.example.com", "ghcr.io" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
        case "internal.corp" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 5})};
        case "10.0.0.5" -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 5})};
        case "LOCALHOST", "localhost" -> new InetAddress[]{InetAddress.getLoopbackAddress()};
        default -> throw new UnknownHostException(host);
    });

    private static void scan(String dockerfile) {
        DockerfileReferenceScanner.requireAllowedReferences(POLICY, dockerfile, Map.of());
    }

    private static void scan(String dockerfile, Map<String, String> buildArgs) {
        DockerfileReferenceScanner.requireAllowedReferences(POLICY, dockerfile, buildArgs);
    }

    private static void assertRefused(String dockerfile) {
        assertThatThrownBy(() -> scan(dockerfile)).isInstanceOfSatisfying(HostException.class, e -> {
            assertThat(e.code()).isEqualTo("REGISTRY_NOT_ALLOWED");
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.getMessage()).doesNotContain("10.0.0.5").doesNotContain("internal.corp");
        });
    }

    // ---- allowed ----

    @Test
    void publicAndDockerHubAndScratchAreAllowed() {
        assertThatCode(() -> scan("FROM nginx:1.27\nFROM library/busybox AS b\nFROM registry.example.com:5443/t/app@sha256:"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\nFROM scratch\n")).doesNotThrowAnyException();
    }

    @Test
    void multiStageWithStageNamesIsAllowed() {
        assertThatCode(() -> scan("""
                FROM golang:1.22 AS build
                RUN go build
                FROM build AS test
                FROM BUILD AS again
                FROM scratch
                COPY --from=build /app /app
                COPY --from=0 /a /a
                COPY --from=TEST /a /b
                COPY --from=registry.example.com/x/y /a /c
                """)).doesNotThrowAnyException();
    }

    @Test
    void platformFlagAndRunMountAreHandled() {
        assertThatCode(() -> scan("FROM --platform=linux/amd64 alpine:3 AS a\nRUN --mount=type=bind,from=a,target=/x true\n"))
                .doesNotThrowAnyException();
        assertRefused("FROM --platform=$BUILDPLATFORM 10.0.0.5:5000/x AS a\n");
        assertRefused("FROM alpine\nRUN --mount=type=bind,from=10.0.0.5:5000/x,target=/x true\n");
    }

    @Test
    void argDefaultsAreSubstituted() {
        assertThatCode(() -> scan("ARG V=3.19\nARG REG=registry.example.com\nFROM $REG/alpine:${V}\nFROM alpine:${TAGX:-3}\n"))
                .doesNotThrowAnyException();
        assertThatCode(() -> scan("ARG BASE=\"alpine:3\"\nFROM ${BASE}\n")).doesNotThrowAnyException();
    }

    // ---- refused ----

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.5:5000/x", "10.0.0.5/x:1", "internal.corp/x", "localhost:5000/x", "localhost/x",
            "169.254.169.254/x", "metadata.google.internal/x", "unresolvable.example.org/x"})
    void internalRegistryInFromIsRefused(String ref) {
        assertRefused("FROM " + ref + "\n");
        assertRefused("FROM busybox\nFROM " + ref + " AS second\n");
    }

    @Test
    void oneBadStageInMultiStageIsRefused() {
        assertRefused("FROM golang AS build\nFROM alpine AS mid\nFROM 10.0.0.5:5000/base AS final\nCOPY --from=build /a /a\n");
    }

    @Test
    void copyFromImageIsCheckedAndForwardStageNameIsAnImage() {
        assertRefused("FROM alpine\nCOPY --from=internal.corp/x /a /a\n");
        assertRefused("FROM alpine\nCOPY --from=10.0.0.5:5000/x:1 /a /a\n");
        assertRefused("FROM alpine\nADD --from=localhost/x /a /a\n");
        // later stage names are not yet stages: the daemon would pull them as an image
        assertRefused("FROM alpine\nCOPY --from=late.corp/x /a /a\nFROM alpine AS late.corp/x\n");
        // a plain name without a dot is a Docker Hub repository
        assertThatCode(() -> scan("FROM alpine\nCOPY --from=internal/x /a /a\n")).doesNotThrowAnyException();
    }

    @Test
    void argHiddenRegistryIsResolvedAndRefused() {
        assertRefused("ARG R=10.0.0.5:5000\nFROM $R/x\n");
        assertRefused("ARG R=10.0.0.5:5000\nFROM ${R}/x\n");
        assertRefused("FROM ${R:-10.0.0.5:5000}/x\n");
        assertRefused("ARG B=${INNER:-internal.corp}/x\nFROM $B\n");
        assertRefused("FROM alpine\nARG R=internal.corp\nCOPY --from=$R/x /a /a\n");
        assertRefused("FROM alpine\nENV R=internal.corp\nCOPY --from=${R}/x /a /a\n");
    }

    @Test
    void requestBuildArgOverridesTheDefault() {
        assertThatThrownBy(() -> scan("ARG R=registry.example.com\nFROM $R/x\n", Map.of("R", "10.0.0.5:5000")))
                .isInstanceOf(HostException.class);
        assertThatCode(() -> scan("ARG R\nFROM $R/x\n", Map.of("R", "registry.example.com"))).doesNotThrowAnyException();
        // an undeclared build arg is ignored by the daemon and must not change the result
        assertRefused("ARG R=10.0.0.5:5000\nFROM $R/x\n", Map.of("OTHER", "ghcr.io"));
    }

    private static void assertRefused(String dockerfile, Map<String, String> args) {
        assertThatThrownBy(() -> scan(dockerfile, args)).isInstanceOfSatisfying(HostException.class,
                e -> assertThat(e.code()).isEqualTo("REGISTRY_NOT_ALLOWED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FROM $UNDECLARED/x\n",
            "ARG R\nFROM $R/x\n",
            "ARG R=\nFROM $R/x\n",
            "FROM ${R:?missing}/x\n",
            "FROM ${R:+alt}/x\n",
            "FROM $(id)\n",
            "FROM `id`\n",
            "FROM ${R\n",
            "FROM a${A:-${B:-${C:-${D:-${E:-x}}}}}\n",
            "FROM\n",
            "FROM --platform=linux/amd64\n",
            "FROM -x\n",
            "FROM Alpine\n",
            "FROM alpine\nCOPY --from=$UNDECLARED /a /a\n",
            "FROM alpine\nCOPY --from= /a /a\n",
            "FROM alpine\nCOPY --from=a\\$b /a /a\n"})
    void unresolvableOrMalformedReferencesAreRefused(String dockerfile) {
        assertRefused(dockerfile);
    }

    @Test
    void selfReferencingArgsDoNotLoopForever() {
        assertRefused("ARG A=$A\nFROM $A\n");
        assertRefused("ARG A=$B\nARG B=$A\nFROM $A\n");
    }

    // ---- syntax tricks ----

    @Test
    void caseWhitespaceAndTabsDoNotHideAReference() {
        assertRefused("from 10.0.0.5:5000/x\n");
        assertRefused("FrOm\t10.0.0.5:5000/x\n");
        assertRefused("   FROM    10.0.0.5:5000/x   AS    a\n");
        assertRefused("﻿FROM 10.0.0.5:5000/x\n");
        assertRefused("FROM 10.0.0.5:5000/x\r\nRUN true\r\n");
        assertRefused("FROM alpine\ncopy --FROM=10.0.0.5:5000/x /a /a\n".replace("--FROM", "--from"));
        assertRefused("FROM LOCALHOST/x\n");
        assertRefused("FROM LocalHost:5000/x\n");
    }

    @Test
    void lineContinuationsDoNotHideAReference() {
        assertRefused("FROM \\\n  10.0.0.5:5000/x\n");
        assertRefused("FROM \\   \n10.0.0.5:5000/x\n");
        assertRefused("FROM --platform=linux/amd64 \\\n# comment in the middle\n\n  10.0.0.5:5000/x \\\n  AS a\n");
        assertRefused("FROM alpine\nCOPY \\\n  --from=10.0.0.5:5000/x \\\n  /a /a\n");
        assertRefused("FROM 10.0.0.5:\\\n5000/x\n");
    }

    @Test
    void backtickEscapeDirectiveIsHonoured() {
        assertRefused("# escape=`\nFROM `\n10.0.0.5:5000/x\n");
        assertRefused("#escape = `\nFROM alpine\nCOPY `\n--from=10.0.0.5:5000/x /a /a\n");
    }

    @Test
    void commentsAreIgnoredButOnlyRealComments() {
        assertThatCode(() -> scan("# FROM 10.0.0.5:5000/x\nFROM alpine\n   # COPY --from=10.0.0.5/x /a /a\nRUN echo hi\n"))
                .doesNotThrowAnyException();
        // a hash after an instruction is not a comment: it is part of the line, the instruction still counts
        assertRefused("FROM 10.0.0.5:5000/x # just a comment\n");
        assertRefused("FROM alpine\nRUN true\nFROM 10.0.0.5:5000/x\n# trailing\n");
    }

    @Test
    void stageNameIsNotMistakenForAnImageReference() {
        assertThatCode(() -> scan("FROM alpine AS Build_1\nFROM build_1\n")).doesNotThrowAnyException();
    }

    // ---- review round 1 ----

    @ParameterizedTest
    @ValueSource(strings = {"\u2003", "\u00A0", "\u0085", "\u2028", "\u3000", "\u000B", "\u001C", "\u2009"})
    void nonAsciiOrControlWhitespaceMakesTheDockerfileUnreadable(String ws) {
        // Docker sees two instructions where a Unicode-aware trim sees one
        assertRefused("FROM alpine\nRUN true \\" + ws + "\nFROM 10.0.0.5:5000/evil\n");
        assertRefused("FROM alpine\n" + ws + "FROM 10.0.0.5:5000/evil\n");
        assertRefused("FROM alpine\n# harmless " + ws + " comment\n");
    }

    @Test
    void asciiSpaceAndTabAfterTheEscapeStillContinue() {
        // one RUN for the daemon as well: the FROM line is its argument
        assertThatCode(() -> scan("FROM alpine\nRUN true \\ \t \nFROM 10.0.0.5:5000/evil\n")).doesNotThrowAnyException();
    }

    @Test
    void unknownDirectiveEndsTheDirectiveBlock() {
        // BuildKit stops at "# foo=bar": the later escape directive is a plain comment, "`" is no continuation
        // and the FROM below is a real instruction
        assertRefused("# foo=bar\n# escape=`\nFROM alpine\nRUN true `\nFROM 10.0.0.5/x\n");
        // with a valid directive block the backtick does continue (the daemon sees one RUN)
        assertThatCode(() -> scan("# escape=`\nFROM alpine\nRUN true `\nFROM 10.0.0.5/x\n")).doesNotThrowAnyException();
    }

    @Test
    void checkDirectiveDoesNotHideALaterSyntaxOrEscapeDirective() {
        assertRefused("# check=skip=all\n# syntax=10.0.0.5:5000/evil\nFROM alpine\n");
        assertRefused("# check=skip=all\n# escape=`\nFROM alpine\n");
        assertRefused("# foo=bar\n# plain comment\n# syntax=docker/dockerfile:1\nFROM alpine\n");
        assertThatCode(() -> scan("# check=skip=all\nFROM alpine\n# syntax=10.0.0.5:5000/evil\n")).doesNotThrowAnyException();
    }

    @Test
    void duplicateDirectivesAreRefused() {
        assertRefused("# escape=`\n# escape=`\nFROM alpine\n");
        assertRefused("# syntax=docker/dockerfile:1\n# syntax=docker/dockerfile:1\nFROM alpine\n");
        assertRefused("# escape=x\nFROM alpine\n");
    }

    @Test
    void syntaxFrontendImageGoesThroughTheRegistryRule() {
        assertRefused("# syntax=10.0.0.5:5000/evil\nFROM alpine\n");
        assertRefused("#syntax = internal.corp/frontend:1\nFROM alpine\n");
        assertRefused("# syntax=Bad Ref\nFROM alpine\n");
        assertRefused("# escape=`\n# syntax=localhost:5000/f@sha256:" + "a".repeat(64) + "\nFROM alpine\n");
        assertThatCode(() -> scan("# syntax=docker/dockerfile:1\nFROM alpine\n")).doesNotThrowAnyException();
        assertThatCode(() -> scan("# syntax=docker/dockerfile:1.7@sha256:" + "a".repeat(64) + "\n# escape=`\nFROM alpine\n"))
                .doesNotThrowAnyException();
        // not in the directive block any more: only a comment
        assertThatCode(() -> scan("FROM alpine\n# syntax=10.0.0.5:5000/evil\n")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUILDPLATFORM", "BUILDOS", "BUILDARCH", "BUILDVARIANT", "TARGETPLATFORM", "TARGETOS",
            "TARGETARCH", "TARGETVARIANT"})
    void predefinedPlatformArgsCannotBeUsedUndeclared(String name) {
        assertRefused("FROM ${" + name + ":-alpine}\n");
        assertRefused("FROM alpine\nCOPY --from=${" + name + ":-alpine} /a /a\n");
        // the request can set them without any ARG, so the fallback must not be trusted
        assertThatThrownBy(() -> scan("FROM ${" + name + ":-alpine}\n", Map.of(name, "10.0.0.5:5000/evil")))
                .isInstanceOf(HostException.class);
        // declared: the request value is resolved and checked like any other
        assertThatThrownBy(() -> scan("ARG " + name + "\nFROM $" + name + "\n", Map.of(name, "10.0.0.5:5000/evil")))
                .isInstanceOf(HostException.class);
        assertThatCode(() -> scan("ARG " + name + "=alpine\nFROM $" + name + "\n")).doesNotThrowAnyException();
    }

    @Test
    void requestOverrideOfTargetArchWinsOverTheDeclaredDefault() {
        String dockerfile = "ARG TARGETARCH=alpine\nFROM $TARGETARCH\n";
        assertThatCode(() -> scan(dockerfile)).doesNotThrowAnyException();
        assertRefused(dockerfile, Map.of("TARGETARCH", "10.0.0.5:5000/evil"));
        assertThatCode(() -> scan(dockerfile, Map.of("TARGETARCH", "busybox"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUN --mount=type=bind,\"from=10.0.0.5:5000/x\",target=/a true",
            "RUN --mount=type=bind,'from=10.0.0.5:5000/x',target=/a true",
            "RUN --mount=type=bind,from=\"10.0.0.5:5000/x\",target=/a true"})
    void quotedMountFieldsAreRefused(String run) {
        assertRefused("FROM alpine\n" + run + "\n");
    }

    @Test
    void longWhitespaceRunsInDirectiveLinesAreLinear() {
        String spaces = " ".repeat(200_000);
        long start = System.nanoTime();
        scan("# foo" + spaces + "=" + spaces + "x" + spaces + "\nFROM alpine\n");
        scan("#" + spaces + "a" + spaces + "\nFROM alpine\n");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);
    }

    // ---- review round 2 ----

    @Test
    void stageScopeReferencesNeverExpandVariables() {
        // ENV beats ARG in Docker, so the ARG value must not be trusted
        assertRefused("FROM alpine\nENV X=10.0.0.5:5000/evil\nARG X=ghcr.io/ok\nCOPY --from=$X /a /a\n");
        assertRefused("FROM alpine\nARG X=ghcr.io/ok\nENV X=10.0.0.5:5000/evil\nCOPY --from=$X /a /a\n");
        // inherited from the parent stage or the base image: unknowable here
        assertRefused("FROM alpine AS a\nENV X=10.0.0.5:5000/evil\nFROM a AS b\nCOPY --from=$X /a /a\n");
        assertRefused("FROM alpine\nARG X=ghcr.io/ok\nCOPY --from=$X /a /a\n");
        assertRefused("FROM alpine\nARG X=ghcr.io/ok\nCOPY --from=${X} /a /a\nCOPY --from=${X:-ghcr.io/ok} /a /a\n");
        assertRefused("FROM alpine\nARG X=ghcr.io/ok\nRUN --mount=type=bind,from=$X,target=/a true\n");
        // literal references are still scanned, global ARGs still work for FROM
        assertThatCode(() -> scan("ARG REG=docker.io\nFROM $REG/library/alpine\nCOPY --from=ghcr.io/o/x /a /a\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void multiPairDeclarationsAreEvaluatedAgainstTheStateBeforeTheInstruction() {
        assertRefused("ENV abc=10.0.0.5:5000/evil\nENV abc=alpine def=$abc\nFROM $def\n");
        assertRefused("ARG abc=10.0.0.5:5000/evil\nARG abc=alpine def=$abc\nFROM $def\n");
        assertRefused("ARG abc=alpine def=$abc\nFROM $def\n");
        // redeclaring a name with another value is not trusted either
        assertRefused("ARG abc=10.0.0.5:5000/evil\nARG abc=alpine\nFROM $abc\n");
        assertThatCode(() -> scan("ARG a=docker.io\nFROM $a/library/alpine\n")).doesNotThrowAnyException();
    }

    @Test
    void loneCarriageReturnIsRefused() {
        assertRefused("FROM alpine\rFROM 10.0.0.5:5000/evil\n");
        assertRefused("FROM alpine\r");
        assertThatCode(() -> scan("FROM alpine\r\nRUN true\r\n")).doesNotThrowAnyException();
    }

    // ---- reading the Dockerfile out of an upload ----

    private static byte[] tar(boolean gzip, Object... nameContentPairs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip ? new GZIPOutputStream(out) : out, 512)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                String name = (String) nameContentPairs[i];
                Object content = nameContentPairs[i + 1];
                if (content instanceof String link && link.startsWith("->")) {
                    TarArchiveEntry e = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
                    e.setLinkName(link.substring(2));
                    tar.putArchiveEntry(e);
                    tar.closeArchiveEntry();
                    continue;
                }
                byte[] bytes = ((String) content).getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry e = new TarArchiveEntry(name);
                e.setSize(bytes.length);
                tar.putArchiveEntry(e);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void dockerfileIsReadFromPlainAndGzippedArchives(@TempDir Path dir) throws Exception {
        Path plain = Files.write(dir.resolve("a.tar"), tar(false, "Dockerfile", "FROM 10.0.0.5:5000/x\n"));
        Path gz = Files.write(dir.resolve("b.tgz"), tar(true, "./docker//App.Dockerfile", "FROM 10.0.0.5:5000/y\n"));
        assertThat(DockerfileReferenceScanner.readFromArchive(plain, "Dockerfile")).contains("10.0.0.5:5000/x");
        assertThat(DockerfileReferenceScanner.readFromArchive(gz, "docker/App.Dockerfile")).contains("10.0.0.5:5000/y");
    }

    @Test
    void lastEntryWinsLikeTheDaemonExtraction(@TempDir Path dir) throws Exception {
        Path file = Files.write(dir.resolve("a.tar"), tar(false, "Dockerfile", "FROM alpine\n", "./Dockerfile", "FROM 10.0.0.5/x\n"));
        assertThat(DockerfileReferenceScanner.readFromArchive(file, "Dockerfile")).contains("10.0.0.5/x");
    }

    @Test
    void missingSymlinkedOrOversizeDockerfileIsRefused(@TempDir Path dir) throws Exception {
        Path none = Files.write(dir.resolve("none.tar"), tar(false, "other", "x"));
        Path link = Files.write(dir.resolve("link.tar"), tar(false, "real", "FROM alpine\n", "Dockerfile", "->real"));
        Path big = Files.write(dir.resolve("big.tar"), tar(false, "Dockerfile", "#".repeat(2_000_000)));
        for (Path p : new Path[]{none, link, big}) {
            assertThatThrownBy(() -> DockerfileReferenceScanner.readFromArchive(p, "Dockerfile"))
                    .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.status().value()).isEqualTo(400));
        }
    }
}

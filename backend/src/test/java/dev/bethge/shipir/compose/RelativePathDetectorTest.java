package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RelativePathDetectorTest {

    @Test
    void selfContainedFileHasNoFindings() {
        String yaml = """
                services:
                  web:
                    image: nginx
                    volumes:
                      - data:/var/lib/data
                      - /srv/site:/usr/share/nginx/html:ro
                      - ${DATA_DIR}/x:/x
                      - /var/run/docker.sock:/var/run/docker.sock
                      - type: bind
                        source: /srv/abs
                        target: /abs
                      - type: volume
                        source: data
                        target: /v
                    env_file: /etc/app.env
                    build: https://github.com/acme/app.git
                volumes:
                  data: {}
                secrets:
                  tok:
                    file: /run/secrets/tok
                """;
        assertThat(RelativePathDetector.find(yaml)).isEmpty();
    }

    @Test
    void flagsRelativeBindMountsInShortAndLongForm() {
        String yaml = """
                services:
                  web:
                    image: nginx
                    volumes:
                      - ./html:/usr/share/nginx/html
                      - ../shared:/shared:ro
                      - .:/app
                      - conf/app.conf:/etc/app.conf
                      - type: bind
                        source: ./long
                        target: /long
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly(
                "web bind mount: ./html", "web bind mount: ../shared", "web bind mount: .",
                "web bind mount: conf/app.conf", "web bind mount: ./long");
    }

    @Test
    void flagsEnvFileBuildContextAndFileSecrets() {
        String yaml = """
                services:
                  a:
                    image: x
                    env_file: .env
                  b:
                    image: x
                    env_file:
                      - ./one.env
                      - path: ./two.env
                        required: false
                  c:
                    build: ./app
                  d:
                    build:
                      context: ../d
                configs:
                  cfg:
                    file: ./cfg.txt
                secrets:
                  tok:
                    file: tok.txt
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly(
                "a env_file: .env", "b env_file: ./one.env", "b env_file: ./two.env", "c build context: ./app",
                "d build context: ../d", "secrets.tok file: tok.txt", "configs.cfg file: ./cfg.txt");
    }

    @Test
    void unparsableYamlYieldsNothing() {
        assertThat(RelativePathDetector.find("a: [")).isEmpty();
        assertThat(RelativePathDetector.find("INVALID")).isEmpty();
    }

    @Test
    void findsRelativePathsInAnchoredExtensionBlocksUsedViaMergeKeys() {
        String yaml = """
                x-common: &c
                  volumes:
                    - ./a:/a
                  env_file: .env
                services:
                  web:
                    <<: *c
                    image: nginx
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly(
                "x-common bind mount: ./a", "x-common env_file: .env");
    }

    @Test
    void refusesAliasesUsedAsValuesOfPathKeys() {
        String yaml = """
                x-vols: &vols
                  - ./a:/a
                x-env: &env .env
                x-build: &b ./app
                services:
                  web:
                    image: nginx
                    volumes: *vols
                    env_file: *env
                  api:
                    build: *b
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactlyInAnyOrder(
                "web uses a YAML alias for volumes; expand it first",
                "web uses a YAML alias for env_file; expand it first",
                "api uses a YAML alias for build; expand it first");
    }

    @Test
    void longFormBindWithSlashlessRelativeSourceIsFlagged() {
        String yaml = """
                services:
                  web:
                    image: nginx
                    volumes:
                      - type: bind
                        source: data
                        target: /d
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly("web bind mount: data");
    }

    @Test
    void buildMapWithoutContextDefaultsToCurrentDirAndAdditionalContextsAreScanned() {
        String yaml = """
                services:
                  a:
                    build:
                      dockerfile: Dockerfile.prod
                  b:
                    build:
                      context: /abs
                      additional_contexts:
                        lib: ../lib
                        img: docker-image://alpine
                        other: service:a
                        remote: https://github.com/acme/x.git
                  c:
                    build:
                      context: /abs
                      additional_contexts:
                        - tools=./tools
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly(
                "a build context: .", "b build additional context: ../lib", "c build additional context: ./tools");
    }

    @Test
    void remoteBuildContextsAreNotFlagged() {
        String yaml = """
                services:
                  a:
                    build:
                      context: https://github.com/acme/app.git#main
                  b:
                    build:
                      context: git@github.com:acme/app.git
                  c:
                    build: github.com/acme/app
                """;
        assertThat(RelativePathDetector.find(yaml)).isEmpty();
    }

    @Test
    void refusesAliasesAnywhereInsideAPathSubtree() {
        String yaml = """
                x-v: &v ./a:/a
                x-m: &m {type: bind, source: ./a, target: /a}
                x-e: &e .env
                x-ctx: &ctx ./app
                x-s: &s ./s
                x-b: &b {context: ./app}
                x-def: &def {file: ./tok}
                services:
                  a:
                    volumes: [*v]
                  b:
                    volumes: [*m]
                  c:
                    env_file: [*e]
                  d:
                    build: {context: *ctx}
                  e:
                    volumes:
                      - {type: bind, source: *s, target: /x}
                  f:
                    build: {<<: *b}
                secrets:
                  tok: *def
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactlyInAnyOrder(
                "a uses a YAML alias for volumes; expand it first",
                "b uses a YAML alias for volumes; expand it first",
                "c uses a YAML alias for env_file; expand it first",
                "d uses a YAML alias for build; expand it first",
                "e uses a YAML alias for volumes; expand it first",
                "f uses a YAML alias for build; expand it first",
                "document uses a YAML alias for secrets; expand it first");
    }

    @Test
    void aliasesOutsidePathSubtreesAreIgnored() {
        String yaml = """
                x-env: &env {A: "1"}
                services:
                  web:
                    image: nginx
                    environment: *env
                    labels: {a: *env}
                """;
        assertThat(RelativePathDetector.find(yaml)).isEmpty();
    }

    @Test
    void aRealFindingIsKeptEvenWhenAnAliasHasTheSameName() {
        String yaml = """
                x-d: &data {a: b}
                services:
                  web:
                    volumes:
                      - {type: bind, source: data, target: /d}
                    environment: *data
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly("web bind mount: data");
    }

    @Test
    void keysNamedBuildVolumesOrEnvFileInsideEnvironmentOrLabelsAreNotPaths() {
        String yaml = """
                services:
                  web:
                    image: nginx
                    environment:
                      build: production
                      volumes: x
                      env_file: y
                    labels:
                      build: "1.2"
                    deploy:
                      labels:
                        build: "2"
                    annotations:
                      build: "3"
                """;
        assertThat(RelativePathDetector.find(yaml)).isEmpty();
    }

    @Test
    void aliasesInsideSequencesOutsidePathKeysDoNotHideLaterAliasFindings() {
        String yaml = """
                x-ports: &p "80:80"
                x-vol: &v ./a:/a
                x-cmd: &x b
                services:
                  web:
                    image: nginx
                    ports: [*p]
                    command: [a, *x]
                    depends_on: [*p]
                    volumes: *v
                """;
        assertThat(RelativePathDetector.find(yaml)).containsExactly("web uses a YAML alias for volumes; expand it first");
    }

    @Test
    void namesThatLookLikeComposeKeysAreNotPathKeys() {
        String yaml = """
                x-logging: &l {driver: json-file}
                services:
                  build:
                    image: node
                    <<: *l
                  volumes:
                    image: node
                    logging: *l
                  env_file:
                    image: node
                networks:
                  build:
                    <<: *l
                volumes:
                  env_file:
                    <<: *l
                  volumes:
                    <<: *l
                """;
        assertThat(RelativePathDetector.find(yaml)).isEmpty();
    }
}

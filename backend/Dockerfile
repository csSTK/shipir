# syntax=docker/dockerfile:1

# Build stage: JDK + Maven wrapper. Tests are skipped here because they need
# Docker/Testcontainers and run in `./mvnw verify` outside the image build.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Dependencies first, so this layer stays cached until pom.xml or the wrapper changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

# Runtime stage: JRE plus the Docker and Compose CLIs, non-root user, no build tools or sources.
FROM eclipse-temurin:25-jre-alpine AS runtime

# docker-cli talks to the mounted socket; docker-cli-compose provides `docker compose`, which the
# app runs as a subprocess for Compose stacks. Installed as root, before the USER switch.
RUN apk add --no-cache docker-cli docker-cli-compose

RUN addgroup -S -g 10001 shipir \
 && adduser -S -u 10001 -G shipir -h /app shipir

# Stack directory. Owned by the app user so a fresh named volume mounted here (docker-compose.yml)
# inherits that ownership on first use.
RUN mkdir -p /data/stacks && chown shipir:shipir /data/stacks
ENV SHIPIR_STACKS_BASE_DIR=/data/stacks

WORKDIR /app
COPY --from=build --chown=shipir:shipir /workspace/target/*.jar app.jar

USER shipir:shipir
EXPOSE 8080

# Configuration comes from the environment only (DB_HOST, DB_PORT, DB_NAME,
# DB_USER, DB_PASSWORD); see application.yml. No secrets are set in the image.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

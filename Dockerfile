# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: compile fat JAR using the project's Gradle wrapper
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy build descriptor files first so Docker can cache the dependency-download
# layer independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ ./gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

# Copy source and produce the fat JAR
COPY src/ ./src/
RUN ./gradlew --no-daemon shadowJar

# ---------------------------------------------------------------------------
# Runtime stage: minimal JRE image
# Multi-arch eclipse-temurin images run natively on Raspberry Pi 4/5 (arm64).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is required for the docker-compose healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/build/libs/photovault-server.jar ./photovault-server.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/photovault-server.jar"]

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "dev.jskrzypczak.photovault"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

application {
    // EngineMain is a real class from ktor-server-netty, so the build is green even before
    // we write our own module. It reads application.yaml at runtime (added in the next step).
    mainClass.set("io.ktor.server.netty.EngineMain")
}

ktor {
    fatJar {
        archiveFileName.set("photovault-server.jar")
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.bcrypt)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    // docker-java (shaded in Testcontainers) defaults to API 1.32;
    // Docker 29+ requires minimum 1.40 — override via system property and env.
    systemProperty("api.version", "1.43")
    environment("DOCKER_API_VERSION", "1.43")
}

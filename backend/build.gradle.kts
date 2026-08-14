plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "co.ara"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Override Spring Boot 3.4.1's managed Testcontainers version (1.20.4). That version's
// docker-java client fails to negotiate with newer Docker Engine/Desktop releases
// (observed: "client version 1.32 is too old, minimum supported API version is 1.40"
// against Docker Desktop server 29.6.2 / API 1.55), breaking container startup for every
// test that extends PostgresTestBase. 1.21.4 fixes the negotiation. Do not revert to the
// Boot-managed default without confirming your Docker Engine's API version is compatible.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> { useJUnitPlatform() }

/**
 * The OpenAPI document is produced by OpenApiDocumentTest, which writes
 * build/openapi.json during `./gradlew test`. That keeps the contract a real build
 * artifact -- regenerable from a checkout, diffable in CI -- rather than something
 * only obtainable by starting the application and running curl by hand.
 */
val openApiDocument = layout.buildDirectory.file("openapi.json")

/**
 * The output is declared on `test`, which is what actually writes it, and NOT on
 * openApiSpec, which only checks for it.
 *
 * Declaring it on openApiSpec is what made the task fail every run: Gradle's stale
 * output cleanup deletes a file that is registered as one task's output but was
 * produced by another, and it does so immediately before the owning task executes
 * -- so the file the test had just written was removed moments before the task
 * looked for it. Registering it here also makes `test` re-run when the document is
 * missing, which is the behaviour `npm run generate:api` wants.
 */
tasks.named<Test>("test") {
    outputs.file(openApiDocument)
}

tasks.register("openApiSpec") {
    description = "Produces build/openapi.json for frontend type generation"
    group = "documentation"
    dependsOn("test")
    inputs.file(openApiDocument)
    // Never up-to-date: this task's only job is to assert the file exists and say
    // where it is, and skipping that would report success without checking.
    outputs.upToDateWhen { false }
    doLast {
        val file = openApiDocument.get().asFile
        if (!file.exists()) {
            throw GradleException("openapi.json was not produced; did OpenApiDocumentTest run?")
        }
        // Concatenated, not interpolated: the previous ${'$'}{...} escaped the dollar
        // and printed the expression literally, which nobody noticed because the
        // task never got this far.
        println("OpenAPI document written to " + file.absolutePath)
    }
}

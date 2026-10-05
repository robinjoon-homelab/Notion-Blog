import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "xyz.robinjoon"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

ktlint {
    version.set("1.8.0")
    ignoreFailures.set(false)
    outputToConsole.set(true)
}

// Keep ktlint on the Kotlin compiler version bundled with its rule engine.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            requested.version?.let { requestedVersion ->
                useVersion(requestedVersion)
                because("ktlint must use the Kotlin compiler version it was built with")
            }
        }
    }
}

val exposedVersion = "1.4.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter:$exposedVersion") {
        exclude(group = "org.jetbrains.exposed", module = "exposed-dao")
    }
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    runtimeOnly("org.webjars.npm:katex:0.17.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework:spring-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<KtLintCheckTask>().configureEach {
    mustRunAfter(tasks.named("ktlintFormat"))
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(tasks.named("ktlintFormat"), tasks.named("ktlintCheck"))
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("ktlintFormat"), tasks.named("ktlintCheck"))
    useJUnitPlatform()
}

package xyz.robinjoon.notionblog.architecture

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TargetArchitectureBoundaryRulesTest {
    private val checks = TargetArchitectureChecks(ROOT)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `domain imports retain purity in either package layout`(featureFirst: Boolean) {
        val domain = packageName("domain", "post", featureFirst)
        val pure = source(domain, "import java.net.URI\nimport $ROOT.shared.domain.Identity")
        val spring = source(domain, "import org.springframework.stereotype.Component")

        assertThat(checks.domainImports(listOf(pure))).isEmpty()
        assertThat(checks.domainImports(listOf(spring))).singleElement().asString().contains("Component")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `domain and application cannot reach outer implementations in either layout`(featureFirst: Boolean) {
        val domain = packageName("domain", "post", featureFirst)
        val service = packageName("application.service", "queries", featureFirst)
        val adapter = packageName("adapter.outbound", "notion.client", featureFirst)
        val badDomain = source(domain, "import $service.PostReader")
        val badService = source(service, "import $adapter.NotionClient")
        val goodService = source(service, "import $domain.Post")

        assertThat(checks.dependencies(listOf(badDomain, badService))).hasSize(2)
        assertThat(checks.dependencies(listOf(goodService))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Exposed belongs only to the persistence exposed subtree`(featureFirst: Boolean) {
        val body = "import org.jetbrains.exposed.v1.core.Table"
        val allowed = source(packageName("adapter.outbound", "persistence.exposed.table", featureFirst), body)
        val forbidden =
            listOf(
                source(packageName("adapter.outbound", "persistence.snapshot", featureFirst), body),
                source(packageName("adapter.inbound", "web", featureFirst), body),
                source(packageName("config", "database", featureFirst), body),
                source(packageName("adapter.outbound", "persistence.other.exposed", featureFirst), body),
                source(packageName("adapter.outbound", "persistence.exposedness", featureFirst), body),
                source(packageName("adapter.outbound", "other.persistence.exposed", featureFirst), body),
            )

        assertThat(checks.exposedTypes(listOf(allowed))).isEmpty()
        forbidden.forEach { candidate ->
            assertThat(checks.exposedTypes(listOf(candidate))).isNotEmpty()
        }
    }

    @Test
    fun `qualified Exposed references cannot bypass the import check`() {
        val candidate = source("$ROOT.blog.application.model", "val row: org.jetbrains.exposed.v1.core.ResultRow")

        assertThat(checks.exposedTypes(listOf(candidate))).isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `JSON belongs to Notion or persistence while HTTP and Notion DTOs remain in Notion`(featureFirst: Boolean) {
        val notion = packageName("adapter.outbound", "notion.client.blocks", featureFirst)
        val persistence = packageName("adapter.outbound", "persistence.snapshot.encoding", featureFirst)
        val web = packageName("adapter.inbound", "web.view", featureFirst)
        val json = "import tools.jackson.databind.JsonNode"
        val http = "import org.springframework.web.client.RestClient"
        val dto = "import ${packageName("adapter.outbound", "notion.dto.blocks", !featureFirst)}.BlockResponse"

        assertThat(checks.adapterTypes(listOf(source(notion, "$json\n$http\n$dto"), source(persistence, json)))).isEmpty()
        assertThat(checks.adapterTypes(listOf(source(web, "$json\n$http\n$dto")))).hasSize(3)
        assertThat(checks.adapterTypes(listOf(source(persistence, "$http\n$dto")))).hasSize(2)
    }

    @Test
    fun `feature and similar package names do not grant Notion privileges`() {
        val body = "import tools.jackson.databind.JsonNode\nimport org.springframework.web.client.RestClient"
        val candidates =
            listOf(
                source("$ROOT.notion.application.model", body),
                source("$ROOT.adapter.outbound.notionary", body),
                source("$ROOT.adapter.outbound.presentation.notion", body),
            )

        candidates.forEach { candidate ->
            assertThat(checks.adapterTypes(listOf(candidate))).hasSize(2)
        }
    }

    @Test
    fun `Notion source implementations remain inside their enclosing adapter boundary`() {
        val body = "import org.springframework.web.client.RestClient\nimport tools.jackson.databind.JsonNode"

        assertThat(checks.adapterTypes(listOf(source("$ROOT.adapter.outbound.notion.source", body)))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unsafe typing direct clocks and repository transaction blocks remain forbidden`(featureFirst: Boolean) {
        val application = packageName("application.service", "queries", featureFirst)
        val persistence = packageName("adapter.outbound", "persistence.exposed", featureFirst)
        val unsafe = source(application, "activateDefaultTyping()\nval key = \"@class\"\nInstant.now()")
        val transaction = source(persistence, "transaction { load() }")
        val clock = source(application, "val now = clock.instant()")

        assertThat(checks.runtimePatterns(listOf(unsafe))).hasSize(3)
        assertThat(checks.runtimePatterns(listOf(transaction))).singleElement().asString().contains("transaction")
        assertThat(checks.runtimePatterns(listOf(clock))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `legacy symbols and source routes cannot hide under a feature package`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web", featureFirst)
        val candidate = source(web, "val old: NotionGateway\nval route = \"/{slug}\"\nval sourceRoute = \"/notion/{pageId}\"")

        assertThat(checks.legacySymbols(listOf(candidate))).hasSize(3)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `web mappings preserve exactly the three public routes across nested packages`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web.controllers.posts", featureFirst)
        val valid = source(web, publicRoutes)
        val additional = source(web, "$publicRoutes\n@GetMapping(\"/private\")")
        val duplicate = source(web, "$publicRoutes\n@GetMapping(\"/\")")

        assertThat(checks.webRoutes(listOf(valid))).isEmpty()
        assertThat(checks.webRoutes(listOf(additional))).isNotEmpty()
        assertThat(checks.webRoutes(listOf(duplicate))).isNotEmpty()
        assertThat(checks.webRoutes(emptyList())).isNotEmpty()
    }

    @Test
    fun `a feature named web cannot substitute for the inbound web layer`() {
        val candidate = source("$ROOT.web.application.service", publicRoutes)

        assertThat(checks.webRoutes(listOf(candidate))).isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `all inbound groups must preserve the same public routes`(featureFirst: Boolean) {
        val api = source(packageName("adapter.inbound", "api.controllers", featureFirst), publicRoutes)
        val extra = source(packageName("adapter.inbound", "another.controllers", featureFirst), "@GetMapping(\"/extra\")")

        assertThat(checks.webRoutes(listOf(api))).isEmpty()
        assertThat(checks.webRoutes(listOf(api, extra))).isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `snapshot metadata checks follow serialization packages without blocking safe diagnostics`(featureFirst: Boolean) {
        val serializer = packageName("adapter.outbound", "persistence.snapshot.encoding", featureFirst)
        val logger = packageName("adapter.outbound", "diagnostics", featureFirst)
        val unsafe = source(serializer, "val name = failure.javaClass.name\nval stored = \"xyz.example.Implementation\"")
        val diagnostic = source(logger, "val name = failure.javaClass.name", "Slf4jSnapshotFailureReporter.kt")
        val similarPackage =
            source(packageName("adapter.outbound", "persistence.snapshotary", featureFirst), "val name = failure.javaClass.name")
        val safe = source(serializer, "val kind = \"paragraph\"\nval schemaVersion = 1")

        assertThat(checks.snapshotMetadata(listOf(unsafe))).hasSize(2)
        assertThat(checks.snapshotMetadata(listOf(diagnostic, similarPackage, safe))).isEmpty()
    }

    @Test
    fun `a package declaration must agree with the physical source path`() {
        val path = "$ROOT.blog.adapter.outbound.persistence.exposed".replace('.', '/') + "/Wrong.kt"
        val text = "package $ROOT.blog.application.service\nimport org.jetbrains.exposed.v1.core.Table"

        assertThatThrownBy { checks.source(path, text) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("path/package mismatch")
    }

    @Test
    fun `a missing declaration cannot silently leave the scan`() {
        val path = "$ROOT.blog.domain".replace('.', '/') + "/Missing.kt"

        assertThatThrownBy { checks.source(path, "class Missing") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("package declaration missing")
    }

    @Test
    fun `unrecognized and outside packages cannot silently leave the scan`() {
        val packages = listOf("$ROOT.blog.misc", "$ROOT.blog.application.unknown", "outside.adapter.outbound.persistence.exposed")

        packages.forEach { packageName ->
            assertThatThrownBy { source(packageName, "class Unknown") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("unrecognized architecture package")
        }
    }

    @Test
    fun `a later valid layer does not override an invalid first reserved marker`() {
        assertThatThrownBy { source("$ROOT.blog.application.unknown.adapter.outbound.persistence.exposed", "class Unknown") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unrecognized architecture package")
    }

    @Test
    fun `only the known bootstrap file may use the root package`() {
        assertThat(source(ROOT, "class BlogApplication", "BlogApplication.kt").packageName).isEqualTo(ROOT)
        assertThatThrownBy { source(ROOT, "class Escaped", "Escaped.kt") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unrecognized architecture package")
    }

    private fun source(
        packageName: String,
        body: String,
        fileName: String = "Fixture.kt",
    ): TargetArchitectureChecks.Source = checks.source(packageName.replace('.', '/') + "/$fileName", "package $packageName\n$body")

    private fun packageName(
        layer: String,
        path: String,
        featureFirst: Boolean,
    ): String = if (featureFirst) "$ROOT.blog.publication.$layer.$path" else "$ROOT.$layer.$path.details.nested"

    private companion object {
        const val ROOT = "xyz.robinjoon.notionblog"
        val publicRoutes = "@GetMapping(\"/\")\n@GetMapping(\"/posts/{postId}\")\n@GetMapping(\"/feed.xml\")"
    }
}

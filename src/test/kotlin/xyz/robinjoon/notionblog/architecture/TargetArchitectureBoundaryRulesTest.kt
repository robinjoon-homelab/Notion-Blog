package xyz.robinjoon.notionblog.architecture

import architecturefixtures.routes.ConstantRoute
import architecturefixtures.routes.CreatePostRoute
import architecturefixtures.routes.DeclaredRoutes
import architecturefixtures.routes.DuplicateRootRoute
import architecturefixtures.routes.ExchangeRoute
import architecturefixtures.routes.ExtraRoute
import architecturefixtures.routes.InheritedRoute
import architecturefixtures.routes.NamedConstantPostPageRoute
import architecturefixtures.routes.PatchRoute
import architecturefixtures.routes.PathArrayRoute
import architecturefixtures.routes.PathAttributeRoute
import architecturefixtures.routes.PostPageAsDeleteRoute
import architecturefixtures.routes.PostPageRoute
import architecturefixtures.routes.PrivateRoute
import architecturefixtures.routes.PublishAsGetRoute
import architecturefixtures.routes.PublishForAnyMethodRoute
import architecturefixtures.routes.PublishRoute
import architecturefixtures.routes.TypePrefixedPublishRoute
import architecturefixtures.routes.TypePrefixedRoute
import architecturefixtures.routes.ValueAttributeRoute
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
    fun `JSON belongs to Notion or persistence while Spring HTTP clients and Notion DTOs remain in Notion`(featureFirst: Boolean) {
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
    fun `HTTP transports serve the Notion and media adapters while Spring HTTP clients stay in Notion`(featureFirst: Boolean) {
        val notion = packageName("adapter.outbound", "notion.client", featureFirst)
        val media = packageName("adapter.outbound", "media.download", featureFirst)
        val transports =
            "import java.net.http.HttpClient\nimport java.net.HttpURLConnection\nimport okhttp3.OkHttpClient\n" +
                "import org.apache.hc.client5.http.classic.HttpClient"
        val spring = "import org.springframework.web.client.RestClient\nimport org.springframework.http.client.JdkClientHttpRequestFactory"
        val outside =
            listOf(
                packageName("adapter.inbound", "web", featureFirst),
                packageName("adapter.outbound", "persistence.exposed", featureFirst),
                packageName("adapter.outbound", "diagnostics", featureFirst),
                packageName("config", "media", featureFirst),
                packageName("application.service", "queries", featureFirst),
                packageName("domain", "post", featureFirst),
            )

        assertThat(checks.adapterTypes(listOf(source(notion, "$transports\n$spring"), source(media, transports)))).isEmpty()
        assertThat(checks.adapterTypes(listOf(source(media, spring)))).hasSize(2)
        outside.forEach { candidate ->
            assertThat(checks.adapterTypes(listOf(source(candidate, transports)))).describedAs(candidate).hasSize(4)
        }
    }

    @Test
    fun `qualified HTTP client references cannot bypass the import check`() {
        val candidate = source("$ROOT.blog.adapter.inbound.web", "val client = java.net.http.HttpClient.newHttpClient()")

        assertThat(checks.adapterTypes(listOf(candidate))).singleElement().asString().contains("java.net.http.HttpClient")
    }

    @Test
    fun `feature and similar package names do not grant media privileges`() {
        val body = "import java.net.http.HttpClient"
        val candidates =
            listOf(
                source("$ROOT.media.application.model", body),
                source("$ROOT.adapter.outbound.mediary", body),
                source("$ROOT.adapter.outbound.presentation.media", body),
            )

        candidates.forEach { candidate ->
            assertThat(checks.adapterTypes(listOf(candidate))).hasSize(1)
        }
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
    fun `web mappings preserve exactly the declared routes across nested packages`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web.controllers.posts", featureFirst)
        val valid = declaredHandlers(web)
        val additional = valid + checks.handler(web, PrivateRoute::class.java)
        val duplicate = valid + checks.handler(web, DuplicateRootRoute::class.java)

        assertThat(checks.webRoutes(valid)).isEmpty()
        assertThat(checks.webRoutes(additional)).isNotEmpty()
        assertThat(checks.webRoutes(duplicate)).isNotEmpty()
        assertThat(checks.webRoutes(emptyList())).isNotEmpty()
    }

    @Test
    fun `a feature named web cannot substitute for the inbound web layer`() {
        val candidate = declaredHandlers("$ROOT.web.application.service")

        assertThat(checks.webRoutes(candidate)).isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `all inbound groups must preserve the same declared routes`(featureFirst: Boolean) {
        val api = declaredHandlers(packageName("adapter.inbound", "api.controllers", featureFirst))
        val extra = checks.handler(packageName("adapter.inbound", "another.controllers", featureFirst), ExtraRoute::class.java)

        assertThat(checks.webRoutes(api)).isEmpty()
        assertThat(checks.webRoutes(api + extra)).isNotEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a route outside inbound adapters fails even when the inbound inventory matches`(featureFirst: Boolean) {
        val inbound = declaredHandlers(packageName("adapter.inbound", "web", featureFirst))
        val misplaced = checks.handler(packageName("config", "web", featureFirst), ExtraRoute::class.java)

        assertThat(checks.webRoutes(inbound + misplaced)).singleElement().asString().contains("outside inbound adapters", "GET /extra")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `HTTP methods are part of the declared route contract`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web", featureFirst)
        val others = checks.handler(web, DeclaredRoutes::class.java)
        val postPage = checks.handler(web, PostPageRoute::class.java)
        val publish = checks.handler(web, PublishRoute::class.java)
        val changedMethods =
            mapOf(
                "publish as GET" to listOf(others, postPage, checks.handler(web, PublishAsGetRoute::class.java)),
                "publish for every method" to listOf(others, postPage, checks.handler(web, PublishForAnyMethodRoute::class.java)),
                "post page as DELETE" to listOf(others, checks.handler(web, PostPageAsDeleteRoute::class.java), publish),
            )

        assertThat(checks.webRoutes(listOf(others, postPage, publish))).isEmpty()
        assertThat(checks.handler(web, PublishForAnyMethodRoute::class.java).routes).containsExactly("ANY /admin/posts/{postId}/publish")
        changedMethods.forEach { (change, handlers) ->
            assertThat(checks.webRoutes(handlers)).describedAs(change).isNotEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `other mapping forms are resolved and fail as undeclared routes`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web.controllers", featureFirst)
        val valid = declaredHandlers(web)
        val undeclared =
            mapOf(
                PatchRoute::class.java to listOf("PATCH /admin/posts/{postId}"),
                CreatePostRoute::class.java to listOf("POST /admin/posts"),
                ValueAttributeRoute::class.java to listOf("GET /value"),
                PathAttributeRoute::class.java to listOf("GET /path"),
                ConstantRoute::class.java to listOf("GET /constant"),
                PathArrayRoute::class.java to listOf("GET /first", "GET /second"),
                TypePrefixedRoute::class.java to listOf("GET /prefix/child"),
                ExchangeRoute::class.java to listOf("GET /exchange"),
                InheritedRoute::class.java to listOf("GET /inherited"),
            )

        undeclared.forEach { (type, routes) ->
            val handler = checks.handler(web, type)
            assertThat(handler.routes).describedAs(type.simpleName).containsExactlyInAnyOrderElementsOf(routes)
            assertThat(checks.webRoutes(valid + handler)).describedAs(type.simpleName).isNotEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `equivalent mapping forms resolve to the declared routes`(featureFirst: Boolean) {
        val web = packageName("adapter.inbound", "web", featureFirst)
        val equivalent =
            listOf(DeclaredRoutes::class.java, NamedConstantPostPageRoute::class.java, TypePrefixedPublishRoute::class.java)
                .map { checks.handler(web, it) }

        assertThat(checks.webRoutes(equivalent)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `routes cannot be registered outside annotated controllers`(featureFirst: Boolean) {
        val config = packageName("config", "web", featureFirst)
        val registrations =
            listOf(
                "import org.springframework.web.servlet.function.router",
                "fun views(registry: ViewControllerRegistry) = registry.addViewController(\"/hidden\")",
                "val mapping = SimpleUrlHandlerMapping(mapOf(\"/hidden\" to handler))",
                "class Hidden : HttpRequestHandler",
                "class Legacy : org.springframework.web.servlet.mvc.Controller",
                "val servlet = ServletRegistrationBean(HiddenServlet(), \"/hidden\")",
            )
        val ordinary = "import org.springframework.web.servlet.config.annotation.WebMvcConfigurer\nval response: HttpServletResponse"

        registrations.forEach { body ->
            assertThat(checks.routeRegistrations(listOf(source(config, body)))).describedAs(body).isNotEmpty()
        }
        assertThat(checks.routeRegistrations(listOf(source(config, ordinary)))).isEmpty()
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

    // 이 컨트롤러들이 함께 운영 경로를 정확히 선언한다.
    private fun declaredHandlers(packageName: String): List<TargetArchitectureChecks.Handler> =
        listOf(DeclaredRoutes::class.java, PostPageRoute::class.java, PublishRoute::class.java).map { checks.handler(packageName, it) }

    private fun packageName(
        layer: String,
        path: String,
        featureFirst: Boolean,
    ): String = if (featureFirst) "$ROOT.blog.publication.$layer.$path" else "$ROOT.$layer.$path.details.nested"

    private companion object {
        const val ROOT = "xyz.robinjoon.notionblog"
    }
}

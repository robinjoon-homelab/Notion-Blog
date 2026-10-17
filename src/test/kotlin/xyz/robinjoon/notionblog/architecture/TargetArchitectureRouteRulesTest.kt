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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TargetArchitectureRouteRulesTest {
    private val checks = TargetArchitectureChecks(ROOT)

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

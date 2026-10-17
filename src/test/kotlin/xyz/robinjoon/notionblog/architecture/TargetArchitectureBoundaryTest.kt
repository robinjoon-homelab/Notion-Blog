package xyz.robinjoon.notionblog.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodIntrospector
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class TargetArchitectureBoundaryTest {
    @Test
    fun `target domain imports only Kotlin or JDK types`() {
        assertThat(checks.domainImports(productionFiles())).describedAs("target domain imports").isEmpty()
    }

    @Test
    fun `source dependencies point from adapters to application to domain`() {
        assertThat(checks.dependencies(productionFiles())).describedAs("forbidden dependency direction").isEmpty()
    }

    @Test
    fun `Exposed types stay inside the Exposed persistence adapter`() {
        assertThat(checks.exposedTypes(productionFiles())).describedAs("Exposed references outside persistence.exposed").isEmpty()
    }

    @Test
    fun `JSON Notion DTOs and HTTP clients stay in their declared output adapters`() {
        assertThat(checks.adapterTypes(productionFiles())).describedAs("JSON HTTP client and Notion DTO boundaries").isEmpty()
    }

    @Test
    fun `production code does not own unsafe typing clocks or repository transactions`() {
        assertThat(checks.runtimePatterns(productionFiles())).describedAs("forbidden production runtime patterns").isEmpty()
    }

    @Test
    fun `legacy symbols and source routes are absent from production`() {
        assertThat(checks.legacySymbols(productionFiles())).describedAs("legacy target symbols and routes").isEmpty()
    }

    @Test
    fun `web production mappings are exactly the declared HTTP methods and routes`() {
        assertThat(checks.webRoutes(productionHandlers())).describedAs("HTTP method and route mappings across all inbound groups").isEmpty()
    }

    @Test
    fun `web routes are registered only through annotated controllers`() {
        assertThat(checks.routeRegistrations(productionFiles())).describedAs("route registration outside controllers").isEmpty()
    }

    @Test
    fun `snapshot mapping does not persist implementation package names`() {
        assertThat(checks.snapshotMetadata(productionFiles())).describedAs("snapshot implementation metadata").isEmpty()
    }

    private companion object {
        val productionRoot: Path = Path.of("src/main/kotlin")
        val checks = TargetArchitectureChecks("xyz.robinjoon.notionblog")

        fun productionFiles(): List<TargetArchitectureChecks.Source> =
            Files.walk(productionRoot).use { paths ->
                paths
                    .iterator()
                    .asSequence()
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map { path -> checks.source(productionRoot.relativize(path).toString(), Files.readString(path)) }
                    .toList()
            }

        // 컴파일된 모든 운영 클래스에서 경로를 읽으므로 소스 언어와 상관없이 컨트롤러를 찾는다.
        fun productionHandlers(): List<TargetArchitectureChecks.Handler> {
            val paths =
                requireNotNull(System.getProperty("architecture.productionClasses")) {
                    "Run architecture tests with Gradle so all production output directories are supplied."
                }.split(File.pathSeparator).map(Path::of)
            return ClassFileImporter()
                .importPaths(paths)
                .map { it.reflect() }
                .filter(checks::isHandler)
                .map { checks.handler(it.packageName, it) }
        }
    }
}

internal class TargetArchitectureChecks(
    private val root: String,
) {
    private val packages = ArchitecturePackages(root)
    private val routeMappings = RouteMappings()

    fun source(
        relativePath: String,
        text: String,
    ): Source {
        val path = relativePath.replace('\\', '/')
        val packageName =
            requireNotNull(packageRegex.find(text)?.groupValues?.get(1)) {
                "$path: package declaration missing"
            }
        val pathPackage = path.substringBeforeLast('/', "").replace('/', '.')
        require(packageName == pathPackage) { "$path: path/package mismatch for $packageName" }
        val location = packages.location(packageName)
        val bootstrap = packageName == root && path.substringAfterLast('/') == "BlogApplication.kt"
        require(location != null || bootstrap) { "$path: unrecognized architecture package $packageName" }
        return Source(path, packageName, text, location)
    }

    fun domainImports(sources: List<Source>): List<String> =
        sources.filter { it.location?.layer == ArchitectureLayer.DOMAIN }.flatMap { source ->
            imports(source.text).filterNot(::isDomainImport).map(source::describe)
        }

    fun dependencies(sources: List<Source>): List<String> =
        sources.flatMap { source ->
            imports(source.text)
                .filter { forbiddenDirection(source.location, importLocation(it)) }
                .map(source::describe)
        }

    fun exposedTypes(sources: List<Source>): List<String> =
        sources
            .filterNot { it.location.isAt(ArchitectureLayer.OUTBOUND, "persistence", "exposed") }
            .filter { it.text.contains("org.jetbrains.exposed.") }
            .map { it.describe("Exposed reference outside persistence.exposed") }

    // HTTP 클라이언트 참조는 전체 텍스트에서 찾으므로 정규화된 이름으로 import 검사를 우회할 수 없다.
    fun adapterTypes(sources: List<Source>): List<String> =
        sources.flatMap { source ->
            val imports = imports(source.text).filter { isForbiddenAdapterImport(it, source.location) }
            val httpClients = httpClientRegex.findAll(source.text).map { it.value }.filter { isForbiddenHttpClient(it, source.location) }
            (imports + httpClients).map(source::describe)
        }

    fun runtimePatterns(sources: List<Source>): List<String> =
        sources.flatMap { source ->
            val forbidden = matches(source, forbiddenTokenRegex)
            if (source.location.isAt(ArchitectureLayer.OUTBOUND, "persistence") &&
                repositoryTransactionRegex.containsMatchIn(source.text)
            ) {
                forbidden + source.describe("repository transaction block")
            } else {
                forbidden
            }
        }

    fun legacySymbols(sources: List<Source>): List<String> =
        sources.flatMap { matches(it, legacySymbolRegex) + matches(it, legacyRouteRegex) }

    fun isHandler(type: Class<*>): Boolean = routeMappings.handles(type)

    // 패키지를 따로 받아 픽스처 컨트롤러를 어느 배치에도 둘 수 있게 한다.
    fun handler(
        packageName: String,
        type: Class<*>,
    ): Handler {
        require(routeMappings.handles(type)) { "${type.name}: not a Spring MVC controller" }
        val location = requireNotNull(packages.location(packageName)) { "${type.name}: unrecognized architecture package $packageName" }
        return Handler(type.name, location, routeMappings.routes(type))
    }

    fun webRoutes(handlers: List<Handler>): List<String> {
        val (inbound, outside) = handlers.partition { it.location.layer == ArchitectureLayer.INBOUND }
        val routes = inbound.flatMap { it.routes }
        val misplaced = outside.filter { it.routes.isNotEmpty() }.map { it.describe("routes outside inbound adapters ${it.routes}") }
        return if (routes.sorted() == allowedWebRoutes.sorted()) {
            misplaced
        } else {
            misplaced + "expected exactly $allowedWebRoutes, found $routes"
        }
    }

    // 경로 목록은 어노테이션이 붙은 컨트롤러만 읽으므로 핸들러를 등록하는 다른 방법은 모두 거부한다.
    fun routeRegistrations(sources: List<Source>): List<String> = sources.flatMap { matches(it, routeRegistrationRegex) }

    fun snapshotMetadata(sources: List<Source>): List<String> =
        sources
            .filter { it.location.isAt(ArchitectureLayer.OUTBOUND, "persistence", "snapshot") }
            .flatMap { matches(it, snapshotClassMetadataRegex) }

    private fun isDomainImport(reference: String): Boolean =
        reference.startsWith("kotlin.") || reference.startsWith("java.") || reference.startsWith("javax.") ||
            importLocation(reference)?.layer == ArchitectureLayer.DOMAIN

    private fun importLocation(reference: String): ArchitectureLocation? = packages.location(reference.substringBeforeLast('.', ""))

    private fun forbiddenDirection(
        origin: ArchitectureLocation?,
        target: ArchitectureLocation?,
    ): Boolean =
        when {
            origin?.layer == ArchitectureLayer.DOMAIN -> target?.isApplication == true || target?.isAdapter == true
            origin?.isApplication == true -> target?.isAdapter == true
            else -> false
        }

    private fun isForbiddenAdapterImport(
        reference: String,
        location: ArchitectureLocation?,
    ): Boolean {
        val notion = location.isAt(ArchitectureLayer.OUTBOUND, "notion")
        return when {
            reference.startsWith("tools.jackson.databind.JsonNode") -> !notion && !location.isAt(ArchitectureLayer.OUTBOUND, "persistence")
            importLocation(reference).isAt(ArchitectureLayer.OUTBOUND, "notion", "dto") -> !notion
            else -> false
        }
    }

    // Spring HTTP 클라이언트는 Notion API 클라이언트만 쓴다. 일반 HTTP 전송은 미디어 다운로드에도 쓰이며,
    // 이는 Notion 토큰을 실은 클라이언트와 공유하면 안 된다.
    private fun isForbiddenHttpClient(
        reference: String,
        location: ArchitectureLocation?,
    ): Boolean =
        when {
            location.isAt(ArchitectureLayer.OUTBOUND, "notion") -> false
            reference.startsWith("org.springframework.") -> true
            else -> !location.isAt(ArchitectureLayer.OUTBOUND, "media")
        }

    private fun ArchitectureLocation?.isAt(
        layer: ArchitectureLayer,
        vararg prefix: String,
    ): Boolean = this != null && this.layer == layer && path.take(prefix.size) == prefix.toList()

    private fun imports(text: String): List<String> = importRegex.findAll(text).map { it.groupValues[1] }.toList()

    private fun matches(
        source: Source,
        pattern: Regex,
    ): List<String> = pattern.findAll(source.text).map { source.describe(it.value) }.toList()

    internal data class Source(
        val relativePath: String,
        val packageName: String,
        val text: String,
        val location: ArchitectureLocation?,
    ) {
        fun describe(reference: String): String = "$relativePath: $reference"
    }

    internal data class Handler(
        val name: String,
        val location: ArchitectureLocation,
        val routes: List<String>,
    ) {
        fun describe(reference: String): String = "$name: $reference"
    }

    // Spring MVC가 시작할 때와 똑같이 매핑을 해석한다. 합성·상속 어노테이션, value·path 별칭, 배열, 상수,
    // 타입 수준 접두사, @HttpExchange를 다룬다. 메서드가 없는 매핑은 모든 메서드를 받으므로 ANY로 적고
    // 선언된 경로와 절대 일치하지 않는다.
    private class RouteMappings : RequestMappingHandlerMapping() {
        fun handles(type: Class<*>): Boolean = isHandler(type)

        fun routes(type: Class<*>): List<String> {
            val userType = ClassUtils.getUserClass(type)
            val mappings =
                MethodIntrospector.selectMethods(
                    userType,
                    MethodIntrospector.MetadataLookup<RequestMappingInfo> { getMappingForMethod(it, userType) },
                )
            return mappings.values.flatMap(::describe)
        }

        private fun describe(mapping: RequestMappingInfo): List<String> {
            val methods =
                mapping.methodsCondition.methods
                    .map { it.name }
                    .ifEmpty { listOf("ANY") }
            return methods.flatMap { method -> mapping.patternValues.map { path -> "$method $path" } }
        }
    }

    private companion object {
        val importRegex = Regex("(?m)^import\\s+([^\\s;]+)")
        val packageRegex = Regex("(?m)^package\\s+([^\\s;]+)")
        val forbiddenTokenRegex = Regex("activateDefaultTyping|@class|(?<![A-Za-z0-9_])Instant\\.now\\s*\\(")
        val repositoryTransactionRegex = Regex("(?s)\\btransaction\\s*\\{")
        val legacySymbolRegex =
            Regex(
                "\\b(?:Slug|PageRoute(?:Kind|s)?|NotionGateway|BlogPersistencePort|TaggedPageSnapshotCodec|" +
                    "NotionPageRenderer|PageAccessService|TransactionalPageStore|TransactionalSettingsStore)\\b",
            )
        val legacyRouteRegex = Regex("/\\{slug}|/notion/\\{pageId}")
        val httpClientRegex =
            Regex(
                "(?:org\\.springframework\\.(?:web|http)\\.client|java\\.net\\.http|okhttp3|org\\.apache\\.(?:hc|http))\\.[\\w.]*|" +
                    "java\\.net\\.(?:Http)?URLConnection\\b",
            )
        val routeRegistrationRegex =
            Regex(
                "org\\.springframework\\.web\\.servlet\\.function\\.|org\\.springframework\\.web\\.servlet\\.mvc\\.Controller\\b|" +
                    "\\b(?:RouterFunctions?|ViewControllerRegistry|HandlerMapping|(?:Simple|BeanName)UrlHandlerMapping|" +
                    "HttpRequestHandler|ServletRegistrationBean|WebServlet|HttpServlet)\\b",
            )
        val snapshotClassMetadataRegex =
            Regex(
                "\\\"(?:xyz\\.|java\\.|kotlin\\.)[^\\\"]+\\\"|(?:javaClass\\.name|::class\\.qualifiedName|Class\\.forName|qualifiedName)",
            )
        val allowedWebRoutes =
            listOf(
                "GET /",
                "GET /posts/{postId}",
                "GET /feed.xml",
                "GET /media/{mediaKey}",
                "GET /admin/posts",
                "GET /admin/posts/{postId}",
                "POST /admin/posts/{postId}/refresh",
                "POST /admin/posts/{postId}/publish",
                "POST /admin/posts/{postId}/unpublish",
                "POST /admin/posts/{postId}/archive",
                "POST /admin/posts/{postId}/restore",
                "GET /admin/media/{mediaKey}",
            )
    }
}

package xyz.robinjoon.notionblog.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    fun `JSON stays in output adapters while HTTP and Notion DTOs stay in the Notion adapter`() {
        assertThat(checks.adapterTypes(productionFiles())).describedAs("JSON HTTP and Notion DTO boundaries").isEmpty()
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
    fun `web production mappings are limited to the root post id and RSS routes`() {
        assertThat(checks.webRoutes(productionFiles())).describedAs("public route mappings across all inbound groups").isEmpty()
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
    }
}

internal class TargetArchitectureChecks(
    private val root: String,
) {
    private val packages = ArchitecturePackages(root)

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

    fun adapterTypes(sources: List<Source>): List<String> =
        sources.flatMap { source ->
            imports(source.text).filter { isForbiddenAdapterImport(it, source.location) }.map(source::describe)
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

    fun webRoutes(sources: List<Source>): List<String> {
        val inbound = sources.filter { it.location?.layer == ArchitectureLayer.INBOUND }
        val routes = inbound.flatMap { source -> webMappingRegex.findAll(source.text).map { it.groupValues[2] }.toList() }
        return if (routes.sorted() == allowedWebRoutes.sorted()) {
            emptyList()
        } else {
            listOf("expected exactly $allowedWebRoutes, found $routes")
        }
    }

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
            reference.startsWith("org.springframework.web.client.RestClient") -> !notion
            importLocation(reference).isAt(ArchitectureLayer.OUTBOUND, "notion", "dto") -> !notion
            else -> false
        }
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
        val webMappingRegex = Regex("@(GetMapping|RequestMapping|PostMapping|PutMapping|DeleteMapping)\\s*\\(\\s*\\\"([^\\\"]+)")
        val snapshotClassMetadataRegex =
            Regex(
                "\\\"(?:xyz\\.|java\\.|kotlin\\.)[^\\\"]+\\\"|(?:javaClass\\.name|::class\\.qualifiedName|Class\\.forName|qualifiedName)",
            )
        val allowedWebRoutes = listOf("/", "/posts/{postId}", "/feed.xml")
    }
}

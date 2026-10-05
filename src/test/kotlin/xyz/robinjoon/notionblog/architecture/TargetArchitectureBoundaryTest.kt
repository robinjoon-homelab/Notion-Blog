package xyz.robinjoon.notionblog.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TargetArchitectureBoundaryTest {
    @Test
    fun `target domain imports only Kotlin or JDK types`() {
        val violations = productionFiles()
            .filter { it.relativePath.isTargetDomainFile() }
            .flatMap { source ->
                imports(source.text)
                    .filterNot(::isKotlinJdkOrTargetDomainImport)
                    .map { "${source.relativePath}: $it" }
            }

        assertThat(violations).describedAs("target domain imports").isEmpty()
    }

    @Test
    fun `source dependencies point from adapters to application to domain`() {
        val violations = productionFiles().flatMap { source ->
            val imported = imports(source.text)
            when {
                source.packageName.startsWith("xyz.robinjoon.notionblog.domain.") ->
                    imported
                        .filter { it.startsWith("xyz.robinjoon.notionblog.application.") || it.startsWith("xyz.robinjoon.notionblog.adapter.") }
                        .map { "${source.relativePath}: $it" }

                source.packageName.startsWith("xyz.robinjoon.notionblog.application.") ->
                    imported
                        .filter { it.startsWith("xyz.robinjoon.notionblog.adapter.") }
                        .map { "${source.relativePath}: $it" }

                else -> emptyList()
            }
        }

        assertThat(violations).describedAs("forbidden dependency direction").isEmpty()
    }

    @Test
    fun `Exposed types stay inside the Exposed persistence adapter`() {
        val violations = productionFiles()
            .filterNot { it.relativePath.isInsideExposedPersistence() }
            .flatMap { source ->
                val exposedReferences = buildList {
                    addAll(imports(source.text).filter { it.startsWith("org.jetbrains.exposed.") })
                    if (source.text.contains("org.jetbrains.exposed.")) {
                        add("qualified Exposed reference")
                    }
                }
                exposedReferences.map { "${source.relativePath}: $it" }
            }

        assertThat(violations).describedAs("Exposed references outside adapter/output/persistence/exposed")
            .isEmpty()
    }

    @Test
    fun `JSON stays in output adapters while HTTP and Notion DTOs stay in the Notion adapter`() {
        val violations = productionFiles()
            .flatMap { source ->
                val boundaryReferences = buildList {
                    val sourceImports = imports(source.text)
                    if (!source.relativePath.isInsideNotionAdapter() && !source.relativePath.isInsidePersistenceAdapter()) {
                        addAll(sourceImports.filter { it.startsWith("tools.jackson.databind.JsonNode") })
                    }
                    if (!source.relativePath.isInsideNotionAdapter()) {
                        addAll(sourceImports.filter { it.startsWith("org.springframework.web.client.RestClient") })
                        addAll(
                            sourceImports.filter {
                                it.startsWith("xyz.robinjoon.notionblog.adapter.output.notion.dto.")
                            },
                        )
                    }
                }
                boundaryReferences.map { "${source.relativePath}: $it" }
            }

        assertThat(violations).describedAs("JSON/HTTP/Notion boundary references outside their output adapters")
            .isEmpty()
    }

    @Test
    fun `production code does not own unsafe typing clocks or repository transactions`() {
        val sources = productionFiles()
        val violations = buildList {
            sources.filter { forbiddenTokenRegex.containsMatchIn(it.text) }.forEach { source ->
                forbiddenTokenRegex.findAll(source.text).forEach { match ->
                    add("${source.relativePath}: ${match.value}")
                }
            }
            sources.filter { it.relativePath.isPersistenceFile() && repositoryTransactionRegex.containsMatchIn(it.text) }
                .forEach { source ->
                    add("${source.relativePath}: repository transaction block")
                }
        }

        assertThat(violations).describedAs("forbidden production runtime patterns").isEmpty()
    }

    @Test
    fun `legacy symbols and source routes are absent from production`() {
        val sources = productionFiles()
        val violations = sources.flatMap { source ->
            legacySymbolRegex.findAll(source.text).map { "${source.relativePath}: ${it.value}" }.toList()
        } + sources.flatMap { source ->
            legacyRouteRegex.findAll(source.text).map { "${source.relativePath}: ${it.value}" }.toList()
        }

        assertThat(violations).describedAs("legacy target symbols/routes").isEmpty()
    }

    @Test
    fun `web production mappings are limited to the root post id and RSS routes`() {
        val routes = productionFiles()
            .filter { it.relativePath.isWebFile() }
            .flatMap { source ->
                webMappingRegex.findAll(source.text).map { it.groupValues[2] }.toList()
            }

        assertThat(routes).describedAs("web route mappings")
            .containsExactlyInAnyOrderElementsOf(allowedWebRoutes)
    }

    @Test
    fun `snapshot mapping does not persist implementation package names`() {
        val violations = productionFiles()
            .filter { it.relativePath.isSnapshotFile() }
            .flatMap { source ->
                snapshotClassMetadataRegex.findAll(source.text)
                    .map { "${source.relativePath}: ${it.value}" }
                    .toList()
            }

        assertThat(violations).describedAs("snapshot implementation metadata").isEmpty()
    }

    private data class ProductionSource(
        val relativePath: String,
        val packageName: String,
        val text: String,
    )

    private companion object {
        val productionRoot: Path = Path.of("src/main/kotlin")
        val importRegex = Regex("(?m)^import\\s+([^\\s]+)")
        val packageRegex = Regex("(?m)^package\\s+([^\\s]+)")
        val forbiddenTokenRegex = Regex("activateDefaultTyping|@class|(?<![A-Za-z0-9_])Instant\\.now\\s*\\(")
        val repositoryTransactionRegex = Regex("(?s)\\btransaction\\s*\\{")
        val legacySymbolRegex = Regex(
            "\\b(?:Slug|PageRoute(?:Kind|s)?|NotionGateway|BlogPersistencePort|TaggedPageSnapshotCodec|" +
                "NotionPageRenderer|PageAccessService|TransactionalPageStore|TransactionalSettingsStore)\\b",
        )
        val legacyRouteRegex = Regex("/\\{slug}|/notion/\\{pageId}")
        val webMappingRegex = Regex("@(GetMapping|RequestMapping|PostMapping|PutMapping|DeleteMapping)\\s*\\(\\s*\\\"([^\\\"]+)")
        val snapshotClassMetadataRegex = Regex(
            "\\\"(?:xyz\\.|java\\.|kotlin\\.)[^\\\"]+\\\"|(?:javaClass\\.name|::class\\.qualifiedName|Class\\.forName|qualifiedName)",
        )
        val allowedWebRoutes = setOf("/", "/posts/{postId}", "/feed.xml")

        fun productionFiles(): List<ProductionSource> = Files.walk(productionRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { path ->
                    val text = Files.readString(path)
                    ProductionSource(
                        relativePath = productionRoot.relativize(path).toString().replace('\\', '/'),
                        packageName = packageRegex.find(text)?.groupValues?.get(1).orEmpty(),
                        text = text,
                    )
                }
                .toList()
        }

        fun imports(source: String): List<String> = importRegex.findAll(source).map { it.groupValues[1] }.toList()

        fun isKotlinJdkOrTargetDomainImport(import: String): Boolean = import.startsWith("kotlin.") || import.startsWith("java.") || import.startsWith("javax.") ||
            targetDomainPackages.any { import.startsWith(it) }

        val targetDomainPackages = listOf(
            "xyz.robinjoon.notionblog.domain.post.",
            "xyz.robinjoon.notionblog.domain.publication.",
            "xyz.robinjoon.notionblog.domain.site.",
            "xyz.robinjoon.notionblog.domain.source.",
            "xyz.robinjoon.notionblog.domain.sync.",
        )

        fun String.isTargetDomainFile(): Boolean = startsWith("xyz/robinjoon/notionblog/domain/post/") ||
            startsWith("xyz/robinjoon/notionblog/domain/publication/") ||
            startsWith("xyz/robinjoon/notionblog/domain/site/") ||
            startsWith("xyz/robinjoon/notionblog/domain/source/") ||
            startsWith("xyz/robinjoon/notionblog/domain/sync/")

        fun String.isInsideExposedPersistence(): Boolean = startsWith("xyz/robinjoon/notionblog/adapter/output/persistence/exposed/")

        fun String.isInsideNotionAdapter(): Boolean = startsWith("xyz/robinjoon/notionblog/adapter/output/notion/")

        fun String.isInsidePersistenceAdapter(): Boolean = startsWith("xyz/robinjoon/notionblog/adapter/output/persistence/")

        fun String.isPersistenceFile(): Boolean = contains("/adapter/output/persistence/") || contains("/adapter/out/persistence/")

        fun String.isWebFile(): Boolean = contains("/adapter/input/web/") || contains("/adapter/in/web/")

        fun String.isSnapshotFile(): Boolean = contains("/snapshot/") || substringAfterLast('/').contains("Snapshot")
    }
}

package xyz.robinjoon.notionblog.adapter.outbound.presentation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.application.model.ResolvedPresentationAsset
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef

class ClasspathPresentationAssetCatalogTest {
    @Test
    fun `resolves a descriptor only for the exact registered asset reference`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", "sha256-theme")
        val catalog = ClasspathPresentationAssetCatalog(mapOf(reference to descriptor))

        assertThat(catalog.resolve(reference)).isEqualTo(descriptor)
        assertThat(catalog.resolve(PresentationAssetRef("theme", 2, "sha256-theme"))).isNull()
        assertThat(catalog.resolve(PresentationAssetRef("theme", 3, "sha256-other"))).isNull()
        assertThat(catalog.resolve(PresentationAssetRef("other-theme", 3, "sha256-theme"))).isNull()
    }

    @Test
    fun `resolves the explicitly registered current reference for a key`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", "sha256-theme")
        val catalog =
            ClasspathPresentationAssetCatalog(
                mapOf(reference to descriptor),
                currentReferences = mapOf("theme" to reference),
            )

        assertThat(catalog.resolveCurrent("theme")).isEqualTo(ResolvedPresentationAsset(reference, descriptor))
    }

    @Test
    fun `an exact asset without a current reference does not resolve as current`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", reference.integrity)
        val catalog = ClasspathPresentationAssetCatalog(mapOf(reference to descriptor))

        assertThat(catalog.resolveCurrent("theme")).isNull()
    }

    @Test
    fun `rejects a current reference that is missing from the exact registry`() {
        val currentReference = PresentationAssetRef("theme", 3, "sha256-theme")

        assertThatIllegalArgumentException()
            .isThrownBy {
                ClasspathPresentationAssetCatalog(
                    registry = emptyMap(),
                    currentReferences = mapOf("theme" to currentReference),
                )
            }.withMessageContaining("current")
    }

    @Test
    fun `rejects a current map entry whose key differs from the reference key`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", reference.integrity)

        assertThatIllegalArgumentException()
            .isThrownBy {
                ClasspathPresentationAssetCatalog(
                    registry = mapOf(reference to descriptor),
                    currentReferences = mapOf("other-key" to reference),
                )
            }.withMessageContaining("key")
    }

    @Test
    fun `rejects a current reference whose integrity does not match the registered exact reference`() {
        val registeredReference = PresentationAssetRef("theme", 3, "sha256-registered")
        val currentReference = PresentationAssetRef("theme", 3, "sha256-current")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", registeredReference.integrity)

        assertThatIllegalArgumentException()
            .isThrownBy {
                ClasspathPresentationAssetCatalog(
                    registry = mapOf(registeredReference to descriptor),
                    currentReferences = mapOf("theme" to currentReference),
                )
            }.withMessageContaining("exact registry")
    }

    @Test
    fun `does not guess the highest version when current reference points to an older version`() {
        val olderReference = PresentationAssetRef("theme", 1, "sha256-old")
        val newerReference = PresentationAssetRef("theme", 2, "sha256-new")
        val olderDescriptor = PresentationAssetDescriptor("/presentation/theme-v1.css", "text/css", olderReference.integrity)
        val newerDescriptor = PresentationAssetDescriptor("/presentation/theme-v2.css", "text/css", newerReference.integrity)
        val catalog =
            ClasspathPresentationAssetCatalog(
                registry = mapOf(olderReference to olderDescriptor, newerReference to newerDescriptor),
                currentReferences = mapOf("theme" to olderReference),
            )

        assertThat(catalog.resolveCurrent("theme"))
            .isEqualTo(ResolvedPresentationAsset(olderReference, olderDescriptor))
    }

    @Test
    fun `keeps a snapshot of the deployment registry instead of following later map mutations`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")
        val descriptor = PresentationAssetDescriptor("/presentation/theme.css", "text/css", "sha256-theme")
        val registry = linkedMapOf(reference to descriptor)
        val catalog = ClasspathPresentationAssetCatalog(registry)
        val laterReference = PresentationAssetRef("later", 1, "sha256-later")

        registry[laterReference] = PresentationAssetDescriptor("/presentation/later.css", "text/css", "sha256-later")

        assertThat(catalog.resolve(reference)).isEqualTo(descriptor)
        assertThat(catalog.resolve(laterReference)).isNull()
    }

    @Test
    fun `rejects public paths that are not rooted at exactly one slash`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")

        listOf("presentation/theme.css", "//cdn.example/theme.css", "https://cdn.example/theme.css").forEach { path ->
            assertThatIllegalArgumentException().isThrownBy {
                ClasspathPresentationAssetCatalog(
                    mapOf(reference to PresentationAssetDescriptor(path, "text/css", reference.integrity)),
                )
            }
        }
    }

    @Test
    fun `rejects media types outside stylesheet and script types`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")

        assertThatIllegalArgumentException().isThrownBy {
            ClasspathPresentationAssetCatalog(
                mapOf(reference to PresentationAssetDescriptor("/presentation/theme.html", "text/html", reference.integrity)),
            )
        }
    }

    @Test
    fun `accepts stylesheet and script media types`() {
        val stylesheet = PresentationAssetRef("theme", 3, "sha256-theme")
        val classicScript = PresentationAssetRef("theme-script", 1, "sha256-classic")
        val moduleScript = PresentationAssetRef("module-script", 1, "sha256-module")
        val catalog =
            ClasspathPresentationAssetCatalog(
                mapOf(
                    stylesheet to PresentationAssetDescriptor("/presentation/theme.css", "text/css", stylesheet.integrity),
                    classicScript to
                        PresentationAssetDescriptor("/presentation/theme.js", "application/javascript", classicScript.integrity),
                    moduleScript to PresentationAssetDescriptor("/presentation/module.js", "text/javascript", moduleScript.integrity),
                ),
            )

        assertThat(catalog.resolve(stylesheet)).isNotNull
        assertThat(catalog.resolve(classicScript)).isNotNull
        assertThat(catalog.resolve(moduleScript)).isNotNull
    }

    @Test
    fun `rejects blank or mismatched descriptor integrity`() {
        val reference = PresentationAssetRef("theme", 3, "sha256-theme")

        assertThatIllegalArgumentException().isThrownBy {
            ClasspathPresentationAssetCatalog(
                mapOf(reference to PresentationAssetDescriptor("/presentation/theme.css", "text/css", " ")),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            ClasspathPresentationAssetCatalog(
                mapOf(reference to PresentationAssetDescriptor("/presentation/theme.css", "text/css", "sha256-other")),
            )
        }
    }
}

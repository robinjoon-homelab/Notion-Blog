package xyz.robinjoon.notionblog.domain.site

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.util.UUID

class SiteConfigurationTest {
    @Test
    fun `site configuration uses source document references and a typed presentation profile`() {
        val root = SourceDocumentRef(SourceId("notion-main"), "root-document")
        val profile =
            PresentationProfileRef(
                id = PresentationProfileId(UUID.fromString("e51c30e2-3a84-44d4-8ac6-0b7cea2db2dc")),
                version = 2,
            )

        val configuration =
            SiteConfiguration(
                publicationId = PublicationId(UUID.fromString("78b8b8a2-a5ae-4e3a-bc90-72f58fd1767f")),
                rootDocument = root,
                headerDocument = null,
                footerDocument = null,
                metadata = SiteMetadata("My blog", "A description", "ko-KR", favicon = null),
                presentationProfile = profile,
            )

        assertThat(configuration.rootDocument).isEqualTo(root)
        assertThat(configuration.publicationId)
            .isEqualTo(PublicationId(UUID.fromString("78b8b8a2-a5ae-4e3a-bc90-72f58fd1767f")))
        assertThat(configuration.presentationProfile).isEqualTo(profile)
    }

    @Test
    fun `presentation profiles select closed semantic tokens and trusted asset references`() {
        val asset = PresentationAssetRef(key = "blog-base", version = 4, integrity = "sha384-fixed-hash")
        val profile =
            PresentationProfile(
                id = PresentationProfileId(UUID.fromString("e51c30e2-3a84-44d4-8ac6-0b7cea2db2dc")),
                key = PresentationProfileKey("default"),
                version = 4,
                tokens =
                    PresentationTokens(
                        colorMode = PresentationColorMode.SYSTEM,
                        contentWidth = PresentationContentWidth.STANDARD,
                        density = PresentationDensity.COMFORTABLE,
                    ),
                styleSheets = listOf(asset),
                scripts = emptyList(),
            )

        assertThat(profile.styleSheets).containsExactly(asset)
        assertThat(profile.tokens.colorMode).isEqualTo(PresentationColorMode.SYSTEM)
    }

    @Test
    fun `presentation asset versions cannot be negative`() {
        assertThatIllegalArgumentException().isThrownBy {
            PresentationAssetRef(key = "blog-base", version = -1, integrity = "sha384-fixed-hash")
        }
    }

    @Test
    fun `site metadata requires meaningful text fields`() {
        assertThatIllegalArgumentException().isThrownBy {
            SiteMetadata(siteName = " ", defaultDescription = null, languageTag = "ko-KR", favicon = null)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SiteMetadata(siteName = "My blog", defaultDescription = " ", languageTag = "ko-KR", favicon = null)
        }
        assertThatIllegalArgumentException().isThrownBy {
            SiteMetadata(siteName = "My blog", defaultDescription = null, languageTag = " ", favicon = null)
        }
    }
}

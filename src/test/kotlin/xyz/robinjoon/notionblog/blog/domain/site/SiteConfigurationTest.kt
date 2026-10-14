package xyz.robinjoon.notionblog.blog.domain.site

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef

class SiteConfigurationTest {
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

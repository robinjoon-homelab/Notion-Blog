package xyz.robinjoon.notionblog.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Duration

// Spring은 생성자로 바인딩하므로 검증은 속성을 직접 만들어 확인한다.
class MediaPropertiesTest {
    @Test
    fun `defaults to the agreed per-file and per-import limits`() {
        val properties = MediaProperties()

        assertThat(properties.maxBytes).isEqualTo(25L * 1024 * 1024)
        assertThat(properties.maxImportBytes).isEqualTo(100L * 1024 * 1024)
        assertThat(properties.requestTimeout).isEqualTo(Duration.ofSeconds(10))
        assertThat(properties.maxRedirects).isEqualTo(3)
    }

    @Test
    fun `rejects limits outside the supported range`() {
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(maxBytes = 0) }
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(maxBytes = 1_000_000_001, maxImportBytes = 2_000_000_000) }
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(maxBytes = 10, maxImportBytes = 9) }
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(requestTimeout = Duration.ZERO) }
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(maxRedirects = -1) }
        assertThatIllegalArgumentException().isThrownBy { MediaProperties(maxRedirects = 11) }
    }
}

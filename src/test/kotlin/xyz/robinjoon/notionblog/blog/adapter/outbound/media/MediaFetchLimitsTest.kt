package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

class MediaFetchLimitsTest {
    @ParameterizedTest
    @CsvSource("0, 1, 0", "1, 0, 0", "1000000001, 1, 0", "1, 1, -1", "1, 1, 11")
    fun `rejects fetch limits outside the supported range`(
        maxBytes: Long,
        timeoutSeconds: Long,
        maxRedirects: Int,
    ) {
        assertThatIllegalArgumentException().isThrownBy {
            MediaFetchLimits(maxBytes, Duration.ofSeconds(timeoutSeconds), maxRedirects)
        }
    }
}

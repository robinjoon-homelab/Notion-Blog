package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class MediaContentTypesTest {
    @ParameterizedTest
    @CsvSource(
        "image/png, image/png",
        "'IMAGE/JPEG; charset=binary', image/jpeg",
        "' video/mp4 ', video/mp4",
        "application/pdf, application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document, " +
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )
    fun `keeps a retained header type as lowercase type and subtype`(
        header: String,
        stored: String,
    ) {
        assertThat(MediaContentTypes.stored(header)).isEqualTo(stored)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "image/svg+xml", "text/html", "application/xhtml+xml", "text/javascript", "application/xml",
            "application/octet-stream", "binary/octet-stream", "image", "image/", "image/png extra", "",
        ],
    )
    @NullSource
    fun `stores any other header type as an octet stream`(header: String?) {
        assertThat(MediaContentTypes.stored(header)).isEqualTo("application/octet-stream")
    }
}

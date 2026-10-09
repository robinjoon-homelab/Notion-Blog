package xyz.robinjoon.notionblog.adapter.inbound.web

internal object RssXmlText {
    fun clean(value: String): String =
        buildString {
            value.codePoints().forEach { codePoint ->
                if (
                    codePoint == 0x09 || codePoint == 0x0A || codePoint == 0x0D ||
                    codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD ||
                    codePoint in 0x10000..0x10FFFF
                ) {
                    appendCodePoint(codePoint)
                }
            }
        }

    fun postTitle(value: String): String = clean(value).ifBlank { "제목 없는 글" }
}

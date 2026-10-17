package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 노션은 속성 ID를 URL 인코딩해 반환하며, 설정에는 어느 형태든 올 수 있다. */
internal object NotionPropertyIds {
    fun decode(value: String): String =
        try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Notion property ID is malformed", exception)
        }

    fun same(
        first: String,
        second: String,
    ): Boolean = first == second || decode(first) == decode(second)
}

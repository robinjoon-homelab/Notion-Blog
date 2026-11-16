package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceConfigurationException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

// 입력 주소는 식별자 추출에만 사용한다. 토큰을 붙인 요청은 기존 Notion API 호스트로만 보낸다.
internal object NotionMigrationLocation {
    fun id(value: String): String {
        val location = value.trim()
        NotionIdNormalizer.normalizeOrNull(location)?.let { return it }
        val uri = parse(location)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme != "https" || !isNotionHost(host) || uri.userInfo != null || uri.port != -1) {
            throw SourceConfigurationException("A Notion database HTTPS URL or ID is required")
        }
        val lastSegment =
            uri.path
                .orEmpty()
                .trimEnd('/')
                .substringAfterLast('/')
        val id =
            identifier.find(lastSegment)?.value
                ?: throw SourceConfigurationException("Notion database URL does not contain a valid ID")
        return NotionIdNormalizer.normalize(id)
    }

    private fun parse(value: String): URI =
        try {
            URI(value)
        } catch (exception: URISyntaxException) {
            throw SourceConfigurationException("Notion database URL is malformed", exception)
        }

    private fun isNotionHost(host: String?): Boolean =
        host in setOf("notion.so", "www.notion.so", "app.notion.com") || host?.endsWith(".notion.site") == true

    private val identifier = Regex("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$")
}

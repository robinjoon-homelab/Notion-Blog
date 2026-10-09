package xyz.robinjoon.notionblog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.application.port.output.source.SourceConfigurationException
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId

class NotionSettingsMapperTest {
    private val sourceId = SourceId("notion-main")
    private val mapper = NotionSettingsMapper(sourceId)

    @Test
    fun `maps the first enabled rows and only the head allowlist into source neutral configuration`() {
        val configuration =
            mapper.map(
                listOf(
                    row("rootPage", "page", page = "https://workspace.notion.site/Root-0123456789abcdef0123456789abcdef"),
                    row("rootPage", "blocks", page = "ffffffffffffffffffffffffffffffff"),
                    row("header", "blocks", page = "11111111111111111111111111111111"),
                    row("footer", "blocks", page = "22222222222222222222222222222222"),
                    row(
                        key = "head",
                        kind = "head",
                        data =
                            """
                            {"siteName":"Notion Blog","defaultDescription":"A blog","languageTag":"ko-KR",
                              "faviconAssetKey":"favicon","presentationProfileKey":"default"}
                            """.trimIndent(),
                    ),
                ),
            )

        assertThat(configuration.rootDocument)
            .isEqualTo(SourceDocumentRef(sourceId, "0123456789abcdef0123456789abcdef"))
        assertThat(configuration.headerDocument)
            .isEqualTo(SourceDocumentRef(sourceId, "11111111111111111111111111111111"))
        assertThat(configuration.footerDocument)
            .isEqualTo(SourceDocumentRef(sourceId, "22222222222222222222222222222222"))
        assertThat(configuration.metadata.siteName).isEqualTo("Notion Blog")
        assertThat(configuration.metadata.defaultDescription).isEqualTo("A blog")
        assertThat(configuration.metadata.languageTag).isEqualTo("ko-KR")
        assertThat(configuration.metadata.faviconAssetKey).isEqualTo("favicon")
        assertThat(configuration.presentationProfileKey).isEqualTo(PresentationProfileKey("default"))
    }

    @Test
    fun `ignores disabled rows before validating their kind or data`() {
        val configuration =
            mapper.map(
                listOf(
                    row("rootPage", "page", page = "0123456789abcdef0123456789abcdef"),
                    disabledRow("header", "script", page = "not-a-page-id"),
                    disabledRow("head", "head", data = "<script>steal()</script>"),
                ),
            )

        assertThat(configuration.headerDocument).isNull()
        assertThat(configuration.metadata.siteName).isEqualTo("Blog")
    }

    @Test
    fun `canonicalizes raw and Notion URL page references to lowercase 32 hexadecimal ids`() {
        val configuration =
            mapper.map(
                listOf(
                    row("rootPage", "page", page = "01234567-89AB-CDEF-0123-456789ABCDEF"),
                    row(
                        "header",
                        "blocks",
                        page = "https://workspace.notion.site/Header-11111111111111111111111111111111",
                    ),
                ),
            )

        assertThat(configuration.rootDocument.externalId).isEqualTo("0123456789abcdef0123456789abcdef")
        assertThat(configuration.headerDocument?.externalId).isEqualTo("11111111111111111111111111111111")
    }

    @Test
    fun `rejects missing root wrong kinds and unsafe head data without exposing source contents`() {
        val secret = "token=server-secret<script>alert(1)</script>"

        listOf(
            emptyList(),
            listOf(row("rootPage", "blocks", page = "0123456789abcdef0123456789abcdef")),
            listOf(row("rootPage", "page", page = "https://notion.site.attacker.example/$secret")),
            listOf(
                row("rootPage", "page", page = "0123456789abcdef0123456789abcdef"),
                row("head", "head", data = """{"siteName":"Blog","script":"$secret"}"""),
            ),
            listOf(
                row("rootPage", "page", page = "0123456789abcdef0123456789abcdef"),
                row("head", "head", data = """{"siteName":"Blog","languageTag":42}"""),
            ),
        ).forEach { rows ->
            assertThatThrownBy { mapper.map(rows) }
                .isInstanceOf(SourceConfigurationException::class.java)
                .hasMessageNotContaining(secret)
        }
    }

    @Test
    fun `retains the parse failure when head data is malformed without exposing it in the message`() {
        val rows =
            listOf(
                row("rootPage", "page", page = "0123456789abcdef0123456789abcdef"),
                row("head", "head", data = "{invalid-json"),
            )

        assertThatThrownBy { mapper.map(rows) }
            .isInstanceOf(SourceConfigurationException::class.java)
            .hasMessage("Notion site configuration is invalid")
            .hasRootCauseInstanceOf(JacksonException::class.java)
    }

    private fun row(
        key: String,
        kind: String,
        page: String = "",
        data: String = "",
    ): NotionSettingsRowResponse =
        NotionSettingsRowResponse(
            id = "row-$key-$kind",
            properties =
                objectMapper.readTree(
                    """
                    {
                      "Key": {"type":"title","title":[{"plain_text":"$key"}]},
                      "Kind": {"type":"select","select":{"name":"$kind"}},
                      "Enabled": {"type":"checkbox","checkbox":true},
                      "Page": {"type":"rich_text","rich_text":[{"plain_text":${json(page)}}]},
                      "Data": {"type":"rich_text","rich_text":[{"plain_text":${json(data)}}]}
                    }
                    """.trimIndent(),
                ),
        )

    private fun disabledRow(
        key: String,
        kind: String,
        page: String = "",
        data: String = "",
    ): NotionSettingsRowResponse {
        val response = row(key, kind, page, data)
        val properties = response.properties as ObjectNode
        properties.putObject("Enabled").put("type", "checkbox").put("checkbox", false)
        return response
    }

    private fun json(value: String): String = objectMapper.writeValueAsString(value)

    private companion object {
        val objectMapper = JsonMapper.builder().build()
    }
}

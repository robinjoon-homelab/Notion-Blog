package xyz.robinjoon.notionblog.adapter.outbound.notion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import xyz.robinjoon.notionblog.adapter.outbound.notion.client.NotionApiClient
import xyz.robinjoon.notionblog.adapter.outbound.notion.dto.NotionSettingsRowResponse
import xyz.robinjoon.notionblog.adapter.outbound.notion.source.NotionSiteConfigurationSource
import xyz.robinjoon.notionblog.domain.source.SourceId

class NotionSiteConfigurationSourceTest {
    @Test
    fun `uses the configured source and data source in one fetch because cursor collection belongs to the client`() {
        val client = mockk<NotionApiClient>()
        every { client.fetchSettingsRows("settings-source") } returns
            listOf(
                row("rootPage", "page", page = "0123456789abcdef0123456789abcdef"),
            )
        val source =
            NotionSiteConfigurationSource(
                sourceId = SourceId("notion-main"),
                settingsDataSourceId = "settings-source",
                client = client,
            )

        val configuration = source.fetch()

        assertThat(configuration.rootDocument.sourceId).isEqualTo(SourceId("notion-main"))
        assertThat(configuration.rootDocument.externalId).isEqualTo("0123456789abcdef0123456789abcdef")
        verify(exactly = 1) { client.fetchSettingsRows("settings-source") }
    }

    private fun row(
        key: String,
        kind: String,
        page: String,
    ): NotionSettingsRowResponse =
        NotionSettingsRowResponse(
            id = "row-$key",
            properties =
                objectMapper.readTree(
                    """
                    {
                      "Key": {"type":"title","title":[{"plain_text":"$key"}]},
                      "Kind": {"type":"select","select":{"name":"$kind"}},
                      "Enabled": {"type":"checkbox","checkbox":true},
                      "Page": {"type":"rich_text","rich_text":[{"plain_text":"$page"}]},
                      "Data": {"type":"rich_text","rich_text":[]}
                    }
                    """.trimIndent(),
                ),
        )

    private companion object {
        val objectMapper = JsonMapper.builder().build()
    }
}

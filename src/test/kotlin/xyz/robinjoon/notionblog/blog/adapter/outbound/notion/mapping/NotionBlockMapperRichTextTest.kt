package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import java.net.URI

class NotionBlockMapperRichTextTest {
    private val fixture = NotionBlockMapperFixture()
    private val mapper = fixture.mapper

    @Test
    fun `maps rich text variants mentions and annotation foreground background separately`() {
        val block =
            fixture.envelope(
                type = "paragraph",
                payload =
                    """
                    {
                      "rich_text": [
                        {"type":"text","text":{"content":"linked","link":{"url":"https://example.com"}},"annotations":{"bold":true,
                          "color":"red_background"},"plain_text":"linked","href":"https://example.com"},
                        {"type":"equation","equation":{"expression":"E=mc^2"},"annotations":{"italic":true,"color":"blue"},
                          "plain_text":"E=mc^2","href":null},
                        {"type":"mention","mention":{"type":"page","page":{"id":"a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d"}},
                          "annotations":{"color":"default"},"plain_text":"Page","href":"https://www.notion.so/page-1"},
                        {"type":"mention","mention":{"type":"database","database":{"id":"database-1"}},
                          "annotations":{"color":"default"},"plain_text":"Database","href":null},
                        {"type":"mention","mention":{"type":"date","date":{"start":"2026-01-01"}},"annotations":{"color":"default"},
                          "plain_text":"Jan 1","href":null},
                        {"type":"mention","mention":{"type":"template_mention","template_mention":{"type":"template_mention_date",
                          "template_mention_date":"today"}},"annotations":{"color":"default"},"plain_text":"Today","href":null},
                        {"type":"mention","mention":{"type":"link_preview","link_preview":{"url":"https://example.com/preview"}},
                          "annotations":{"color":"default"},"plain_text":"Preview","href":"https://example.com/preview"},
                        {"type":"mention","mention":{"type":"user","user":{"id":"user-1"}},"annotations":{"color":"default"},
                          "plain_text":"Ada","href":null}
                      ],
                      "color":"green_background"
                    }
                    """.trimIndent(),
            )

        val mapped = mapper.map(block)
        val content = mapped.content as TextBlockContent.Paragraph

        assertThat(mapped.style.foreground).isNull()
        assertThat(mapped.style.background).isEqualTo(ColorToken.GREEN)
        val linked = content.richText[0] as InlineContent.Text
        assertThat(linked.text).isEqualTo("linked")
        assertThat(linked.annotations.bold).isTrue()
        assertThat(linked.annotations.foreground).isNull()
        assertThat(linked.annotations.background).isEqualTo(ColorToken.RED)
        assertThat(content.richText).hasSize(8)
        assertThat(content.richText[1]).isInstanceOf(InlineContent.Equation::class.java)
        assertThat(content.richText.drop(2).map { (it as InlineContent.Mention).kind.name }).containsExactly(
            "DOCUMENT",
            "DATABASE",
            "DATE",
            "TEMPLATE",
            "LINK_PREVIEW",
            "USER",
        )
    }

    @Test
    fun `preserves safe hrefs for non-page mentions while keeping page mentions as source documents`() {
        val block =
            fixture.envelope(
                type = "paragraph",
                payload =
                    """
                    {
                      "rich_text": [
                        {"type":"mention","mention":{"type":"page","page":{"id":"a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d"}},
                          "annotations":{"color":"default"},"plain_text":"Page","href":"https://www.notion.so/page"},
                        {"type":"mention","mention":{"type":"database","database":{"id":"database-1"}},
                          "annotations":{"color":"default"},"plain_text":"Database","href":"https://www.notion.so/database"},
                        {"type":"mention","mention":{"type":"data_source","data_source":{"id":"data-source-1"}},
                          "annotations":{"color":"default"},"plain_text":"Data source","href":"https://www.notion.so/data-source"},
                        {"type":"mention","mention":{"type":"user","user":{"id":"user-1"}},"annotations":{"color":"default"},
                          "plain_text":"Ada","href":"https://www.notion.so/user"},
                        {"type":"mention","mention":{"type":"agent","agent":{"id":"agent-1"}},"annotations":{"color":"default"},
                          "plain_text":"Agent","href":"https://www.notion.so/agent"},
                        {"type":"mention","mention":{"type":"date","date":{"start":"2026-01-01"}},"annotations":{"color":"default"},
                          "plain_text":"Jan 1","href":"https://www.notion.so/date"},
                        {"type":"mention","mention":{"type":"template_mention","template_mention":{"type":"template_mention_date",
                          "template_mention_date":"today"}},"annotations":{"color":"default"},"plain_text":"Today",
                          "href":"https://www.notion.so/template"},
                        {"type":"mention","mention":{"type":"link_preview","link_preview":{"url":"https://example.com/preview"}},
                          "annotations":{"color":"default"},"plain_text":"Preview","href":"https://www.notion.so/preview"},
                        {"type":"mention","mention":{"type":"future_mention","future_mention":{}},"annotations":{"color":"default"},
                          "plain_text":"Future","href":"https://www.notion.so/future"}
                      ]
                    }
                    """.trimIndent(),
            )

        val mentions =
            (mapper.map(block).content as TextBlockContent.Paragraph)
                .richText
                .map { it as InlineContent.Mention }

        assertThat(mentions.first().target).isEqualTo(
            LinkTarget.SourceDocument(
                reference =
                    xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef(
                        SourceId("notion-main"),
                        "a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d",
                    ),
                originalUrl = URI("https://www.notion.so/page"),
            ),
        )
        assertThat(mentions.drop(1).map { it.target }).containsExactly(
            LinkTarget.ExternalUrl(URI("https://www.notion.so/database")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/data-source")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/user")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/agent")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/date")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/template")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/preview")),
            LinkTarget.ExternalUrl(URI("https://www.notion.so/future")),
        )
    }

    @Test
    fun `rejects an unsafe href on a non-page mention`() {
        val block =
            fixture.envelope(
                type = "paragraph",
                payload =
                    """
                    {"rich_text":[
                      {"type":"mention","mention":{"type":"database","database":{"id":"database-1"}},"annotations":{"color":"default"},
                        "plain_text":"Database","href":"javascript:alert(1)"}
                    ]}
                    """.trimIndent(),
            )

        assertThatThrownBy { mapper.map(block) }
            .isInstanceOf(NotionBlockMappingException::class.java)
            .hasMessage("URL must use http or https")
    }
}

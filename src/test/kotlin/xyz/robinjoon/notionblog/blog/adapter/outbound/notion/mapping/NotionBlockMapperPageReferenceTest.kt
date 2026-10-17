package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.mapping

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.port.output.source.SourceMappingException
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget

class NotionBlockMapperPageReferenceTest {
    private val fixture = NotionBlockMapperFixture()
    private val mapper = fixture.mapper

    @Test
    fun `canonicalizes every page source reference to lowercase thirty two hexadecimal characters`() {
        val childPage =
            mapper
                .map(
                    fixture.envelope(
                        type = "child_page",
                        id = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D",
                        payload = """{"title":"Child"}""",
                    ),
                ).content as xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent.ChildPost
        val pageLink =
            mapper
                .map(
                    fixture.envelope(
                        type = "link_to_page",
                        payload = """{"type":"page_id","page_id":"A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"}""",
                    ),
                ).content as xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent.DocumentLink
        val mention =
            mapper
                .map(
                    fixture.envelope(
                        type = "paragraph",
                        payload =
                            """
                            {"rich_text":[
                              {"type":"mention","mention":{"type":"page","page":{"id":"A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"}},
                                "annotations":{"color":"default"},"plain_text":"Page","href":null}
                            ]}
                            """.trimIndent(),
                    ),
                ).content as TextBlockContent.Paragraph

        assertThat(childPage.reference.externalId).isEqualTo("a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d")
        assertThat(pageLink.reference.externalId).isEqualTo("a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d")
        val target = checkNotNull((mention.richText.single() as InlineContent.Mention).target) as LinkTarget.SourceDocument
        assertThat(target.reference.externalId)
            .isEqualTo("a1b2c3d4e5f64a5b8c9d0e1f2a3b4c5d")
    }

    @Test
    fun `rejects malformed page ids at every page reference mapping boundary`() {
        val malformedChildPage =
            fixture.envelope(
                type = "child_page",
                id = "not-a-page-id",
                payload = """{"title":"Child"}""",
            )
        val malformedPageLink =
            fixture.envelope(
                type = "link_to_page",
                payload = """{"type":"page_id","page_id":"not-a-page-id"}""",
            )
        val malformedMention =
            fixture.envelope(
                type = "paragraph",
                payload =
                    """
                    {"rich_text":[
                      {"type":"mention","mention":{"type":"page","page":{"id":"not-a-page-id"}},"annotations":{"color":"default"},
                        "plain_text":"Page","href":null}
                    ]}
                    """.trimIndent(),
            )

        listOf(malformedChildPage, malformedPageLink, malformedMention).forEach { block ->
            assertThatThrownBy { mapper.map(block) }.isInstanceOf(SourceMappingException::class.java)
        }
    }
}

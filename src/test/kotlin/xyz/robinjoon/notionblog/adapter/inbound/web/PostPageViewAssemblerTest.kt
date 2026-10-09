package xyz.robinjoon.notionblog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ColumnListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataGalleryView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.DataTableView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ExternalLinkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.InternalLinkView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.MediaView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.ParagraphView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TabContainerView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TableView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.TextInlineView
import xyz.robinjoon.notionblog.adapter.inbound.web.view.UnsupportedView
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.LinkResolution
import xyz.robinjoon.notionblog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.domain.post.Post
import xyz.robinjoon.notionblog.domain.post.PostId
import xyz.robinjoon.notionblog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardLayout
import xyz.robinjoon.notionblog.domain.post.block.content.DataCardSize
import xyz.robinjoon.notionblog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.domain.post.block.content.DataCoverAspect
import xyz.robinjoon.notionblog.domain.post.block.content.DataGalleryOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.domain.post.block.content.HeadingLevel
import xyz.robinjoon.notionblog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ListBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.MediaType
import xyz.robinjoon.notionblog.domain.post.block.content.MeetingNotesStatus
import xyz.robinjoon.notionblog.domain.post.block.content.NumberedListFormat
import xyz.robinjoon.notionblog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.ReusableBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SpecialBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.SynchronizedBlockOrigin
import xyz.robinjoon.notionblog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.domain.post.block.content.UnsupportedBlockContent
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.domain.post.block.inline.MentionKind
import xyz.robinjoon.notionblog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.domain.post.block.style.BlockStyle
import xyz.robinjoon.notionblog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.domain.post.block.style.StyleVariant
import xyz.robinjoon.notionblog.domain.post.block.style.WidthToken
import xyz.robinjoon.notionblog.domain.publication.PublicationId
import xyz.robinjoon.notionblog.domain.site.PresentationAssetRef
import xyz.robinjoon.notionblog.domain.site.PresentationProfile
import xyz.robinjoon.notionblog.domain.site.PresentationProfileId
import xyz.robinjoon.notionblog.domain.site.PresentationProfileKey
import xyz.robinjoon.notionblog.domain.site.PresentationProfileRef
import xyz.robinjoon.notionblog.domain.site.PresentationTokens
import xyz.robinjoon.notionblog.domain.site.SiteConfiguration
import xyz.robinjoon.notionblog.domain.site.SiteMetadata
import xyz.robinjoon.notionblog.domain.source.SourceDocumentRef
import xyz.robinjoon.notionblog.domain.source.SourceId
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class PostPageViewAssemblerTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)
    private val assembler = PostPageViewAssembler(clock)

    @ParameterizedTest
    @CsvSource("true, true", "true, false", "false, true", "false, false")
    fun `renders RSS discovery and subscription only with a configured feed regardless of footer document`(
        configuredFeed: Boolean,
        footerDocument: Boolean,
    ) {
        val feedUrl = "https://blog.example:8443/feed.xml".takeIf { configuredFeed }
        val footer =
            Post(
                PostId(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                "Site footer",
                BlockTree(listOf(node("footer-content", TextBlockContent.Paragraph(listOf(InlineContent.Text("Footer text")))))),
            ).takeIf { footerDocument }
        val page = page(emptyList()).copy(footer = footer)
        val view = PostPageViewAssembler(clock, feedUrl).assemble(page)

        val html = templateEngine().process("blog/post", Context().apply { setVariable("page", view) })
        val head = html.substringAfter("<head>").substringBefore("</head>")
        val body = html.substringAfter("<body>").substringBefore("</body>")
        val alternateLinks = Regex("<link\\b[^>]*rel=\"alternate\"[^>]*>").findAll(head).map { it.value }.toList()
        val subscriptionLinks = Regex("<a\\b[^>]*>\\s*RSS 구독\\s*</a>").findAll(body).map { it.value }.toList()

        assertThat(view.feedUrl).isEqualTo(feedUrl)
        if (configuredFeed) {
            assertThat(alternateLinks).hasSize(1)
            assertThat(alternateLinks.single()).contains("type=\"application/rss+xml\"", "href=\"$feedUrl\"")
            assertThat(subscriptionLinks).hasSize(1)
            assertThat(subscriptionLinks.single()).contains("href=\"$feedUrl\"")
            assertThat(body.indexOf(subscriptionLinks.single())).isGreaterThan(body.indexOf("</article>"))
        } else {
            assertThat(alternateLinks).isEmpty()
            assertThat(subscriptionLinks).isEmpty()
            assertThat(html).doesNotContain("application/rss+xml", "RSS 구독")
        }
        assertThat(body.contains("Footer text")).isEqualTo(footerDocument)
    }

    @Test
    fun `renders only resolved internal or safe external links and expires source hosted media`() {
        val document = SourceDocumentRef(SourceId("notion"), "internal")
        val internal = LinkTarget.SourceDocument(document, URI("https://notion.so/internal"))
        val external = LinkTarget.ExternalUrl(URI("https://example.com/read"))
        val unsafe = LinkTarget.ExternalUrl(URI("javascript:alert(1)"))
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "paragraph",
                            TextBlockContent.Paragraph(
                                listOf(
                                    InlineContent.Text(
                                        "<script>alert(1)</script>",
                                        TextAnnotations(bold = true, foreground = ColorToken.RED, background = ColorToken.BLUE),
                                        internal,
                                    ),
                                    InlineContent.Text("external", link = external),
                                    InlineContent.Text("unsafe", link = unsafe),
                                    InlineContent.Equation("x &lt; y"),
                                    InlineContent.Mention("Person", MentionKind.USER, target = external),
                                ),
                            ),
                        ),
                        node(
                            "expired-image",
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.SourceHosted(URI("https://files.notion.so/image"), clock.instant().minusSeconds(1)),
                                "image.png",
                            ),
                        ),
                        node(
                            "unsafe-image",
                            MediaBlockContent.Media(MediaType.IMAGE, MediaSource.External(URI("data:image/png;base64,x")), "image.png"),
                        ),
                    ),
                links = mapOf(internal to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )

        val view = assembler.assemble(page)
        val paragraph = view.post.blocks.first() as ParagraphView
        val text = paragraph.content.filterIsInstance<TextInlineView>()

        assertThat((checkNotNull(text[0].link) as InternalLinkView).href).isEqualTo("/posts/00000000-0000-0000-0000-000000000123")
        assertThat((checkNotNull(text[1].link) as ExternalLinkView).href).isEqualTo("https://example.com/read")
        assertThat(text[2].link).isNull()
        assertThat(text[0].annotations.classes).contains("notion-color-red", "notion-background-blue")
        assertThat((view.post.blocks[1] as MediaView).url).isNull()
        assertThat((view.post.blocks[2] as MediaView).url).isNull()
    }

    @Test
    fun `maps style tokens through fixed view classes instead of user supplied values`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("styled"),
                            TextBlockContent.Paragraph(listOf(InlineContent.Text("safe"))),
                            BlockStyle(foreground = ColorToken.RED, variant = StyleVariant("untrusted variant")),
                        ),
                    ),
            )

        val view = assembler.assemble(page)
        val paragraph = view.post.blocks.single() as ParagraphView

        assertThat(paragraph.style.classes).contains("notion-color-red")
        assertThat(paragraph.style.classes).doesNotContain("untrusted variant")
    }

    @Test
    fun `evaluates nested source hosted media once while assembling layout containers`() {
        val countingClock = CountingClock(clock)
        val source = MediaSource.SourceHosted(URI("https://example.com/nested.png"), clock.instant().plusSeconds(60))
        val image = node("image", MediaBlockContent.Media(MediaType.IMAGE, source, "nested.png"))
        val row =
            BlockNode(
                BlockId("row"),
                LayoutBlockContent.TableRow(listOf(listOf(InlineContent.Text("Cell")))),
                children = listOf(image),
            )
        val table = BlockNode(BlockId("table"), LayoutBlockContent.Table(1), children = listOf(row))
        val tab = BlockNode(BlockId("tab"), LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null), children = listOf(table))
        val tabs = BlockNode(BlockId("tabs"), LayoutBlockContent.TabContainer, children = listOf(tab))
        val column = BlockNode(BlockId("column"), LayoutBlockContent.Column(null), children = listOf(tabs))
        val columns = BlockNode(BlockId("columns"), LayoutBlockContent.ColumnList, children = listOf(column))

        val view = PostPageViewAssembler(countingClock).assemble(page(listOf(columns)))
        val columnView = (view.post.blocks.single() as ColumnListView).columns.single()
        val tabView = (columnView.children.single() as TabContainerView).tabs.single()
        val rowView = (tabView.children.single() as TableView).rows.single()

        assertThat((rowView.children.single() as MediaView).url).isEqualTo(source.url.toASCIIString())
        assertThat(countingClock.reads).isEqualTo(1)
    }

    @Test
    fun `keeps repeated header body and footer document ids and navigation references distinct`() {
        val base =
            page(
                listOf(
                    node("heading", TextBlockContent.Heading(HeadingLevel.TWO, listOf(InlineContent.Text("Section")))),
                    node("toc", ReferenceBlockContent.TableOfContents),
                    node("item", ListBlockContent.BulletedItem(listOf(InlineContent.Text("Item")))),
                    BlockNode(
                        BlockId("tabs"),
                        LayoutBlockContent.TabContainer,
                        children =
                            listOf(
                                BlockNode(
                                    BlockId("tab"),
                                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null),
                                    children = listOf(node("panel-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("Panel"))))),
                                ),
                            ),
                    ),
                ),
            )
        val html = render(base.copy(header = base.post, footer = base.post))
        val idPattern = Regex("""\bid="([^"]+)"""")
        val fragmentPattern = Regex("""href="#([^"]+)"""")
        val ariaReferencePattern = Regex("""aria-(?:controls|labelledby)="([^"]+)"""")
        val ids = idPattern.findAll(html).map { it.groupValues[1] }.toList()

        assertThat(ids).doesNotHaveDuplicates()
        assertThat(html.substringAfter("<article").substringBefore("</article>"))
            .contains("id=\"heading\"", "href=\"#heading\"")
        listOf("header", "article", "footer").forEach { element ->
            val region = html.substringAfter("<$element").substringBefore("</$element>")
            val regionIds = idPattern.findAll(region).map { it.groupValues[1] }.toList()
            val fragments = fragmentPattern.findAll(region).map { it.groupValues[1] }.toList()
            val ariaReferences = ariaReferencePattern.findAll(region).map { it.groupValues[1] }.toList()

            assertThat(fragments).hasSize(2)
            assertThat(ariaReferences).hasSize(2)
            assertThat(regionIds).containsAll(fragments).containsAll(ariaReferences)
        }
        assertThat(
            base.post.content.roots
                .first()
                .id.value,
        ).isEqualTo("heading")
    }

    @Test
    fun `renders semantic list toggle table tabs and escaped content through thymeleaf`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("parent-paragraph"),
                            TextBlockContent.Paragraph(listOf(InlineContent.Text("parent"))),
                            children = listOf(node("paragraph-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("child"))))),
                        ),
                        node("bullet-1", ListBlockContent.BulletedItem(listOf(InlineContent.Text("one")))),
                        node("bullet-2", ListBlockContent.BulletedItem(listOf(InlineContent.Text("two")))),
                        node("todo", ListBlockContent.ToDoItem(listOf(InlineContent.Text("task")), true)),
                        BlockNode(
                            BlockId("toggle"),
                            TextBlockContent.Toggle(listOf(InlineContent.Text("toggle"))),
                            children = listOf(node("toggle-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("nested"))))),
                        ),
                        BlockNode(
                            BlockId("table"),
                            LayoutBlockContent.Table(width = 2, hasColumnHeader = true, hasRowHeader = true),
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("header-row"),
                                        LayoutBlockContent.TableRow(
                                            listOf(listOf(InlineContent.Text("name")), listOf(InlineContent.Text("value"))),
                                        ),
                                    ),
                                    BlockNode(
                                        BlockId("body-row"),
                                        LayoutBlockContent.TableRow(
                                            listOf(listOf(InlineContent.Text("row")), listOf(InlineContent.Text("cell"))),
                                        ),
                                    ),
                                ),
                        ),
                        BlockNode(
                            BlockId("columns"),
                            LayoutBlockContent.ColumnList,
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("column"),
                                        LayoutBlockContent.Column(WidthToken(0.53)),
                                        children =
                                            listOf(
                                                node("column-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("column")))),
                                            ),
                                    ),
                                ),
                        ),
                        BlockNode(
                            BlockId("tabs"),
                            LayoutBlockContent.TabContainer,
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("tab"),
                                        LayoutBlockContent.TabItem(listOf(InlineContent.Text("Tab")), null),
                                        children =
                                            listOf(
                                                node("tab-text", TextBlockContent.Paragraph(listOf(InlineContent.Text("panel")))),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val html = templateEngine().process("blog/post", Context().apply { setVariable("page", assembler.assemble(page)) })

        assertThat(html).contains("<ul", "one", "two", "type=\"checkbox\"", " disabled")
        assertThat(html).contains("<details", "toggle", "<thead", "scope=\"col\"", "scope=\"row\"")
        assertThat(html).contains("notion-columns", "notion-column-width-55", "role=\"tablist\"", "role=\"tabpanel\"")
        assertThat(html).doesNotMatch(Regex("(?s)<p[^>]*>(?:(?!</p>).)*<section class=\"notion-children\"").toPattern())
        assertThat(html).doesNotContain("style=")
        assertThat(html).doesNotContain("th:utext")
    }

    @Test
    fun `renders linked and unlinked inline content exactly once`() {
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "paragraph",
                            TextBlockContent.Paragraph(
                                listOf(
                                    InlineContent.Text(
                                        "unique-linked-inline",
                                        link = LinkTarget.ExternalUrl(URI("https://example.com/read")),
                                    ),
                                    InlineContent.Text("unique-plain-inline"),
                                ),
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).containsOnlyOnce("unique-linked-inline")
        assertThat(html).containsOnlyOnce("unique-plain-inline")
        assertThat(html).contains("href=\"https://example.com/read\"", "target=\"_blank\"", "rel=\"noopener noreferrer\"")
    }

    @Test
    fun `starts a new ordered list only at an explicit numbered list boundary`() {
        val rich = listOf(InlineContent.Text("item"))
        val page =
            page(
                nodes =
                    listOf(
                        node("roman-3", ListBlockContent.NumberedItem(rich, 3, NumberedListFormat.UPPER_ROMAN, true)),
                        node("roman-4", ListBlockContent.NumberedItem(rich)),
                        node("alpha-7", ListBlockContent.NumberedItem(rich, 7, NumberedListFormat.LOWER_ALPHA, true)),
                        node("alpha-8", ListBlockContent.NumberedItem(rich)),
                    ),
            )

        val lists =
            assembler
                .assemble(page)
                .post.blocks
                .filterIsInstance<ListView>()

        assertThat(lists).hasSize(2)
        assertThat(lists[0].startNumber).isEqualTo(3)
        assertThat(lists[0].numberFormat).isEqualTo(xyz.robinjoon.notionblog.adapter.inbound.web.view.NumberedListFormatView.UPPER_ROMAN)
        assertThat(lists[0].items).hasSize(2)
        assertThat(lists[1].startNumber).isEqualTo(7)
        assertThat(lists[1].numberFormat).isEqualTo(xyz.robinjoon.notionblog.adapter.inbound.web.view.NumberedListFormatView.LOWER_ALPHA)
        assertThat(lists[1].items).hasSize(2)

        val html = render(page)
        assertThat(html).contains("<ol", "start=\"3\"", "type=\"I\"", "start=\"7\"", "type=\"a\"")
    }

    @Test
    fun `renders every trusted icon kind and keeps equation source as progressive fallback`() {
        val rich = listOf(InlineContent.Text("content"), InlineContent.Equation("x < y"))
        val page =
            page(
                nodes =
                    listOf(
                        node("emoji", TextBlockContent.Callout(rich, BlockIcon.Emoji("💡"))),
                        node(
                            "media",
                            TextBlockContent.Callout(
                                rich,
                                BlockIcon.Media(MediaSource.External(URI("https://example.com/icon.png"))),
                            ),
                        ),
                        node("native", TextBlockContent.Callout(rich, BlockIcon.Native("academic-cap", ColorToken.BLUE))),
                        BlockNode(
                            BlockId("tabs"),
                            LayoutBlockContent.TabContainer,
                            children =
                                listOf(
                                    BlockNode(
                                        BlockId("custom"),
                                        LayoutBlockContent.TabItem(
                                            listOf(InlineContent.Text("Custom tab")),
                                            BlockIcon.CustomEmoji(
                                                "emoji-id",
                                                "party-parrot",
                                                MediaSource.External(URI("https://example.com/custom.gif")),
                                            ),
                                        ),
                                        children = listOf(node("panel", TextBlockContent.Equation("E = mc^2"))),
                                    ),
                                ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "notion-callout-icon",
            "💡",
            "https://example.com/icon.png",
            "notion-native-icon",
            "academic-cap",
            "notion-color-blue",
            "notion-tab-icon",
            "https://example.com/custom.gif",
            "notion-math-inline",
            "notion-math-block",
            "data-expression=\"E = mc^2\"",
        )
        assertThat(html).contains("x &lt; y", "E = mc^2")
        assertThat(html).doesNotContain("x < y")
    }

    @Test
    fun `derives an accessible breadcrumb and renders database and template titles with children`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val page =
            page(
                nodes =
                    listOf(
                        node("breadcrumb", ReferenceBlockContent.Breadcrumb(emptyList())),
                        node("database", ReferenceBlockContent.DatabaseLink(reference, null, "Engineering wiki")),
                        BlockNode(
                            BlockId("template"),
                            ReusableBlockContent.Template(listOf(InlineContent.Text("Weekly review"))),
                            children = listOf(node("template-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("Prompt"))))),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "aria-label=\"Breadcrumb\"",
            "href=\"/\"",
            ">Home</a>",
            "aria-current=\"page\">Post</span>",
            "Engineering wiki",
            "Weekly review",
            "Prompt",
        )
    }

    @Test
    fun `renders database table captions columns and rich cells with safe resolved links`() {
        val reference = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "public-row"), URI("https://notion.so/public-row"))
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "database-table",
                            dataTable(
                                title = "Roadmap <script>unsafe</script>",
                                columns = listOf("Name <img src=x onerror=alert(1)>", "Status & notes"),
                                rows =
                                    listOf(
                                        DataRow(
                                            listOf(
                                                listOf(InlineContent.Text("Published item", TextAnnotations(bold = true), reference)),
                                                listOf(
                                                    InlineContent.Text("Ready", TextAnnotations(background = ColorToken.GREEN)),
                                                    InlineContent.Text(
                                                        "Documentation",
                                                        link = LinkTarget.ExternalUrl(URI("https://example.com/docs")),
                                                    ),
                                                ),
                                            ),
                                        ),
                                        DataRow(
                                            listOf(
                                                listOf(
                                                    InlineContent.Text(
                                                        "<script>alert(1)</script>",
                                                        link = LinkTarget.ExternalUrl(URI("javascript:alert(1)")),
                                                    ),
                                                ),
                                                emptyList(),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                    ),
                links = mapOf(reference to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )

        val view =
            assembler
                .assemble(page)
                .post.blocks
                .single() as DataTableView
        val html = render(page)
        val table = html.substringAfter("<table class=\"notion-table notion-data-table\"").substringBefore("</table>")

        assertThat(view.title).isEqualTo("Roadmap <script>unsafe</script>")
        assertThat(view.columns.map { it.name }).containsExactly("Name <img src=x onerror=alert(1)>", "Status & notes")
        assertThat(view.rows).hasSize(2)
        assertThat((view.rows[0].cells[0].single() as TextInlineView).annotations.bold).isTrue()
        assertThat((view.rows[1].cells[0].single() as TextInlineView).link).isNull()
        assertThat(
            html,
        ).contains("notion-data-table-wrapper", "role=\"region\"", "tabindex=\"0\"", "aria-labelledby=\"database-table-caption\"")
        assertThat(
            table,
        ).contains("class=\"notion-sr-only\"", "Roadmap &lt;script&gt;unsafe&lt;/script&gt;</caption>", "<thead>", "<tbody>")
        assertThat(Regex("scope=\"col\"").findAll(table).count()).isEqualTo(2)
        assertThat(Regex("<tr>").findAll(table.substringAfter("<tbody>")).count()).isEqualTo(2)
        assertThat(table).contains(
            "Name &lt;img src=x onerror=alert(1)&gt;",
            "Status &amp; notes",
            "href=\"/posts/00000000-0000-0000-0000-000000000123\"",
            "notion-background-green",
            "href=\"https://example.com/docs\"",
            "rel=\"noopener noreferrer\"",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
        )
        assertThat(table).doesNotContain("<script", "<img", "href=\"javascript:", "style=", "data-source", "property-id")
    }

    @Test
    fun `renders an accessible empty database result without inventing rows`() {
        val html = render(page(listOf(node("empty-database", dataTable("Published tasks", listOf("Task", "Owner"), emptyList())))))
        val table = html.substringAfter("<table class=\"notion-table notion-data-table\"").substringBefore("</table>")

        assertThat(table).contains("class=\"notion-sr-only\"", "Published tasks</caption>", ">Task</div>", ">Owner</div>")
        assertThat(table).contains("colspan=\"2\"", "notion-data-table-empty", "No published rows in this view.")
        assertThat(table.substringAfter("<tbody>")).doesNotContain("notion-data-table-cell")
    }

    @Test
    fun `keeps database titles and view tabs around tables without unavailable announcements`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tabs =
            listOf("Active", "Archive").mapIndexed { index, title ->
                BlockNode(
                    BlockId("database-view-$index"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text(title)), null),
                    children = listOf(node("database-table-$index", dataTable(title, listOf("Task"), emptyList()))),
                )
            }
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Team tasks"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val markup = html.substringAfter("id=\"database\"").substringBefore("</aside>")

        assertThat(html).contains("notion-database")
        assertThat(
            markup,
        ).contains("Team tasks", "role=\"tablist\"", "aria-selected=\"true\"", "aria-selected=\"false\"", "Active", "Archive")
        assertThat(Regex("class=\"notion-table notion-data-table\"").findAll(markup).count()).isEqualTo(2)
        assertThat(markup).doesNotContain(" unavailable")
        assertThat(markup.substringBefore("</h2>")).contains("<span>Team tasks</span>").doesNotContain("<a", "href=")
    }

    @Test
    fun `renders a single database view directly with its repeated name available only to assistive technology`() {
        val viewName = "Untitled <script>unsafe</script>"
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tab =
            BlockNode(
                BlockId("database-view"),
                LayoutBlockContent.TabItem(listOf(InlineContent.Text(viewName)), null),
                children =
                    listOf(
                        node(
                            "database-gallery",
                            DataViewContent.Gallery(
                                DataSet(
                                    viewName,
                                    listOf(DataColumn("Name")),
                                    listOf(DataRow(listOf(listOf(InlineContent.Text("Published note"))))),
                                    titleColumnIndex = 0,
                                ),
                            ),
                        ),
                    ),
            )
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Blog posts"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = listOf(tab))),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val markup = html.substringAfter("id=\"database\"").substringBefore("</aside>")

        assertThat(markup).contains(
            "<h2 class=\"notion-database-title\"><span>Blog posts</span></h2>",
            "notion-data-gallery",
            "Published note",
        )
        assertThat(markup).doesNotContain("role=\"tablist\"", "role=\"tab\"", "role=\"tabpanel\"", "class=\"notion-tabs\"")
        assertThat(markup).contains(
            "<span class=\"notion-data-view-title notion-sr-only\"",
            "Untitled &lt;script&gt;unsafe&lt;/script&gt;",
        )
        assertThat(markup).doesNotContain("<h3 class=\"notion-data-view-title", "<script>unsafe</script>")
        assertThat(markup.substringBefore("</h2>")).doesNotContain("<a", "href=")
    }

    @Test
    fun `keeps the safe external database heading link when no inline body is available`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val database = node("database-fallback", ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Original database"))
        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

        assertThat(
            heading,
        ).contains("href=\"https://notion.so/database\"", "target=\"_blank\"", "rel=\"noopener noreferrer\"", ">Original database</a>")
        assertThat(heading).doesNotContain("<span>")
    }

    @Test
    fun `keeps the original database link when every view contains only an unavailable placeholder`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val resolutions = mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl))

        listOf(1, 2).forEach { viewCount ->
            val tabs =
                (1..viewCount).map { index ->
                    BlockNode(
                        BlockId("view-$index"),
                        LayoutBlockContent.TabItem(listOf(InlineContent.Text("Unavailable view $index")), null),
                        children = listOf(node("view-$index-unavailable", UnsupportedBlockContent("database_view"))),
                    )
                }
            val database =
                BlockNode(
                    BlockId("database-fallback"),
                    ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Original database"),
                    children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
                )

            val html = render(page(listOf(database), resolutions))
            val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

            assertThat(heading)
                .describedAs("%s unavailable views", viewCount)
                .contains(
                    "href=\"https://notion.so/database\"",
                    "target=\"_blank\"",
                    "rel=\"noopener noreferrer\"",
                    ">Original database</a>",
                )
            assertThat(html).contains("Unsupported block: database_view")
        }
    }

    @Test
    fun `keeps the database heading plain when an available view accompanies a placeholder`() {
        val reference = SourceDocumentRef(SourceId("notion"), "database")
        val originalUrl = URI("https://notion.so/database")
        val tabs =
            listOf(
                BlockNode(
                    BlockId("unavailable-view"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Unavailable")), null),
                    children = listOf(node("unavailable-content", UnsupportedBlockContent("database_view"))),
                ),
                BlockNode(
                    BlockId("available-view"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text("Available")), null),
                    children = listOf(node("available-table", dataTable("Available", listOf("Title"), emptyList()))),
                ),
            )
        val database =
            BlockNode(
                BlockId("database"),
                ReferenceBlockContent.DatabaseLink(reference, originalUrl, "Blog posts"),
                children = listOf(BlockNode(BlockId("database-views"), LayoutBlockContent.TabContainer, children = tabs)),
            )

        val html =
            render(
                page(
                    listOf(database),
                    mapOf(LinkTarget.SourceDocument(reference, originalUrl) to LinkResolution.External(originalUrl)),
                ),
            )
        val heading = html.substringAfter("class=\"notion-database-title\"").substringBefore("</h2>")

        assertThat(heading).contains("<span>Blog posts</span>").doesNotContain("<a", "href=")
        assertThat(html).contains("Unsupported block: database_view", "No published rows in this view.")
    }

    @Test
    fun `renders member row links internally and outside rows externally in every database layout`() {
        val internal = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "member-row"), URI("https://notion.so/member-row"))
        val external = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "outside-row"), URI("https://notion.so/outside-row"))
        val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000123"))
        val data =
            DataSet(
                "Blog posts",
                listOf(DataColumn("Title")),
                listOf(
                    DataRow(listOf(listOf(InlineContent.Text("Member article", link = internal))), link = internal),
                    DataRow(listOf(listOf(InlineContent.Text("Outside article", link = external))), link = external),
                ),
                titleColumnIndex = 0,
            )
        val resolutions =
            mapOf(
                internal to LinkResolution.Internal(postId),
                external to LinkResolution.External(checkNotNull(external.originalUrl)),
            )

        listOf(DataViewContent.Table(data), DataViewContent.ListView(data), DataViewContent.Gallery(data)).forEach { layout ->
            val html = render(page(listOf(node("view", layout)), resolutions))
            val internalAnchor = Regex("<a\\b[^>]*href=\"/posts/${postId.value}\"[^>]*>").find(html)?.value
            val externalAnchor = Regex("<a\\b[^>]*href=\"https://notion.so/outside-row\"[^>]*>").find(html)?.value

            assertThat(internalAnchor).describedAs(layout.javaClass.simpleName).isNotNull().doesNotContain("target=", "rel=")
            assertThat(
                externalAnchor,
            ).describedAs(layout.javaClass.simpleName).isNotNull().contains("target=\"_blank\"", "rel=\"noopener noreferrer\"")
            assertThat(html).contains("Member article", "Outside article").doesNotContain("href=\"https://notion.so/member-row\"")
            assertThat(Regex("href=\"/posts/${postId.value}\"").findAll(html).count()).isEqualTo(1)
            assertThat(Regex("href=\"https://notion.so/outside-row\"").findAll(html).count()).isEqualTo(1)
            assertThat(Regex("<a\\b[^>]*>(?:(?!</a>).)*<a\\b", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html)).isFalse()
        }
    }

    @Test
    fun `renders table width wrapping vertical line and frozen column options without source CSS`() {
        val data =
            DataSet(
                "Readable table",
                listOf(DataColumn("Title", 201, false), DataColumn("Notes", 900, true), DataColumn("Default", 0), DataColumn("Small", 1)),
                listOf(
                    DataRow(
                        List(4) {
                            listOf(InlineContent.Text("A long value"))
                        },
                        link = LinkTarget.ExternalUrl(URI("https://example.com/row")),
                        icon = BlockIcon.Emoji("📚"),
                    ),
                ),
                titleColumnIndex = 0,
            )
        val table = DataViewContent.Table(data, DataTableOptions(wrapCells = false, frozenColumns = 2, showVerticalLines = false))
        val view =
            assembler
                .assemble(page(listOf(node("configured-table", table))))
                .post.blocks
                .single() as DataTableView
        val html = render(page(listOf(node("configured-table", table))))

        assertThat(
            view.columns.map {
                it.widthClass.cssClass
            },
        ).containsExactly("notion-data-width-200", "notion-data-width-640", "notion-data-width-200", "notion-data-width-80")
        assertThat(view.columns.map { it.wrap }).containsExactly(false, true, false, false)
        assertThat(view.columns.map { it.frozen }).containsExactly(true, true, false, false)
        assertThat(
            html,
        ).contains(
            "data-frozen-columns=\"2\"",
            "notion-data-table-no-vertical-lines",
            "notion-data-nowrap",
            "notion-data-wrap",
            "notion-data-width-640",
            "aria-label=\"Title\"",
        )
        assertThat(Regex("href=\"https://example.com/row\"").findAll(html).count()).isEqualTo(1)
        assertThat(Regex("📚").findAll(html).count()).isEqualTo(1)
        assertThat(html).doesNotContain("style=", "width=\"900", "<script>")
    }

    @Test
    fun `renders database lists as named entries with properties and safe non nested title links`() {
        val rowLink = LinkTarget.SourceDocument(SourceDocumentRef(SourceId("notion"), "row"), URI("https://example.com/row"))
        val data =
            DataSet(
                "Reading list <script>",
                listOf(DataColumn("Status"), DataColumn("Name"), DataColumn("Reference")),
                listOf(
                    DataRow(
                        listOf(
                            listOf(InlineContent.Text("Published")),
                            listOf(
                                InlineContent.Text(
                                    "Article <img>",
                                    TextAnnotations(bold = true),
                                    LinkTarget.ExternalUrl(URI("https://example.com/old-title-link")),
                                ),
                            ),
                            listOf(InlineContent.Text("Documentation", link = LinkTarget.ExternalUrl(URI("https://example.com/docs")))),
                        ),
                        link = rowLink,
                        icon = BlockIcon.Emoji("📚"),
                    ),
                ),
                titleColumnIndex = 1,
            )
        val input =
            page(
                listOf(node("reading-list", DataViewContent.ListView(data))),
                mapOf(rowLink to LinkResolution.Internal(PostId(UUID.fromString("00000000-0000-0000-0000-000000000123")))),
            )
        val view =
            assembler
                .assemble(input)
                .post.blocks
                .single() as DataListView
        val html = render(input)

        assertThat(
            view.rows
                .single()
                .properties
                .map { it.name },
        ).containsExactly("Status", "Reference")
        assertThat(
            html,
        ).contains("notion-data-list", "<ul", "<li", "<dl", "<dt>Status</dt>", "Article &lt;img&gt;", "Reading list &lt;script&gt;", "📚")
        assertThat(html).contains("href=\"/posts/00000000-0000-0000-0000-000000000123\"", "href=\"https://example.com/docs\"")
        assertThat(html).doesNotContain("notion-data-table", "<table", "<dt>Name</dt>", "old-title-link", "<script", "<img>")
        assertThat(Regex("<a\\b[^>]*>(?:(?!</a>).)*<a\\b", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html)).isFalse()
    }

    @Test
    fun `renders gallery card options and only safe unexpired covers`() {
        val data =
            DataSet(
                "Gallery",
                listOf(DataColumn("Name"), DataColumn("Description")),
                listOf(
                    DataRow(
                        listOf(listOf(InlineContent.Text("Visible cover")), listOf(InlineContent.Text("A description"))),
                        cover = MediaSource.External(URI("https://example.com/cover.png")),
                    ),
                    DataRow(
                        listOf(listOf(InlineContent.Text("Expired cover")), emptyList()),
                        cover = MediaSource.SourceHosted(URI("https://example.com/expired.png"), clock.instant()),
                    ),
                    DataRow(
                        listOf(listOf(InlineContent.Text("Unsafe cover")), emptyList()),
                        cover = MediaSource.External(URI("javascript:alert(1)")),
                    ),
                    DataRow(listOf(emptyList(), emptyList()), link = LinkTarget.ExternalUrl(URI("javascript:alert(1)"))),
                ),
                titleColumnIndex = 0,
            )
        val gallery = DataViewContent.Gallery(data, DataGalleryOptions(DataCardSize.LARGE, DataCoverAspect.CONTAIN, DataCardLayout.COMPACT))
        val input = page(listOf(node("gallery", gallery)))
        val view =
            assembler
                .assemble(input)
                .post.blocks
                .single() as DataGalleryView
        val html = render(input)

        assertThat(view.rows.map { it.coverUrl }).containsExactly("https://example.com/cover.png", null, null, null)
        assertThat(
            html,
        ).contains(
            "notion-data-gallery",
            "notion-data-gallery-large",
            "notion-data-gallery-contain",
            "notion-data-gallery-compact",
            "notion-data-card",
        )
        assertThat(
            html,
        ).contains("src=\"https://example.com/cover.png\"", "alt=\"Visible cover\"", "loading=\"lazy\"", "Untitled", "Description")
        assertThat(html).doesNotContain("expired.png", "javascript:", "<table", "<dt>Name</dt>", "style=")
        assertThat(Regex("<img\\b").findAll(html).count()).isEqualTo(1)
    }

    @Test
    fun `uses neutral row titles when the visible data has no title column and shows empty list and gallery states`() {
        val data =
            DataSet(
                "Untitled rows",
                listOf(DataColumn("Status")),
                listOf(DataRow(listOf(listOf(InlineContent.Text("Ready"))), link = LinkTarget.ExternalUrl(URI("https://example.com/row")))),
            )
        val empty = DataSet("Empty", listOf(DataColumn("Status")), emptyList())
        val input =
            page(
                listOf(
                    node("no-title", DataViewContent.ListView(data)),
                    node("empty-list", DataViewContent.ListView(empty)),
                    node("empty-gallery", DataViewContent.Gallery(empty)),
                ),
            )
        val list =
            assembler
                .assemble(input)
                .post.blocks
                .first() as DataListView
        val html = render(input)

        assertThat(list.rows.single().titleText).isEqualTo("Untitled")
        assertThat(
            list.rows
                .single()
                .properties
                .single()
                .name,
        ).isEqualTo("Status")
        assertThat(html).contains("Untitled", "href=\"https://example.com/row\"", "<dt>Status</dt>")
        assertThat(Regex("No published rows in this view\\.").findAll(html).count()).isEqualTo(2)
    }

    @Test
    fun `renders three multilingual database layouts with versioned assets for browser verification`() {
        val data = previewData()
        val layouts =
            listOf(
                "표 · Table" to DataViewContent.Table(data, DataTableOptions(frozenColumns = 1)),
                "리스트 · List" to DataViewContent.ListView(data),
                "갤러리 · Gallery" to DataViewContent.Gallery(data),
            )
        val tabs =
            layouts.mapIndexed { index, (title, layout) ->
                BlockNode(
                    BlockId("preview-view-$index"),
                    LayoutBlockContent.TabItem(listOf(InlineContent.Text(title)), null),
                    children = listOf(node("preview-layout-$index", layout)),
                )
            }
        val reference = SourceDocumentRef(SourceId("notion"), "preview-database")
        val base =
            page(
                listOf(
                    node(
                        "preview-introduction",
                        TextBlockContent.Paragraph(listOf(InlineContent.Text("동일한 공개 데이터를 표, 리스트, 갤러리로 표시합니다. 탭을 선택해 각 레이아웃을 확인하세요."))),
                    ),
                    BlockNode(
                        BlockId("preview-database"),
                        ReferenceBlockContent.DatabaseLink(reference, URI("https://example.com/database"), "제품 개발 · Product roadmap"),
                        children = listOf(BlockNode(BlockId("preview-views"), LayoutBlockContent.TabContainer, children = tabs)),
                    ),
                    node("preview-empty", dataTable("공개 항목 없음 · No published items", listOf("이름", "상태"), emptyList())),
                    node("preview-empty-list", DataViewContent.ListView(DataSet("빈 리스트", listOf(DataColumn("이름")), emptyList()))),
                    node("preview-empty-gallery", DataViewContent.Gallery(DataSet("빈 갤러리", listOf(DataColumn("이름")), emptyList()))),
                ),
            )
        val styles =
            listOf(
                "notion" to "/presentation/notion/v1/notion.css",
                "notion-enhancements" to "/presentation/notion/enhancements/v1/notion-enhancements.css",
                "notion-database" to "/presentation/notion/database/v3/notion-database.css",
            )
        val assets =
            styles.associate { (key, path) ->
                val assetReference = PresentationAssetRef(key, if (key == "notion-database") 3 else 1, resourceIntegrity(path))
                assetReference to PresentationAssetDescriptor(path, "text/css", assetReference.integrity)
            }
        val scripts =
            listOf(
                "notion-tabs" to "/presentation/notion/v1/notion.js",
                "notion-database-behavior" to "/presentation/notion/database/v2/notion-database.js",
            ).associate { (key, path) ->
                val reference = PresentationAssetRef(key, 1, resourceIntegrity(path))
                reference to PresentationAssetDescriptor(path, "text/javascript", reference.integrity)
            }
        val preview =
            base.copy(
                presentation = base.presentation.copy(styleSheets = assets.keys.toList(), scripts = scripts.keys.toList()),
                presentationAssets = assets + scripts,
            )

        val html = render(preview)

        assertThat(
            html,
        ).contains(
            "제품 개발 · Product roadmap",
            "공개 항목 없음 · No published items",
            "very-long-unbroken-value",
            "notion-database.css",
            "notion.js",
        )
        val previewPath = Path.of("build", "qa", "inline-database-views.html")
        Files.createDirectories(previewPath.parent)
        Files.writeString(previewPath, html)
        checkNotNull(javaClass.classLoader.getResourceAsStream("qa/preview-cover.svg")).use { source ->
            Files.copy(source, previewPath.parent.resolve("preview-cover.svg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Test
    fun `canonicalizes supported embeds and degrades unsupported providers to a safe link`() {
        val caption = listOf(InlineContent.Text("Watch externally"))
        val page =
            page(
                nodes =
                    listOf(
                        node("youtube", MediaBlockContent.Embed(URI("http://www.youtube.com/watch?v=abc_123"), emptyList())),
                        node("vimeo", MediaBlockContent.Embed(URI("https://vimeo.com/987654321?autoplay=1"), emptyList())),
                        node("unknown", MediaBlockContent.Embed(URI("https://widgets.example.com/card?id=42"), caption)),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(
            "src=\"https://www.youtube-nocookie.com/embed/abc_123\"",
            "src=\"https://player.vimeo.com/video/987654321\"",
            "href=\"https://widgets.example.com/card?id=42\"",
            ">Open embedded content</a>",
            "Watch externally",
            "sandbox=\"allow-scripts allow-same-origin allow-popups allow-presentation\"",
            "allowfullscreen",
            "referrerpolicy=\"strict-origin-when-cross-origin\"",
        )
        assertThat(Regex("<iframe\\b").findAll(html).count()).isEqualTo(2)
        assertThat(html).doesNotContain("src=\"http://")
    }

    @Test
    fun `uses figure captions only for figure content`() {
        val caption = listOf(InlineContent.Text("Description"))
        val page =
            page(
                nodes =
                    listOf(
                        node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/read"), caption)),
                        node(
                            "image",
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.External(URI("https://example.com/image.png")),
                                "image.png",
                                caption,
                            ),
                        ),
                    ),
            )

        val html = render(page)
        val bookmarkMarkup = html.substringAfter("id=\"bookmark\"").substringBefore("</aside>")
        val imageMarkup = html.substringAfter("id=\"image\"").substringBefore("</figure>")

        assertThat(bookmarkMarkup).contains("class=\"notion-caption\"").doesNotContain("<figcaption")
        assertThat(imageMarkup).contains("<figcaption", "Description")
    }

    @Test
    fun `keeps figure captions as the last child when figure blocks have nested content`() {
        val caption = listOf(InlineContent.Text("Description"))
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("code"),
                            TextBlockContent.Code(emptyList(), "kotlin", caption),
                            children = listOf(node("code-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                        BlockNode(
                            BlockId("image"),
                            MediaBlockContent.Media(
                                MediaType.IMAGE,
                                MediaSource.External(URI("https://example.com/image.png")),
                                "image.png",
                                caption,
                            ),
                            children = listOf(node("image-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                        BlockNode(
                            BlockId("embed"),
                            MediaBlockContent.Embed(URI("https://vimeo.com/123"), caption),
                            children = listOf(node("embed-nested", TextBlockContent.Paragraph(listOf(InlineContent.Text("Nested"))))),
                        ),
                    ),
            )

        val html = render(page)

        listOf("code", "image", "embed").forEach { id ->
            val figure = html.substringAfter("id=\"$id\"").substringBefore("</figure>")
            assertThat(figure.substringAfter("<figcaption")).doesNotContain("notion-children")
        }
    }

    @Test
    fun `renders code content literally without template whitespace`() {
        val source = "if (left < right && ready) {\n    return value\n}"
        val page =
            page(
                nodes =
                    listOf(
                        node("code", TextBlockContent.Code(listOf(InlineContent.Text(source)), "kotlin", emptyList())),
                    ),
            )

        val html = render(page)
        val codeMarkup = html.substringAfter("<pre><code>").substringBefore("</code></pre>")

        assertThat(codeMarkup).isEqualTo("if (left &lt; right &amp;&amp; ready) {\n    return value\n}")
        assertThat(codeMarkup).doesNotContain("<span")
    }

    @Test
    fun `renders synchronized children without an extra placeholder and keeps empty fallbacks visible`() {
        val page =
            page(
                nodes =
                    listOf(
                        BlockNode(
                            BlockId("synced-content"),
                            ReusableBlockContent.Synchronized(null),
                            children = listOf(node("synced-child", TextBlockContent.Paragraph(listOf(InlineContent.Text("Synced body"))))),
                        ),
                        node("synced-empty", ReusableBlockContent.Synchronized(null)),
                        node(
                            "synced-reference-empty",
                            ReusableBlockContent.Synchronized(
                                SynchronizedBlockOrigin(
                                    SourceDocumentRef(SourceId("notion"), "origin-document"),
                                    "origin-block",
                                ),
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains("Synced body")
        assertThat(Regex("Synchronized content").findAll(html).count()).isEqualTo(2)
    }

    @Test
    fun `renders a human readable meeting notes status`() {
        val page =
            page(
                nodes =
                    listOf(
                        node(
                            "meeting",
                            SpecialBlockContent.MeetingNotes(
                                "Rendering review",
                                MeetingNotesStatus.NOT_STARTED,
                                emptyList(),
                                null,
                            ),
                        ),
                    ),
            )

        val html = render(page)

        assertThat(html).contains(">Not started</p>")
        assertThat(html).doesNotContain(">NOT_STARTED</p>")
    }

    @Test
    fun `maps every normalized block subtype without dropping nested fallback children`() {
        val reference = SourceDocumentRef(SourceId("notion"), "reference")
        val rich = listOf(InlineContent.Text("text"))
        val page = page(textAndLayoutBlocks(rich) + mediaAndReferenceBlocks(rich, reference))

        val blocks = assembler.assemble(page).post.blocks
        val kinds = blocks.map { it.kind.name }
        val listItems = blocks.filterIsInstance<ListView>().flatMap { it.items }.map { it::class.simpleName }

        assertThat(kinds).contains(
            "PARAGRAPH",
            "HEADING",
            "QUOTE",
            "TOGGLE",
            "CALLOUT",
            "CODE",
            "EQUATION",
            "LIST",
            "DIVIDER",
            "COLUMN_LIST",
            "TAB_CONTAINER",
            "TABLE",
            "DATA_TABLE",
            "DATA_LIST",
            "DATA_GALLERY",
            "MEDIA",
            "BOOKMARK",
            "LINK_PREVIEW",
            "EMBED",
            "CHILD_POST",
            "DOCUMENT_LINK",
            "DATABASE_LINK",
            "BREADCRUMB",
            "TABLE_OF_CONTENTS",
            "SYNCHRONIZED",
            "TEMPLATE",
            "MEETING_NOTES",
            "UNSUPPORTED",
        )
        assertThat(listItems).contains("BulletedListItemView", "NumberedListItemView", "TodoListItemView")
        assertThat((blocks.last() as UnsupportedView).originalType).isEqualTo("future_block")
        assertThat((blocks.last() as UnsupportedView).children).hasSize(1)
    }

    private fun previewData(): DataSet {
        val rows =
            (1..3).map { index ->
                DataRow(
                    listOf(
                        listOf(InlineContent.Text("인라인 데이터베이스 구현 $index", TextAnnotations(bold = true))),
                        listOf(InlineContent.Text(if (index % 2 == 0) "진행 중" else "완료", TextAnnotations(background = ColorToken.GREEN))),
                        listOf(InlineContent.Text("Robin / 로빈")),
                        listOf(InlineContent.Text("2026-09-01")),
                        listOf(InlineContent.Text("Read documentation", link = LinkTarget.ExternalUrl(URI("https://example.com/docs")))),
                        listOf(
                            InlineContent.Text(
                                "길고 다양한 언어의 셀도 읽을 수 있어야 합니다. 日本語の説明と English notes. " + "very-long-unbroken-value".repeat(3),
                            ),
                        ),
                    ),
                    link = LinkTarget.ExternalUrl(URI("https://example.com/tasks/$index")),
                    icon = BlockIcon.Emoji(if (index == 1) "📚" else "📝"),
                    cover = if (index == 2) null else MediaSource.External(URI("http://localhost:8081/preview-cover.svg")),
                )
            }
        val columns =
            listOf(
                DataColumn("이름 / Name", 240),
                DataColumn("상태 / Status", 120, false),
                DataColumn("담당 / Owner", 160),
                DataColumn("마감 / Due", 160),
                DataColumn("문서 링크", 200),
                DataColumn("설명 / Notes", 320),
            )
        return DataSet("공개 문서 · Published notes", columns, rows, titleColumnIndex = 0)
    }

    private fun textAndLayoutBlocks(rich: List<InlineContent>): List<BlockNode> =
        listOf(
            node("paragraph", TextBlockContent.Paragraph(rich)),
            node("heading-one", TextBlockContent.Heading(HeadingLevel.ONE, rich)),
            node("heading-two", TextBlockContent.Heading(HeadingLevel.TWO, rich)),
            node("heading-three", TextBlockContent.Heading(HeadingLevel.THREE, rich)),
            node("heading-four", TextBlockContent.Heading(HeadingLevel.FOUR, rich)),
            node("quote", TextBlockContent.Quote(rich)),
            node("toggle", TextBlockContent.Toggle(rich)),
            node("callout", TextBlockContent.Callout(rich, BlockIcon.Emoji("💡"))),
            node("code", TextBlockContent.Code(rich, "kotlin", rich)),
            node("equation", TextBlockContent.Equation("x = y")),
            node("bullet", ListBlockContent.BulletedItem(rich)),
            node("numbered", ListBlockContent.NumberedItem(rich, 3, NumberedListFormat.UPPER_ROMAN)),
            node("todo", ListBlockContent.ToDoItem(rich, true)),
            node("divider", LayoutBlockContent.Divider),
            BlockNode(
                BlockId("columns"),
                LayoutBlockContent.ColumnList,
                children =
                    listOf(
                        BlockNode(
                            BlockId("column"),
                            LayoutBlockContent.Column(WidthToken(0.5)),
                            children = listOf(node("column-child", TextBlockContent.Paragraph(rich))),
                        ),
                    ),
            ),
            BlockNode(
                BlockId("tabs"),
                LayoutBlockContent.TabContainer,
                children =
                    listOf(
                        BlockNode(
                            BlockId("tab"),
                            LayoutBlockContent.TabItem(rich, null),
                            children = listOf(node("tab-child", TextBlockContent.Paragraph(rich))),
                        ),
                    ),
            ),
            BlockNode(
                BlockId("table"),
                LayoutBlockContent.Table(1, true, true),
                children = listOf(BlockNode(BlockId("row"), LayoutBlockContent.TableRow(listOf(rich)))),
            ),
            node("data-table", dataTable("Database view", listOf("Name"), listOf(DataRow(listOf(rich))))),
            node(
                "data-list",
                DataViewContent.ListView(DataSet("Database list", listOf(DataColumn("Name")), listOf(DataRow(listOf(rich))))),
            ),
            node(
                "data-gallery",
                DataViewContent.Gallery(DataSet("Database gallery", listOf(DataColumn("Name")), listOf(DataRow(listOf(rich))))),
            ),
        )

    private fun mediaAndReferenceBlocks(
        rich: List<InlineContent>,
        reference: SourceDocumentRef,
    ): List<BlockNode> =
        listOf(
            node("image", MediaBlockContent.Media(MediaType.IMAGE, MediaSource.External(URI("https://example.com/image")), "image")),
            node("video", MediaBlockContent.Media(MediaType.VIDEO, MediaSource.External(URI("https://example.com/video")), "video")),
            node("audio", MediaBlockContent.Media(MediaType.AUDIO, MediaSource.External(URI("https://example.com/audio")), "audio")),
            node("file", MediaBlockContent.Media(MediaType.FILE, MediaSource.External(URI("https://example.com/file")), "file")),
            node("pdf", MediaBlockContent.Media(MediaType.PDF, MediaSource.External(URI("https://example.com/pdf")), "pdf")),
            node("bookmark", MediaBlockContent.Bookmark(URI("https://example.com/bookmark"), rich)),
            node("preview", MediaBlockContent.LinkPreview(URI("https://example.com/preview"))),
            node("embed", MediaBlockContent.Embed(URI("https://www.youtube.com/embed/id"), rich)),
            node("child", ReferenceBlockContent.ChildPost("Child", reference)),
            node("document", ReferenceBlockContent.DocumentLink(reference, URI("https://example.com/document"))),
            node("database", ReferenceBlockContent.DatabaseLink(reference, URI("https://example.com/database"), "Database")),
            node(
                "breadcrumb",
                ReferenceBlockContent.Breadcrumb(listOf(LinkTarget.SourceDocument(reference, URI("https://example.com/breadcrumb")))),
            ),
            node("toc", ReferenceBlockContent.TableOfContents),
            node("synced", ReusableBlockContent.Synchronized(SynchronizedBlockOrigin(reference, "origin"))),
            node("template", ReusableBlockContent.Template(rich)),
            node("meeting", SpecialBlockContent.MeetingNotes("Meeting", MeetingNotesStatus.COMPLETED, rich, null)),
            BlockNode(
                BlockId("unsupported"),
                UnsupportedBlockContent("future_block"),
                children = listOf(node("fallback-child", TextBlockContent.Paragraph(rich))),
            ),
        )

    private fun page(
        nodes: List<BlockNode>,
        links: Map<LinkTarget.SourceDocument, LinkResolution> = emptyMap(),
    ): BlogPage {
        val style = PresentationAssetRef("style", 1, "sha256-style")
        val profile =
            PresentationProfile(
                PresentationProfileId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                PresentationProfileKey("default"),
                1,
                PresentationTokens(),
                listOf(style),
                emptyList(),
            )
        val root = SourceDocumentRef(SourceId("notion"), "root")
        return BlogPage(
            SiteConfiguration(
                PublicationId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                root,
                null,
                null,
                SiteMetadata("Blog", "Description", "en", null),
                PresentationProfileRef(profile.id, profile.version),
            ),
            profile,
            mapOf(style to PresentationAssetDescriptor("/presentation/notion.css", "text/css", style.integrity)),
            Post(PostId(UUID.fromString("00000000-0000-0000-0000-000000000003")), "Post", BlockTree(nodes)),
            null,
            null,
            links,
        )
    }

    private fun dataTable(
        title: String,
        columns: List<String>,
        rows: List<DataRow>,
    ): DataViewContent.Table =
        DataViewContent.Table(
            DataSet(
                title,
                columns.map {
                    DataColumn(it)
                },
                rows,
            ),
        )

    private fun node(
        id: String,
        content: BlockContent,
    ): BlockNode = BlockNode(BlockId(id), content)

    private fun render(page: BlogPage): String =
        templateEngine().process(
            "blog/post",
            Context().apply { setVariable("page", assembler.assemble(page)) },
        )

    private fun resourceIntegrity(publicPath: String): String {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("static$publicPath")).use { it.readBytes() }
        return "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun templateEngine(): SpringTemplateEngine {
        val resolver =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
                isCacheable = false
            }
        return SpringTemplateEngine().apply { setTemplateResolver(resolver) }
    }

    private class CountingClock(
        private val delegate: Clock,
    ) : Clock() {
        var reads = 0
            private set

        override fun getZone(): ZoneId = delegate.zone

        override fun withZone(zone: ZoneId): Clock = CountingClock(delegate.withZone(zone))

        override fun instant(): Instant {
            reads += 1
            return delegate.instant()
        }
    }
}

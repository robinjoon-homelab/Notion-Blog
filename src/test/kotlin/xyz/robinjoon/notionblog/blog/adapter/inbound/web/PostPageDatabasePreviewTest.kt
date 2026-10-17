package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.dataTable
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.node
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.page
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewFixtures.render
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ListView
import xyz.robinjoon.notionblog.blog.application.model.PresentationAssetDescriptor
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockIcon
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataTableOptions
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.LayoutBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.ReferenceBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.TextBlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.InlineContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.TextAnnotations
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.style.ColorToken
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssetRef
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationAssets
import xyz.robinjoon.notionblog.blog.domain.presentation.PresentationProfile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class PostPageDatabasePreviewTest {
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
                chrome =
                    base.chrome.copy(
                        presentation = base.chrome.presentation.withAssets(PresentationAssets(assets.keys.toList(), scripts.keys.toList())),
                        presentationAssets = assets + scripts,
                    ),
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

    // Restores the same stored profile with other trusted assets; the root keeps its copy private.
    private fun PresentationProfile.withAssets(assets: PresentationAssets): PresentationProfile =
        PresentationProfile.restore(reference, key, tokens, assets)

    private fun resourceIntegrity(publicPath: String): String {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("static$publicPath")).use { it.readBytes() }
        return "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

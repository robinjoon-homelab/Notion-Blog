package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.LinkResolution
import xyz.robinjoon.notionblog.blog.application.model.PublicPostPage
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockId
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockNode
import xyz.robinjoon.notionblog.blog.domain.post.block.content.BlockContent
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataColumn
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataRow
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataSet
import xyz.robinjoon.notionblog.blog.domain.post.block.content.DataViewContent
import xyz.robinjoon.notionblog.blog.domain.post.block.inline.LinkTarget
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// 게시글 화면 조립 테스트가 함께 쓰는 고정 시계, 조립기, 템플릿 렌더링과 블록 조립 도우미.
internal object PostPageViewFixtures {
    val clock: Clock = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)
    val assembler = PostPageViewAssembler(clock)

    fun page(
        nodes: List<BlockNode>,
        links: Map<LinkTarget.SourceDocument, LinkResolution> = emptyMap(),
    ): PublicPostPage = WebFixtures.publicPage(WebFixtures.revision(nodes = nodes), ContentReferences(links, RevisionMedia.empty))

    fun dataTable(
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

    fun node(
        id: String,
        content: BlockContent,
    ): BlockNode = BlockNode(BlockId(id), content)

    fun render(page: PublicPostPage): String =
        templateEngine().process(
            "blog/post",
            Context().apply { setVariable("page", assembler.assemble(page)) },
        )

    fun templateEngine(): SpringTemplateEngine {
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
}

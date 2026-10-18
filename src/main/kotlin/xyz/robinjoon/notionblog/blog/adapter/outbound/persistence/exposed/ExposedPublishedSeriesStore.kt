package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedRows.listed
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesPostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PublicSeries
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesNavigation
import xyz.robinjoon.notionblog.blog.application.model.PublicSeriesSummary
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostLink
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PublishedSeriesStore
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

// 시리즈의 공개 글만 읽는다. 공개 판정은 ExposedPublishedRows를 쓰므로 비공개·보관 글은 행 자체가 나오지 않고, 순번·개수·이전/다음도
// 공개 글만으로 계산된다.
class ExposedPublishedSeriesStore(
    private val propertiesCodec: JsonPostPropertiesCodec,
) : PublishedSeriesStore {
    override fun findAll(excludedPostIds: Set<PostId>): List<PublicSeriesSummary> =
        publishedMembers()
            .select(ExposedPublishedRows.listColumns + seriesColumns)
            .where { listed(excludedPostIds) }
            .orderBy(SeriesTable.title to SortOrder.ASC, SeriesTable.seriesId to SortOrder.ASC, SeriesPostTable.position to SortOrder.ASC)
            .groupBy { SeriesId(it[SeriesTable.seriesId]) }
            .map { (id, rows) ->
                val first = rows.first()
                PublicSeriesSummary(
                    id = id,
                    title = first[SeriesTable.title],
                    description = first[SeriesTable.description],
                    postCount = rows.size,
                    firstPost = ExposedPublishedRows.listEntry(first, propertiesCodec, emptyList()),
                )
            }

    override fun find(
        id: SeriesId,
        excludedPostIds: Set<PostId>,
    ): PublicSeries? {
        val rows = members(id, excludedPostIds, ExposedPublishedRows.listColumns + seriesColumns)
        if (rows.isEmpty()) return null

        val categories = ExposedPublishedRows.categoriesOf(rows.map { PostId(it[PostPublicationTable.postId]) })
        val posts =
            rows.map { row ->
                ExposedPublishedRows.listEntry(row, propertiesCodec, categories[PostId(row[PostPublicationTable.postId])].orEmpty())
            }
        return PublicSeries(id, rows.first()[SeriesTable.title], rows.first()[SeriesTable.description], posts)
    }

    override fun findNavigation(
        postId: PostId,
        excludedPostIds: Set<PostId>,
    ): PublicSeriesNavigation? {
        val seriesId =
            SeriesPostTable
                .select(SeriesPostTable.seriesId)
                .where { SeriesPostTable.postId eq postId.value }
                .singleOrNull()
                ?.let { SeriesId(it[SeriesPostTable.seriesId]) }
                ?: return null
        val rows = members(seriesId, excludedPostIds, listOf(PostPublicationTable.postId, PostRevisionTable.title, SeriesTable.title))
        val links = rows.map { SeriesPostLink(PostId(it[PostPublicationTable.postId]), it[PostRevisionTable.title]) }
        if (links.none { it.postId == postId }) return null

        return PublicSeriesNavigation(seriesId, rows.first()[SeriesTable.title], links, postId)
    }

    private fun members(
        id: SeriesId,
        excludedPostIds: Set<PostId>,
        columns: List<Expression<*>>,
    ): List<ResultRow> =
        publishedMembers()
            .select(columns)
            .where { listed(excludedPostIds) and (SeriesPostTable.seriesId eq id.value) }
            .orderBy(SeriesPostTable.position to SortOrder.ASC)
            .toList()

    private fun publishedMembers(): Join =
        ExposedPublishedRows
            .publishedRevisions()
            .join(SeriesPostTable, JoinType.INNER, PostPublicationTable.postId, SeriesPostTable.postId)
            .join(SeriesTable, JoinType.INNER, SeriesPostTable.seriesId, SeriesTable.seriesId)

    private companion object {
        val seriesColumns = listOf(SeriesTable.seriesId, SeriesTable.title, SeriesTable.description, SeriesPostTable.position)
    }
}

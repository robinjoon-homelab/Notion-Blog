package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.series

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedSeriesStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostLink
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant
import java.util.UUID

class ExposedPublishedSeriesStoreTest {
    private val database = ExposedH2Database()
    private val codec = JsonBlockTreeSnapshotCodec()
    private val store = ExposedPublishedSeriesStore(JsonPostPropertiesCodec())
    private val ddd = seriesId(1)
    private val hidden = seriesId(2)
    private val documents = seriesId(3)
    private val siteDocument = setOf(id(6))

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `only series with a listed public post appear, counted and covered by public posts only`() {
        database.inTransaction {
            seedSeries()

            val all = store.findAll(siteDocument)

            assertThat(all.map { it.id }).containsExactly(ddd)
            assertThat(all.single().title).isEqualTo("DDD")
            assertThat(all.single().postCount).isEqualTo(2)
            assertThat(all.single().firstPost.postId).isEqualTo(id(3))
            assertThat(
                all
                    .single()
                    .firstPost.properties.title,
            ).isEqualTo("Published 3")
            assertThat(store.findAll(emptySet()).map { it.id }).containsExactly(ddd, documents)
        }
    }

    @Test
    fun `a series page holds only its public posts in series order with their tags`() {
        val tag = CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000077"))

        database.inTransaction {
            seedSeries()
            PostRows.category(tag, "Kotlin", 0)
            PostRows.assignment(id(3), tag)

            val series = store.find(ddd, siteDocument)

            assertThat(series?.posts?.map { it.postId }).containsExactly(id(3), id(2))
            assertThat(
                series
                    ?.posts
                    ?.first()
                    ?.categories
                    ?.map { it.name },
            ).containsExactly("Kotlin")
            assertThat(store.find(hidden, siteDocument)).isNull()
            assertThat(store.find(documents, siteDocument)).isNull()
            assertThat(store.find(seriesId(9), siteDocument)).isNull()
        }
    }

    @Test
    fun `the trail skips private posts and uses published titles`() {
        database.inTransaction {
            seedSeries()

            val trail = store.findNavigation(id(2), siteDocument)

            assertThat(trail?.title).isEqualTo("DDD")
            assertThat(trail?.posts).containsExactly(SeriesPostLink(id(3), "Published 3"), SeriesPostLink(id(2), "Published 2"))
            assertThat(trail?.previous).isEqualTo(SeriesPostLink(id(3), "Published 3"))
            assertThat(trail?.next).isNull()
        }
    }

    @Test
    fun `a private, excluded or unlisted post has no trail`() {
        database.inTransaction {
            seedSeries()
            publicPost(7)

            assertThat(store.findNavigation(id(1), siteDocument)).isNull()
            assertThat(store.findNavigation(id(6), siteDocument)).isNull()
            assertThat(store.findNavigation(id(7), siteDocument)).isNull()
        }
    }

    // DDD: 비공개 1, 공개 3(최신은 미발행 제목), 공개 2 순서. 숨김: 보관 4, 비공개 5. 문서: 사이트 문서인 공개 6.
    private fun seedSeries() {
        privatePost(1)
        publicPost(2)
        publicPost(3, latestTitle = "Unpublished title")
        privatePost(4)
        PostPublicationTable.update({ PostPublicationTable.postId eq id(4).value }) { it[archived] = true }
        privatePost(5)
        publicPost(6)
        PostRows.series(ddd, "DDD")
        PostRows.series(hidden, "Hidden")
        PostRows.series(documents, "Documents")
        listOf(1, 3, 2).forEachIndexed { position, label -> PostRows.seriesMember(ddd, id(label), position) }
        listOf(4, 5).forEachIndexed { position, label -> PostRows.seriesMember(hidden, id(label), position) }
        PostRows.seriesMember(documents, id(6), 0)
    }

    private fun publicPost(
        label: Int,
        latestTitle: String? = null,
    ) {
        PostRows.post(id(label), PostFixtures.document("doc-$label"), Instant.parse("2026-10-0${label}T00:00:00Z"))
        PostRows.revision(id(label), PostFixtures.revisionId(label), "Published $label", codec.encode(PostFixtures.paragraphs("Body")))
        val latest = latestTitle?.let { PostFixtures.revisionId(100 + label) } ?: PostFixtures.revisionId(label)
        latestTitle?.let { PostRows.revision(id(label), latest, it, codec.encode(PostFixtures.paragraphs("Draft"))) }
        PostRows.publication(id(label), PostVisibility.PUBLIC, latest, PostFixtures.revisionId(label))
    }

    private fun privatePost(label: Int) {
        PostRows.post(id(label), PostFixtures.document("doc-$label"))
        PostRows.revision(id(label), PostFixtures.revisionId(label), "Private $label", codec.encode(PostFixtures.paragraphs("Hidden")))
        PostRows.publication(id(label), PostVisibility.PRIVATE, PostFixtures.revisionId(label), null)
    }

    private fun id(label: Int): PostId = PostFixtures.postId(label)

    private fun seriesId(label: Int): SeriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-%012d".format(label)))
}

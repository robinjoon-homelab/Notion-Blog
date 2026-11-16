package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant
import java.time.ZoneOffset

class ExposedPublishedPostAuthoredAtTest {
    private val database = ExposedH2Database()
    private val codec = JsonBlockTreeSnapshotCodec()
    private val properties = JsonPostPropertiesCodec()
    private val store = ExposedPublishedPostStore(ExposedRevisionMapper(codec, properties), properties)
    private val importedAt = Instant.parse("2026-11-16T00:00:00Z")
    private val authoredAt = Instant.parse("2023-07-15T00:00:00Z")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `public detail exposes the authored time without changing the actual first publication time`() {
        database.inTransaction {
            publicPost(1, importedAt, authoredAt)
            publicPost(2, importedAt.minusSeconds(60), null)

            val imported = store.find(PostFixtures.postId(1))
            val existing = store.find(PostFixtures.postId(2))

            assertThat(imported?.authoredAt).isEqualTo(authoredAt)
            assertThat(imported?.firstPublishedAt).isEqualTo(importedAt)
            assertThat(imported?.displayDate).isEqualTo(authoredAt)
            assertThat(existing?.authoredAt).isNull()
            assertThat(existing?.displayDate).isEqualTo(importedAt.minusSeconds(60))
        }
    }

    @Test
    fun `home pagination and RSS selection use the authored time with publication fallback and stable ties`() {
        database.inTransaction {
            publicPost(1, importedAt, authoredAt)
            publicPost(2, importedAt.minusSeconds(60), null)
            publicPost(3, importedAt, authoredAt)

            val latest = store.findListPage(emptySet(), PublishedListQuery(PageQuery(1, 2)))
            val oldest = store.findListPage(emptySet(), PublishedListQuery(PageQuery(1, 2), HomePostOrder.OLDEST_FIRST))
            val next = store.findListPage(emptySet(), PublishedListQuery(PageQuery(2, 2)))

            assertThat(latest.items.map { it.postId }).containsExactly(PostFixtures.postId(2), PostFixtures.postId(1))
            assertThat(oldest.items.map { it.postId }).containsExactly(PostFixtures.postId(1), PostFixtures.postId(3))
            assertThat(next.items.map { it.postId }).containsExactly(PostFixtures.postId(3))
            assertThat(oldest.items.map { it.displayDate }).containsExactly(authoredAt, authoredAt)
            assertThat(oldest.items.map { it.firstPublishedAt }).containsExactly(importedAt, importedAt)
            assertThat(store.findRecent(emptySet(), 2).map { it.revision.postId })
                .containsExactly(PostFixtures.postId(2), PostFixtures.postId(1))
        }
    }

    private fun publicPost(
        label: Int,
        firstPublishedAt: Instant,
        authoredAt: Instant?,
    ) {
        val postId = PostFixtures.postId(label)
        val revisionId = PostFixtures.revisionId(label)
        PostRows.post(postId, PostFixtures.document("doc-$label"), firstPublishedAt)
        PostTable.update({ PostTable.postId eq postId.value }) { it[PostTable.authoredAt] = authoredAt?.atOffset(ZoneOffset.UTC) }
        PostRows.revision(postId, revisionId, "Post $label", codec.encode(PostFixtures.paragraphs("Body $label")))
        PostRows.publication(postId, PostVisibility.PUBLIC, revisionId, revisionId)
    }
}

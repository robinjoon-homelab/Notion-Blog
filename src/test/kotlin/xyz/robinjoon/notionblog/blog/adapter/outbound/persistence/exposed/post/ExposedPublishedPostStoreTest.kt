package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SnapshotContentException
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant

class ExposedPublishedPostStoreTest {
    private val database = ExposedH2Database()
    private val codec = JsonBlockTreeSnapshotCodec()
    private val properties = JsonPostPropertiesCodec()
    private val store = ExposedPublishedPostStore(ExposedRevisionMapper(codec, properties), properties)

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `serves the published revision of a public post and nothing of private posts`() {
        database.inTransaction {
            publicPost(1, Instant.parse("2026-10-01T00:00:00Z"), latestTitle = "Unreviewed draft")
            privatePost(2, everPublished = true)
            privatePost(3, everPublished = false)

            val published = store.find(PostFixtures.postId(1))
            assertThat(published?.revision?.title).isEqualTo("Published 1")
            assertThat(published?.revision?.content).isEqualTo(PostFixtures.paragraphs("Body 1"))
            assertThat(published?.firstPublishedAt).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"))
            assertThat(store.find(PostFixtures.postId(2))).isNull()
            assertThat(store.find(PostFixtures.postId(3))).isNull()
            assertThat(store.findPublicPostIds((1..3).map(PostFixtures::postId).toSet())).containsExactly(PostFixtures.postId(1))
        }
    }

    @Test
    fun `lists public posts newest first with exclusions and clamps a page past the end`() {
        database.inTransaction {
            publicPost(1, Instant.parse("2026-10-01T00:00:00Z"))
            publicPost(2, Instant.parse("2026-10-03T00:00:00Z"))
            publicPost(3, Instant.parse("2026-10-02T00:00:00Z"))
            publicPost(4, Instant.parse("2026-10-04T00:00:00Z"))
            privatePost(5, everPublished = true)

            val first = store.findListPage(setOf(PostFixtures.postId(4)), PageQuery(1, 2))
            val beyond = store.findListPage(setOf(PostFixtures.postId(4)), PageQuery(9, 2))

            assertThat(first.items.map { it.postId }).containsExactly(PostFixtures.postId(2), PostFixtures.postId(3))
            assertThat(
                first.items
                    .first()
                    .properties.title,
            ).isEqualTo("Published 2")
            assertThat(first.items.first().revisionId).isEqualTo(PostFixtures.revisionId(2))
            assertThat(first.totalItems).isEqualTo(3)
            assertThat(first.hasNext).isTrue()
            assertThat(beyond.number).isEqualTo(2)
            assertThat(beyond.items.map { it.postId }).containsExactly(PostFixtures.postId(1))
            assertThat(store.findRecent(emptySet(), 2).map { it.revision.postId })
                .containsExactly(PostFixtures.postId(4), PostFixtures.postId(2))
        }
    }

    @Test
    fun `recent posts skip excluded ones before the limit and reject a non-positive limit`() {
        database.inTransaction {
            publicPost(1, Instant.parse("2026-10-01T00:00:00Z"))
            publicPost(2, Instant.parse("2026-10-02T00:00:00Z"))
            publicPost(3, Instant.parse("2026-10-03T00:00:00Z"))
            publicPost(4, Instant.parse("2026-10-04T00:00:00Z"))

            val recent = store.findRecent(setOf(PostFixtures.postId(4)), 2)

            assertThat(recent.map { it.revision.postId }).containsExactly(PostFixtures.postId(3), PostFixtures.postId(2))
            assertThatIllegalArgumentException().isThrownBy { store.findRecent(emptySet(), 0) }
            assertThatIllegalArgumentException().isThrownBy { store.findRecent(emptySet(), -1) }
        }
    }

    @Test
    fun `posts first published at the same time are ordered by post id`() {
        val sameTime = Instant.parse("2026-10-02T00:00:00Z")

        database.inTransaction {
            publicPost(7, sameTime)
            publicPost(3, sameTime)
            publicPost(5, sameTime)
            publicPost(1, Instant.parse("2026-10-03T00:00:00Z"))

            val expected = listOf(1, 3, 5, 7).map(PostFixtures::postId)
            assertThat(store.findListPage(emptySet(), PageQuery(1, 10)).items.map { it.postId }).containsExactlyElementsOf(expected)
            assertThat(store.findRecent(emptySet(), 10).map { it.revision.postId }).containsExactlyElementsOf(expected)
        }
    }

    @Test
    fun `a damaged stored revision is reported as a snapshot content failure with its cause`() {
        database.inTransaction {
            publicPost(1, Instant.parse("2026-10-01T00:00:00Z"))
            PostRevisionTable.update { it[contentJson] = """{"kind":"unknown"}""" }

            assertThatThrownBy { store.find(PostFixtures.postId(1)) }
                .isInstanceOf(SnapshotContentException::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { store.findRecent(emptySet(), 1) }
                .isInstanceOf(SnapshotContentException::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `damaged stored properties are reported as a snapshot content failure when listing`() {
        database.inTransaction {
            publicPost(1, Instant.parse("2026-10-01T00:00:00Z"))
            // JSON 컬럼은 객체도 받지만 리비전 태그는 배열이어야 한다.
            PostRevisionTable.update { it[tagsJson] = "{}" }

            assertThatThrownBy { store.findListPage(emptySet(), PageQuery(1, 10)) }
                .isInstanceOf(SnapshotContentException::class.java)
                .hasCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun publicPost(
        label: Int,
        firstPublishedAt: Instant,
        latestTitle: String? = null,
    ) {
        val postId = PostFixtures.postId(label)
        PostRows.post(postId, PostFixtures.document("doc-$label"), firstPublishedAt)
        PostRows.revision(postId, PostFixtures.revisionId(label), "Published $label", codec.encode(PostFixtures.paragraphs("Body $label")))
        val latest = latestTitle?.let { PostFixtures.revisionId(100 + label) } ?: PostFixtures.revisionId(label)
        latestTitle?.let { PostRows.revision(postId, latest, it, codec.encode(PostFixtures.paragraphs("Draft"))) }
        PostRows.publication(postId, PostVisibility.PUBLIC, latest, PostFixtures.revisionId(label))
    }

    private fun privatePost(
        label: Int,
        everPublished: Boolean,
    ) {
        val postId: PostId = PostFixtures.postId(label)
        PostRows.post(postId, PostFixtures.document("doc-$label"), Instant.parse("2026-10-05T00:00:00Z"))
        PostRows.revision(postId, PostFixtures.revisionId(label), "Private $label", codec.encode(PostFixtures.paragraphs("Hidden")))
        val published = PostFixtures.revisionId(label).takeIf { everPublished }
        PostRows.publication(postId, PostVisibility.PRIVATE, PostFixtures.revisionId(label), published)
    }
}

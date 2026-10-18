package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPublishedPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedRevisionMapper
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonPostPropertiesCodec
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PublishedListQuery
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant
import java.util.UUID

// 공개 검색·공개 글 수·글의 앱 태그 조회다.
class ExposedPublishedPostSearchTest {
    private val database = ExposedH2Database()
    private val codec = JsonBlockTreeSnapshotCodec()
    private val properties = JsonPostPropertiesCodec()
    private val store = ExposedPublishedPostStore(ExposedRevisionMapper(codec, properties), properties)
    private val kotlin = CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000071"))
    private val essay = CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000072"))

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `search matches the published title or summary regardless of case`() {
        database.inTransaction {
            seedPosts()

            assertThat(search("kotlin")).containsExactly(id(2), id(1))
            assertThat(search("SPRING")).containsExactly(id(3))
        }
    }

    @Test
    fun `words only in an unpublished latest revision or a private post are not found`() {
        database.inTransaction {
            seedPosts()

            assertThat(search("draft only")).isEmpty()
            assertThat(search("hidden")).isEmpty()
        }
    }

    @Test
    fun `percent and underscore in search text match literally`() {
        database.inTransaction {
            seedPosts()

            assertThat(search("100%")).containsExactly(id(3))
            assertThat(search("a_b")).containsExactly(id(2))
            assertThat(search("1%0")).isEmpty()
        }
    }

    @Test
    fun `search is combined with a tag and the total follows the same condition`() {
        database.inTransaction {
            seedPosts()

            val page = store.findListPage(emptySet(), PublishedListQuery(PageQuery(1, 1), categoryId = kotlin, search = "kotlin"))

            assertThat(page.items.map { it.postId }).containsExactly(id(1))
            assertThat(page.totalItems).isEqualTo(1)
        }
    }

    @Test
    fun `the public count and a post's tags ignore private and excluded posts`() {
        database.inTransaction {
            seedPosts()

            assertThat(store.countListed(emptySet())).isEqualTo(3)
            assertThat(store.countListed(setOf(id(3)))).isEqualTo(2)
            assertThat(store.findCategories(id(1)).map { it.name }).containsExactly("Essay", "Kotlin")
            assertThat(store.findCategories(id(4))).isEmpty()
        }
    }

    // 1: 제목 Kotlin, 2: 요약 kotlin·a_b, 3: 제목 Spring 100%, 4: 비공개 Hidden. 1의 최신 미발행 리비전에만 "draft only"가 있다.
    private fun seedPosts() {
        publicPost(1, "Kotlin coroutines", "Notes")
        publicPost(2, "Coroutines", "Using kotlin in a_b tests")
        publicPost(3, "Spring 100% guide", null)
        PostRows.revision(id(1), PostFixtures.revisionId(101), "Draft only title", codec.encode(PostFixtures.paragraphs("Draft")))
        PostPublicationTable.update({ PostPublicationTable.postId eq id(1).value }) {
            it[latestRevisionId] = PostFixtures.revisionId(101).value
        }
        PostRows.post(id(4), PostFixtures.document("doc-4"))
        PostRows.revision(id(4), PostFixtures.revisionId(4), "Hidden kotlin", codec.encode(PostFixtures.paragraphs("Hidden")))
        PostRows.publication(id(4), PostVisibility.PRIVATE, PostFixtures.revisionId(4), null)
        PostRows.category(kotlin, "Kotlin", 1)
        PostRows.category(essay, "Essay", 0)
        listOf(1 to kotlin, 1 to essay, 2 to essay, 4 to kotlin).forEach { (label, tag) -> PostRows.assignment(id(label), tag) }
    }

    private fun publicPost(
        label: Int,
        title: String,
        summary: String?,
    ) {
        val revisionId: PostRevisionId = PostFixtures.revisionId(label)
        PostRows.post(id(label), PostFixtures.document("doc-$label"), Instant.parse("2026-10-0${label}T00:00:00Z"))
        PostRows.revision(id(label), revisionId, title, codec.encode(PostFixtures.paragraphs("Body")))
        PostRevisionTable.update({ PostRevisionTable.revisionId eq revisionId.value }) { it[PostRevisionTable.summary] = summary }
        PostRows.publication(id(label), PostVisibility.PUBLIC, revisionId, revisionId)
    }

    private fun search(text: String): List<PostId> =
        store.findListPage(emptySet(), PublishedListQuery(PageQuery(1, 10), search = text)).items.map { it.postId }

    private fun id(label: Int): PostId = PostFixtures.postId(label)
}

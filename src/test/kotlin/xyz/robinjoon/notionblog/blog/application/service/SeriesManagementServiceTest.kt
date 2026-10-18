package xyz.robinjoon.notionblog.blog.application.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.SeriesChangeResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesEditor
import xyz.robinjoon.notionblog.blog.application.model.SeriesLookupResult
import xyz.robinjoon.notionblog.blog.application.model.SeriesOverview
import xyz.robinjoon.notionblog.blog.application.model.SeriesPostSummary
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesMembershipConflictException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import java.util.UUID

class SeriesManagementServiceTest {
    private val posts = mockk<PostRepository>()
    private val series = mockk<SeriesRepository>()
    private val service = SeriesManagementService(posts, series) { seriesId(9) }
    private val publicPost = PostFixtures.publicPost(PostFixtures.postId(1), published = PostFixtures.revisionId(1))
    private val draft = PostFixtures.privatePost(PostFixtures.postId(2), latest = PostFixtures.revisionId(2))
    private val archived = PostFixtures.privatePost(PostFixtures.postId(3)).archive()
    private val elsewhere = PostFixtures.privatePost(PostFixtures.postId(4))
    private val free = PostFixtures.privatePost(PostFixtures.postId(5))
    private val ddd = Series.restore(seriesId(1), "DDD", "순서대로", listOf(draft.id, publicPost.id))
    private val notes = Series.restore(seriesId(2), "Notes", "", listOf(elsewhere.id))

    @BeforeEach
    fun givenStoredState() {
        val managed = listOf(publicPost, draft, archived, elsewhere, free)
        every { posts.findAllManaged() } returns managed
        every { posts.findTitles(managed.mapTo(linkedSetOf()) { it.id }) } returns
            mapOf(publicPost.id to "Public", draft.id to "Draft", archived.id to "Archived", elsewhere.id to "Elsewhere", free.id to "Free")
        every { series.findAll() } returns listOf(ddd, notes)
        every { series.save(any()) } just Runs
        every { series.delete(any()) } just Runs
    }

    @Test
    fun `the list counts every member and the public ones separately`() {
        assertThat(service.list()).containsExactly(
            SeriesOverview(ddd.id, "DDD", "순서대로", 2, 1),
            SeriesOverview(notes.id, "Notes", "", 1, 0),
        )
    }

    @Test
    fun `the editor shows members in series order and unarchived candidates with the series they would leave`() {
        val expected =
            SeriesEditor(
                overview = SeriesOverview(ddd.id, "DDD", "순서대로", 2, 1),
                members =
                    listOf(
                        SeriesPostSummary(draft.id, "Draft", AdminPostStatus.DRAFT, null),
                        SeriesPostSummary(publicPost.id, "Public", AdminPostStatus.PUBLISHED, null),
                    ),
                candidates =
                    listOf(
                        SeriesPostSummary(elsewhere.id, "Elsewhere", AdminPostStatus.DRAFT, "Notes"),
                        SeriesPostSummary(free.id, "Free", AdminPostStatus.DRAFT, null),
                    ),
            )

        assertThat(service.get(ddd.id)).isEqualTo(SeriesLookupResult.Found(expected))
        assertThat(service.get(seriesId(8))).isEqualTo(SeriesLookupResult.NotFound)
    }

    @Test
    fun `a new series gets the generated identity`() {
        assertThat(service.create("Kotlin", "")).isEqualTo(SeriesChangeResult.Saved(seriesId(9)))
    }

    @Test
    fun `saving can take a post from another series`() {
        every { series.lock(setOf(ddd.id, notes.id)) } returns listOf(ddd, notes)

        val result = service.save(ddd.id, "DDD", "", listOf(elsewhere.id, publicPost.id))

        assertThat(result).isEqualTo(SeriesChangeResult.Saved(ddd.id))
    }

    @Test
    fun `saving with an unmanaged post or for a deleted series changes nothing`() {
        every { series.lock(setOf(seriesId(8))) } returns emptyList()

        assertThat(service.save(ddd.id, "DDD", "", listOf(PostFixtures.postId(7)))).isEqualTo(SeriesChangeResult.PostNotFound)
        assertThat(service.save(seriesId(8), "Gone", "", listOf(free.id))).isEqualTo(SeriesChangeResult.NotFound)
    }

    @Test
    fun `a post taken by another series concurrently fails the whole save`() {
        every { series.lock(setOf(ddd.id)) } returns listOf(ddd)
        every { series.save(ddd.update("DDD", "", listOf(free.id))) } throws SeriesMembershipConflictException("taken")

        assertThatThrownBy { service.save(ddd.id, "DDD", "", listOf(free.id)) }.isInstanceOf(SeriesMembershipConflictException::class.java)
    }

    @Test
    fun `deleting removes only an existing series`() {
        every { series.lock(setOf(ddd.id)) } returns listOf(ddd)
        every { series.lock(setOf(seriesId(8))) } returns emptyList()

        assertThat(service.delete(ddd.id)).isEqualTo(SeriesChangeResult.Saved(ddd.id))
        assertThat(service.delete(seriesId(8))).isEqualTo(SeriesChangeResult.NotFound)
    }

    private fun seriesId(label: Int): SeriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-%012d".format(label)))
}

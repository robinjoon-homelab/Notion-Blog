package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.series

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedSeriesRepository
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostCategoryTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostPublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesMembershipConflictException
import xyz.robinjoon.notionblog.blog.domain.category.CategoryId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.util.UUID

class ExposedSeriesRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedSeriesRepository()
    private val ddd = seriesId(1)
    private val notes = seriesId(2)

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `a saved series keeps its order and saving again replaces the members and their order`() {
        database.inTransaction {
            (1..3).forEach(::post)
            repository.save(Series.restore(ddd, "DDD", "설명", listOf(id(3), id(1), id(2))))

            assertThat(repository.find(ddd)?.postIds).containsExactly(id(3), id(1), id(2))

            repository.save(Series.restore(ddd, "DDD 2", "", listOf(id(2), id(3))))

            assertThat(repository.find(ddd)).isEqualTo(Series.restore(ddd, "DDD 2", "", listOf(id(2), id(3))))
            assertThat(repository.find(seriesId(9))).isNull()
        }
    }

    @Test
    fun `series are listed by title then id and only existing ones are locked in id order`() {
        database.inTransaction {
            repository.save(Series.create(notes, "Alpha", ""))
            repository.save(Series.create(ddd, "Beta", ""))
            repository.save(Series.create(seriesId(3), "Alpha", ""))

            assertThat(repository.findAll().map { it.id }).containsExactly(notes, seriesId(3), ddd)
            assertThat(repository.lock(setOf(seriesId(3), seriesId(9), ddd)).map { it.id }).containsExactly(ddd, seriesId(3))
            assertThat(repository.lock(emptySet())).isEmpty()
        }
    }

    @Test
    fun `a post already in another series is refused and the stored membership stays`() {
        database.inTransaction {
            post(1)
            repository.save(Series.restore(ddd, "DDD", "", listOf(id(1))))

            assertThatThrownBy { repository.save(Series.restore(notes, "Notes", "", listOf(id(1)))) }
                .isInstanceOf(SeriesMembershipConflictException::class.java)
            assertThat(repository.find(ddd)?.postIds).containsExactly(id(1))
            assertThat(repository.find(notes)).isNull()
        }
    }

    @Test
    fun `deleting a series keeps its posts, their tags and their publication`() {
        val tag = CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000077"))

        database.inTransaction {
            post(1)
            PostRows.publication(id(1), PostVisibility.PUBLIC, null, null)
            PostRows.category(tag, "Kotlin", 0)
            PostRows.assignment(id(1), tag)
            repository.save(Series.restore(ddd, "DDD", "", listOf(id(1))))

            repository.delete(ddd)

            assertThat(repository.find(ddd)).isNull()
            assertThat(PostTable.selectAll().count()).isEqualTo(1)
            assertThat(PostCategoryTable.selectAll().count()).isEqualTo(1)
            assertThat(PostPublicationTable.selectAll().single()[PostPublicationTable.visibility]).isEqualTo("PUBLIC")
            repository.save(Series.restore(notes, "Notes", "", listOf(id(1))))
            assertThat(repository.find(notes)?.postIds).containsExactly(id(1))
        }
    }

    private fun post(label: Int) {
        PostRows.post(id(label), PostFixtures.document("doc-$label"))
    }

    private fun id(label: Int): PostId = PostFixtures.postId(label)

    private fun seriesId(label: Int): SeriesId = SeriesId(UUID.fromString("00000000-0000-0000-0001-%012d".format(label)))
}

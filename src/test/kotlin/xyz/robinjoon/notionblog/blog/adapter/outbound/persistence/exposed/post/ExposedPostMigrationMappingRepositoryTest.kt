package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedPostMigrationMappingRepository
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows

class ExposedPostMigrationMappingRepositoryTest {
    private val database = ExposedH2Database()
    private val repository = ExposedPostMigrationMappingRepository()
    private val postId = PostFixtures.postId(1)
    private val document = PostFixtures.document("migrated")
    private val selection = MigrationSelection("data-source", MigrationFields("title", "summary", "tags", "date"))

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `mapping round trips by its source document and remains absent for other sources`() {
        database.inTransaction {
            PostRows.post(postId, document)
            PostRows.publication(postId, PostVisibility.PRIVATE, null, null)
            repository.save(postId, selection)

            assertThat(repository.find(document)).isEqualTo(selection)
            assertThat(repository.find(PostFixtures.document("other"))).isNull()
        }
    }

    @Test
    fun `another migration cannot overwrite a mapping`() {
        database.inTransaction {
            PostRows.post(postId, document)
            PostRows.publication(postId, PostVisibility.PRIVATE, null, null)
            repository.save(postId, selection)

            assertThatThrownBy { repository.save(postId, MigrationSelection("different", MigrationFields("new-title"))) }
                .isInstanceOf(ExposedSQLException::class.java)
            assertThat(repository.find(document)).isEqualTo(selection)
        }
    }

    @Test
    fun `a mapping requires a managed post`() {
        database.inTransaction {
            PostRows.post(postId, document)

            assertThatThrownBy { repository.save(postId, selection) }.isInstanceOf(ExposedSQLException::class.java)
        }
    }
}

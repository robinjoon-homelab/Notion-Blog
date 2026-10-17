package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.post

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.ExposedLegacyPostStore
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostAvailabilityTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSnapshotTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationMemberTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationRevisionTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PublicationTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.snapshot.JsonBlockTreeSnapshotCodec
import xyz.robinjoon.notionblog.blog.application.model.LegacySnapshot
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostVisibility
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.support.fixture.PostFixtures
import xyz.robinjoon.notionblog.support.persistence.ExposedH2Database
import xyz.robinjoon.notionblog.support.persistence.PostRows
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// 레거시 공개 집합은 제거된 게시 트리 읽기 경로가 제공하던 결과와 행 단위로 일치해야 한다.
class ExposedLegacyPostStoreTest {
    private val database = ExposedH2Database()
    private val codec = JsonBlockTreeSnapshotCodec()
    private val store = ExposedLegacyPostStore(codec)
    private val publicationId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val activeRevision = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val supersededRevision = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    private val capturedAt = Instant.parse("2026-09-01T00:00:00Z")
    private val firstPublishedAt = Instant.parse("2026-08-01T00:00:00Z")

    @AfterEach
    fun closeDatabase() = database.close()

    @Test
    fun `only active members with a PUBLISHED availability and a snapshot were public`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", snapshot(1)))
            legacyPost(LegacyRow(2, activeRevision, "UNPUBLISHED", snapshot(2)))
            legacyPost(LegacyRow(3, null, "PUBLISHED", snapshot(3)))
            legacyPost(LegacyRow(4, supersededRevision, "PUBLISHED", snapshot(4)))
            legacyPost(LegacyRow(5, activeRevision, "PUBLISHED", null))

            val records = store.findUnmanaged().associateBy { it.binding.postId }

            assertThat(records.keys).containsExactlyInAnyOrderElementsOf((1..5).map(PostFixtures::postId))
            assertThat(records.filterValues { it.legacyPublic }.keys).containsExactly(PostFixtures.postId(1))
            assertThat(records.getValue(PostFixtures.postId(5)).snapshot).isNull()
            assertThat(records.getValue(PostFixtures.postId(2)).firstPublishedAt).isEqualTo(firstPublishedAt)
        }
    }

    @Test
    fun `a post without a legacy availability row was never public`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, null, snapshot(1)))

            val record = store.findUnmanaged().single()
            assertThat(record.legacyPublic).isFalse()
            assertThat(record.snapshot).isNotNull()
        }
    }

    @Test
    fun `members of an active pointer whose revision is not ACTIVE were never public`() {
        database.inTransaction {
            seedPublication(activePointer = supersededRevision)
            legacyPost(LegacyRow(1, supersededRevision, "PUBLISHED", snapshot(1)))

            assertThat(store.findUnmanaged().single().legacyPublic).isFalse()
        }
    }

    @Test
    fun `a legacy snapshot reports its capture time and source-hosted files`() {
        val file = PostFixtures.hosted("legacy.png", expiresAt = capturedAt)

        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", codec.encode(BlockTree(listOf(PostFixtures.image("i", file))))))

            assertThat(store.findUnmanaged().single().snapshot).isEqualTo(LegacySnapshot(capturedAt, setOf(file)))
        }
    }

    @Test
    fun `an undecodable legacy snapshot is still public with no files to capture`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", """{"kind":"unknown"}"""))

            val record = store.findUnmanaged().single()
            assertThat(record.legacyPublic).isTrue()
            assertThat(record.snapshot).isEqualTo(LegacySnapshot(capturedAt, emptySet()))
        }
    }

    @Test
    fun `posts that already have a publication are never offered again`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", snapshot(1)))
            PostRows.publication(PostFixtures.postId(1), PostVisibility.PRIVATE, null, null)

            assertThat(store.findUnmanaged()).isEmpty()
        }
    }

    @Test
    fun `an ambiguous legacy publication fails the migration instead of widening the public set`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", snapshot(1)))
            secondPublication()

            assertThatIllegalStateException().isThrownBy { store.findUnmanaged() }
        }
    }

    @Test
    fun `an ambiguous legacy publication does not matter once every post is managed`() {
        database.inTransaction {
            seedPublication()
            legacyPost(LegacyRow(1, activeRevision, "PUBLISHED", snapshot(1)))
            PostRows.publication(PostFixtures.postId(1), PostVisibility.PRIVATE, null, null)
            secondPublication()

            assertThat(store.findUnmanaged()).isEmpty()
        }
    }

    private fun secondPublication() {
        PublicationTable.insert {
            it[PublicationTable.publicationId] = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            it[rootPostId] = null
            it[activeRevisionId] = null
        }
    }

    private fun seedPublication(activePointer: UUID = activeRevision) {
        PublicationTable.insert {
            it[PublicationTable.publicationId] = this@ExposedLegacyPostStoreTest.publicationId
            it[rootPostId] = null
            it[activeRevisionId] = activePointer
        }
        revision(activeRevision, "ACTIVE")
        revision(supersededRevision, "SUPERSEDED")
    }

    private fun revision(
        id: UUID,
        state: String,
    ) {
        PublicationRevisionTable.insert {
            it[revisionId] = id
            it[PublicationRevisionTable.publicationId] = this@ExposedLegacyPostStoreTest.publicationId
            it[PublicationRevisionTable.state] = state
            it[startedAt] = capturedAt.atOffset(ZoneOffset.UTC)
            it[activatedAt] = capturedAt.atOffset(ZoneOffset.UTC)
        }
    }

    private fun snapshot(label: Int): String = codec.encode(PostFixtures.paragraphs("Legacy $label"))

    private fun legacyPost(row: LegacyRow) {
        val postId: PostId = PostFixtures.postId(row.label)
        PostRows.post(postId, PostFixtures.document("legacy-${row.label}"), firstPublishedAt)
        row.member?.let { revisionId ->
            PublicationMemberTable.insert {
                it[PublicationMemberTable.revisionId] = revisionId
                it[PublicationMemberTable.postId] = postId.value
                it[parentPostId] = null
                it[depth] = 0
            }
        }
        row.status?.let { legacyStatus ->
            PostAvailabilityTable.insert {
                it[PostAvailabilityTable.postId] = postId.value
                it[PostAvailabilityTable.status] = legacyStatus
                it[confirmedAt] = capturedAt.atOffset(ZoneOffset.UTC)
            }
        }
        row.snapshotJson?.let { json ->
            PostSnapshotTable.insert {
                it[PostSnapshotTable.postId] = postId.value
                it[snapshotJson] = json
                it[sourceRevision] = "legacy-${row.label}"
                it[PostSnapshotTable.capturedAt] = this@ExposedLegacyPostStoreTest.capturedAt.atOffset(ZoneOffset.UTC)
            }
        }
    }

    // 레거시 글 하나다. 활성 트리 소속 여부, 레거시 availability 상태, 저장된 스냅숏 JSON(있다면)을 담는다.
    private data class LegacyRow(
        val label: Int,
        val member: UUID?,
        val status: String?,
        val snapshotJson: String?,
    )
}

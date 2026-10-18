package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesPostTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.SeriesTable
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesMembershipConflictException
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.SeriesRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.series.Series
import xyz.robinjoon.notionblog.blog.domain.series.SeriesId

class ExposedSeriesRepository : SeriesRepository {
    override fun findAll(): List<Series> =
        restore(
            SeriesTable
                .selectAll()
                .orderBy(SeriesTable.title to SortOrder.ASC, SeriesTable.seriesId to SortOrder.ASC)
                .toList(),
        )

    override fun find(id: SeriesId): Series? =
        restore(
            SeriesTable
                .selectAll()
                .where { SeriesTable.seriesId eq id.value }
                .toList(),
        ).singleOrNull()

    // ID 순서로 잠가, 같은 시리즈를 함께 잠그는 명령끼리 서로 반대 순서로 기다리지 않게 한다.
    override fun lock(ids: Set<SeriesId>): List<Series> {
        if (ids.isEmpty()) return emptyList()

        return restore(
            SeriesTable
                .selectAll()
                .where { SeriesTable.seriesId inList ids.map(SeriesId::value) }
                .orderBy(SeriesTable.seriesId to SortOrder.ASC)
                .forUpdate()
                .toList(),
        )
    }

    // PostgreSQL은 실패한 문장 뒤에 트랜잭션을 쓸 수 없으므로 다른 시리즈 소속은 쓰기 전에 확인하고, 동시에 같은 글이 들어온
    // 경우만 유일성 위반으로 받아 같은 예외로 알린다. 소속 행은 지우고 다시 넣어 위치를 0부터 다시 매긴다.
    override fun save(series: Series) {
        if (isClaimedElsewhere(series)) throw SeriesMembershipConflictException("a post already belongs to another series")
        try {
            SeriesTable.upsert(SeriesTable.seriesId) {
                it[seriesId] = series.id.value
                it[title] = series.title
                it[description] = series.description
            }
            SeriesPostTable.deleteWhere { seriesId eq series.id.value }
            SeriesPostTable.batchInsert(series.postIds.withIndex().toList()) { member ->
                this[SeriesPostTable.seriesId] = series.id.value
                this[SeriesPostTable.postId] = member.value.value
                this[SeriesPostTable.position] = member.index
            }
        } catch (exception: ExposedSQLException) {
            if (exception.sqlState == UNIQUE_VIOLATION) {
                throw SeriesMembershipConflictException("a post was added to another series concurrently", exception)
            }
            throw exception
        }
    }

    override fun delete(id: SeriesId) {
        SeriesPostTable.deleteWhere { seriesId eq id.value }
        SeriesTable.deleteWhere { seriesId eq id.value }
    }

    private fun isClaimedElsewhere(series: Series): Boolean =
        SeriesPostTable
            .select(SeriesPostTable.postId)
            .where {
                (SeriesPostTable.postId inList series.postIds.map(PostId::value)) and (SeriesPostTable.seriesId neq series.id.value)
            }.any()

    private fun restore(rows: List<ResultRow>): List<Series> {
        if (rows.isEmpty()) return emptyList()

        val members =
            SeriesPostTable
                .select(SeriesPostTable.seriesId, SeriesPostTable.postId)
                .where { SeriesPostTable.seriesId inList rows.map { it[SeriesTable.seriesId] } }
                .orderBy(SeriesPostTable.position to SortOrder.ASC)
                .groupBy({ SeriesId(it[SeriesPostTable.seriesId]) }) { PostId(it[SeriesPostTable.postId]) }
        return rows.map { row ->
            val id = SeriesId(row[SeriesTable.seriesId])
            Series.restore(id, row[SeriesTable.title], row[SeriesTable.description], members[id].orEmpty())
        }
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}

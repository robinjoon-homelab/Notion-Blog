package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostMigrationMappingTable
import xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed.table.PostSourceBindingTable
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostMigrationMappingRepository
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.post.PostId

class ExposedPostMigrationMappingRepository : PostMigrationMappingRepository {
    override fun find(sourceDocument: SourceDocumentRef): MigrationSelection? =
        PostMigrationMappingTable
            .join(PostSourceBindingTable, JoinType.INNER, PostMigrationMappingTable.postId, PostSourceBindingTable.postId)
            .selectAll()
            .where {
                (PostSourceBindingTable.sourceId eq sourceDocument.sourceId.value) and
                    (PostSourceBindingTable.externalId eq sourceDocument.externalId)
            }.singleOrNull()
            ?.let { row ->
                MigrationSelection(
                    row[PostMigrationMappingTable.dataSourceId],
                    MigrationFields(
                        row[PostMigrationMappingTable.titlePropertyId],
                        row[PostMigrationMappingTable.summaryPropertyId],
                        row[PostMigrationMappingTable.tagsPropertyId],
                        row[PostMigrationMappingTable.authoredAtPropertyId],
                    ),
                )
            }

    override fun save(
        postId: PostId,
        selection: MigrationSelection,
    ) {
        PostMigrationMappingTable.insert {
            it[PostMigrationMappingTable.postId] = postId.value
            it[dataSourceId] = selection.dataSourceId
            it[titlePropertyId] = selection.fields.titlePropertyId
            it[summaryPropertyId] = selection.fields.summaryPropertyId
            it[tagsPropertyId] = selection.fields.tagsPropertyId
            it[authoredAtPropertyId] = selection.fields.authoredAtPropertyId
        }
    }
}

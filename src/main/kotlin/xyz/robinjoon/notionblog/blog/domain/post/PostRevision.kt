package xyz.robinjoon.notionblog.blog.domain.post

import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.block.BlockTree
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import java.time.Instant
import java.util.UUID

@JvmInline
value class PostRevisionId(
    val value: UUID,
)

// 원본 문서에서 작성한 페이지 속성이다. 태그는 검증 전에 변경할 수 없는 목록으로 복사하므로, 호출자의 목록이나 노출된 목록을 캐스트한
// 값으로 생성 이후 태그 규칙을 깨뜨릴 수 없다. 값 동등성을 유지하면서 복사한 저장소를 명시적으로 소유한다.
class PostProperties(
    val title: String,
    val summary: String?,
    tags: List<String>,
    val cover: MediaSource?,
) {
    val tags: List<String> = java.util.List.copyOf(tags)

    init {
        require(title.isNotBlank()) { "post title must not be blank" }
        require(summary == null || summary.isNotBlank()) { "post summary must not be blank when present" }
        require(this.tags.all(String::isNotBlank)) { "post tags must not be blank" }
        require(this.tags.toSet().size == this.tags.size) { "post tags must be distinct" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PostProperties && title == other.title && summary == other.summary && tags == other.tags && cover == other.cover)

    override fun hashCode(): Int = listOf(title, summary, tags, cover).hashCode()

    override fun toString(): String = "PostProperties(title=$title, summary=$summary, tags=$tags, cover=$cover)"
}

// 글을 한 번 가져온 상태다. 리비전은 저장된 뒤 바뀌지 않으므로 관리자가 검토한 리비전이 곧 독자가 받는 리비전이다.
data class PostRevision(
    val id: PostRevisionId,
    val postId: PostId,
    val properties: PostProperties,
    val content: BlockTree,
    val sourceRevision: SourceRevision,
    val capturedAt: Instant,
) {
    val title: String
        get() = properties.title

    fun sourceHostedMedia(): Set<MediaSource.SourceHosted> = sourceHostedMedia(properties, content)
}

fun sourceHostedMedia(
    properties: PostProperties,
    content: BlockTree,
): Set<MediaSource.SourceHosted> = content.sourceHostedMedia() + listOfNotNull(properties.cover as? MediaSource.SourceHosted)

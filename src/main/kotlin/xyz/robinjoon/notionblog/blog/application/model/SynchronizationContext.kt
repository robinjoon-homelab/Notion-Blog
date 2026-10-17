package xyz.robinjoon.notionblog.blog.application.model

import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource
import xyz.robinjoon.notionblog.blog.domain.post.block.media.SourceMediaKey

data class PostSynchronizationContext(
    val postId: PostId,
    val sourceDocument: SourceDocumentRef,
    val latestSourceRevision: SourceRevision?,
    val pendingRepairs: List<MediaRepairTarget>,
)

// 파일의 저장 사본이 아직 없는 표시 리비전이다(레거시 스냅숏에서 복사한 리비전만 해당한다). 사본은 그 리비전에 저장된 URL에서, 또는
// 원본이 여전히 같은 원본 리비전을 제공할 때 원본에서만 받을 수 있다.
data class MediaRepairTarget(
    val revisionId: PostRevisionId,
    val sourceRevision: SourceRevision,
    val media: Set<MediaSource.SourceHosted>,
) {
    // 누락된 각 파일을 어디서 받을지 정한다. 새로 서명된 URL은 현재 원본이 정확히 이 원본 리비전을 제공할 때만 저장된 URL을
    // 대신한다. 바뀐 초안은 파일 이름이 같아도 이 리비전을 복구하지 않는다. URL이 아직 유효한지는 내려받을 때 판단한다.
    fun sources(current: ImportedRevision): List<MediaSource.SourceHosted> {
        if (current.sourceRevision != sourceRevision) return media.toList()
        val fresh = current.sourceHostedMedia().associateBy { SourceMediaKey.of(it.url) }
        return media.map { stored -> fresh[SourceMediaKey.of(stored.url)] ?: stored }
    }
}

// 한 번의 가져오기에서 내려받은 파일이다. 새 리비전의 파일과, 속한 리비전을 키로 하는 레거시 복구 파일로 나뉜다.
data class CapturedMedia(
    val newRevision: List<FetchedMedia>,
    val repairs: Map<PostRevisionId, List<FetchedMedia>>,
) {
    val all: List<FetchedMedia>
        get() = newRevision + repairs.values.flatten()

    companion object {
        val empty = CapturedMedia(emptyList(), emptyMap())
    }
}

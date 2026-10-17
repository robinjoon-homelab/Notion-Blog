package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.ContentReferences
import xyz.robinjoon.notionblog.blog.application.model.RevisionMedia
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.PostRepository
import xyz.robinjoon.notionblog.blog.domain.post.PostRevision
import xyz.robinjoon.notionblog.blog.domain.post.PostRevisionId

// 한 페이지에 보이는 리비전들의 링크와 저장 파일 사본이다. 파일은 각 리비전 자신의 연결에서 가져오므로, 나중에 만든 사본이 이전 리비전이
// 보여 주는 내용을 바꾸지 않는다.
@Transactional(readOnly = true)
class ContentReferencesService(
    private val links: ResolvePostLinksService,
    private val posts: PostRepository,
) {
    @Transactional(readOnly = true)
    fun resolve(revisions: Collection<PostRevision>): ContentReferences =
        ContentReferences(
            links = links.resolve(revisions.map(PostRevision::content)),
            media = media(revisions.mapTo(linkedSetOf()) { it.id }),
        )

    @Transactional(readOnly = true)
    fun media(revisionIds: Set<PostRevisionId>): RevisionMedia = RevisionMedia(posts.findMediaBindings(revisionIds))
}

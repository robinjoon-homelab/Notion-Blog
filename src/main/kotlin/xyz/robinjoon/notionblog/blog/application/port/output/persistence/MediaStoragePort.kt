package xyz.robinjoon.notionblog.blog.application.port.output.persistence

import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import java.time.Instant

interface MediaStoragePort {
    // 내용 주소 방식이다. 이미 저장된 키는 기존 바이트를 유지한다.
    fun save(
        media: Collection<FetchedMedia>,
        storedAt: Instant,
    )

    // 공개 글의 게시 리비전에 연결된 파일만 찾는다.
    fun findPublic(key: MediaKey): MediaContent?

    // 어느 리비전에든 연결된 파일을 찾는다.
    fun findForPreview(key: MediaKey): MediaContent?
}

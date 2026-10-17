package xyz.robinjoon.notionblog.blog.application.port.input

import xyz.robinjoon.notionblog.blog.application.model.MediaLookupResult
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey

interface GetMediaUseCase {
    // 공개 글의 게시 리비전에 속한 파일만 반환한다.
    fun getPublic(key: MediaKey): MediaLookupResult

    // 인증된 미리보기용으로 모든 리비전의 파일을 반환한다.
    fun getForPreview(key: MediaKey): MediaLookupResult
}

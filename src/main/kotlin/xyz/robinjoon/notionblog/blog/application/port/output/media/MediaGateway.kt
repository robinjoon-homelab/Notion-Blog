package xyz.robinjoon.notionblog.blog.application.port.output.media

import xyz.robinjoon.notionblog.blog.application.model.FetchedMedia
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaSource

// 원격 파일을 내려받으므로 DB 트랜잭션 밖에서 호출한다. 모든 실패는 MediaFetchException이다.
interface MediaGateway {
    fun fetch(source: MediaSource.SourceHosted): FetchedMedia
}

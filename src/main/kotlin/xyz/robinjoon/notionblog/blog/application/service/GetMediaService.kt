package xyz.robinjoon.notionblog.blog.application.service

import org.springframework.transaction.annotation.Transactional
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.application.model.MediaLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetMediaUseCase
import xyz.robinjoon.notionblog.blog.application.port.output.persistence.MediaStoragePort
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey

@Transactional(readOnly = true)
class GetMediaService(
    private val mediaStorage: MediaStoragePort,
) : GetMediaUseCase {
    @Transactional(readOnly = true)
    override fun getPublic(key: MediaKey): MediaLookupResult = found(mediaStorage.findPublic(key))

    @Transactional(readOnly = true)
    override fun getForPreview(key: MediaKey): MediaLookupResult = found(mediaStorage.findForPreview(key))

    private fun found(media: MediaContent?): MediaLookupResult = media?.let(MediaLookupResult::Found) ?: MediaLookupResult.NotFound
}

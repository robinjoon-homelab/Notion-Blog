package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.application.model.MediaLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetMediaUseCase

@Controller
class MediaController(
    private val media: GetMediaUseCase,
) {
    @GetMapping("/media/{mediaKey}")
    fun publicMedia(
        @PathVariable mediaKey: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        val key = WebIds.mediaKey(mediaKey) ?: return notFound()
        return when (val result = media.getPublic(key)) {
            is MediaLookupResult.Found -> publicResponse(result.media, ifNoneMatch)
            MediaLookupResult.NotFound -> notFound()
        }
    }

    @GetMapping("/admin/media/{mediaKey}")
    fun previewMedia(
        @PathVariable mediaKey: String,
    ): ResponseEntity<ByteArray> {
        val key = WebIds.mediaKey(mediaKey) ?: return notFound()
        return when (val result = media.getForPreview(key)) {
            is MediaLookupResult.Found -> {
                body(
                    ResponseEntity.ok(),
                    result.media,
                ).cacheControl(CacheControl.noStore()).body(result.media.bytes)
            }

            MediaLookupResult.NotFound -> {
                notFound()
            }
        }
    }

    private fun publicResponse(
        content: MediaContent,
        ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        val etag = "\"${content.key.value}\""
        val builder = body(ResponseEntity.status(HttpStatus.OK), content).cacheControl(CacheControl.noCache()).eTag(etag)
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity
                .status(HttpStatus.NOT_MODIFIED)
                .cacheControl(CacheControl.noCache())
                .eTag(etag)
                .build()
        }
        return builder.body(content.bytes)
    }

    private fun matches(
        header: String,
        etag: String,
    ): Boolean = header.split(',').map(String::trim).any { it == etag || it == "*" }

    private fun body(
        builder: ResponseEntity.BodyBuilder,
        content: MediaContent,
    ): ResponseEntity.BodyBuilder =
        builder
            .contentType(MediaType.parseMediaType(content.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition(content.contentType))
            .header("X-Content-Type-Options", "nosniff")

    // 브라우저가 스크립트로 해석할 수 있는 형식은 내려받기로만 제공한다.
    private fun disposition(contentType: String): String = if (inlineTypes.any { contentType.startsWith(it) }) "inline" else "attachment"

    private fun notFound(): ResponseEntity<ByteArray> = ResponseEntity.status(HttpStatus.NOT_FOUND).build()

    private companion object {
        val inlineTypes = listOf("image/", "video/", "audio/", "application/pdf")
    }
}

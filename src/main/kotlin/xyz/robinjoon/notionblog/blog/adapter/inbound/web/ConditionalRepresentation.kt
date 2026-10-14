package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.CacheControl
import org.springframework.http.ETag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.ServletWebRequest
import java.security.MessageDigest
import java.util.HexFormat

// Shared response policy for generated public documents (RSS, sitemap, llms.txt): clients must revalidate, the
// validator is the SHA-256 of the transmitted bytes, and failures are never stored.
internal object ConditionalRepresentation {
    fun respond(
        request: ServletWebRequest,
        bytes: ByteArray,
        mediaType: MediaType,
    ): ResponseEntity<ByteArray> {
        val etag = "\"${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}\""
        val conditionalStatus = conditionalStatus(request, etag)
        if (conditionalStatus != null) {
            return ResponseEntity
                .status(conditionalStatus)
                .cacheControl(CacheControl.noCache())
                .eTag(etag)
                .build()
        }
        return ResponseEntity
            .ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noCache())
            .eTag(etag)
            .body(bytes)
    }

    fun unavailable(): ResponseEntity<ByteArray> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .build()

    private fun conditionalStatus(
        request: ServletWebRequest,
        etag: String,
    ): Int? {
        if (request.checkNotModified(etag)) {
            return request.response?.status ?: HttpStatus.NOT_MODIFIED.value()
        }
        return if (hasWildcardValidator(request)) HttpStatus.NOT_MODIFIED.value() else null
    }

    private fun hasWildcardValidator(request: ServletWebRequest): Boolean =
        request
            .getHeaderValues(HttpHeaders.IF_NONE_MATCH)
            .orEmpty()
            .any { value -> ETag.parse(value).any { it.isWildcard } }
}

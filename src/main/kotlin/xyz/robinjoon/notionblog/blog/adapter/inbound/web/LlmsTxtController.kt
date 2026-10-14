package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import xyz.robinjoon.notionblog.blog.application.model.PublicIndexLookupResult
import xyz.robinjoon.notionblog.blog.application.port.input.GetPublicIndexUseCase

@RestController
class LlmsTxtController(
    private val service: GetPublicIndexUseCase,
    private val renderer: LlmsTxtRenderer,
    private val urls: PublicBlogUrls,
) {
    @GetMapping("/llms.txt", produces = ["text/plain"])
    fun llmsTxt(request: ServletWebRequest): ResponseEntity<ByteArray> {
        if (urls.rootUrl == null) {
            return ConditionalRepresentation.unavailable()
        }
        val result = service.get()
        if (result !is PublicIndexLookupResult.Found) {
            return ConditionalRepresentation.unavailable()
        }
        return ConditionalRepresentation.respond(request, renderer.render(result.index, urls), textMediaType)
    }

    private companion object {
        val textMediaType = MediaType("text", "plain", Charsets.UTF_8)
    }
}

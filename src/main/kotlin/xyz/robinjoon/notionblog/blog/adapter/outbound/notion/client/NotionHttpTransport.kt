package xyz.robinjoon.notionblog.blog.adapter.outbound.notion.client

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriBuilder
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.time.Duration
import java.util.Locale

/** 토큰을 공식·루프백 origin으로만 보내는 Notion HTTP 전송이다. 엔드포인트 의미는 [NotionApiClient]가 맡는다. */
internal class NotionHttpTransport(
    baseUrl: String,
    token: String,
    requestTimeout: Duration,
) {
    private val restClient: RestClient
    private val failureTranslator = NotionFailureTranslator()

    init {
        val validatedBaseUrl = validateBaseUrl(baseUrl)
        require(token.isNotBlank()) { "Notion token must not be blank" }
        require(requestTimeout.isPositive) { "Notion request timeout must be positive" }

        val httpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(requestTimeout)
                .build()
        val requestFactory =
            JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(requestTimeout)
            }
        restClient =
            RestClient
                .builder()
                .baseUrl(validatedBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .defaultHeader(NOTION_VERSION_HEADER, SUPPORTED_API_VERSION)
                .build()
    }

    fun get(
        path: String,
        id: String,
    ): JsonNode =
        execute {
            restClient
                .get()
                .uri(path, id)
                .retrieve()
                .body(JsonNode::class.java)
        }

    fun get(uri: (UriBuilder) -> URI): JsonNode =
        execute {
            restClient
                .get()
                .uri(uri)
                .retrieve()
                .body(JsonNode::class.java)
        }

    fun post(
        path: String,
        id: String,
        body: Map<String, Any>,
    ): JsonNode =
        execute {
            restClient
                .post()
                .uri(path, id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        }

    fun post(
        uri: (UriBuilder) -> URI,
        body: Map<String, Any>,
    ): JsonNode =
        execute {
            restClient
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
        }

    private fun execute(request: () -> JsonNode?): JsonNode =
        try {
            request() ?: throw failureTranslator.invalidResponse()
        } catch (exception: RestClientResponseException) {
            throw failureTranslator.httpFailure(exception.statusCode.value(), exception)
        } catch (exception: ResourceAccessException) {
            if (isInterruption(exception)) Thread.currentThread().interrupt()
            throw failureTranslator.requestFailure(exception)
        } catch (exception: RestClientException) {
            throw failureTranslator.invalidResponse(exception)
        }

    /** 스레드 인터럽트는 소스 장애가 아니다. 일반 타임아웃(SocketTimeoutException은 InterruptedIOException)은 인터럽트로 보지 않는다. */
    private fun isInterruption(exception: ResourceAccessException): Boolean =
        Thread.currentThread().isInterrupted || generateSequence<Throwable>(exception) { it.cause }.any { it is InterruptedException }

    private fun validateBaseUrl(value: String): String {
        require(value.isNotBlank()) { "Notion base URL must not be blank" }
        val uri = parseBaseUri(value)
        require(uri.isAbsolute && uri.host != null) { "Notion base URL must be an absolute HTTP URL" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Notion base URL must not contain userinfo, a query, or a fragment"
        }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val isOfficialOrigin = scheme == "https" && host == OFFICIAL_HOST && (uri.port == -1 || uri.port == HTTPS_PORT)
        val isLoopbackOrigin = (scheme == "http" || scheme == "https") && host in loopbackHosts
        require(isOfficialOrigin || isLoopbackOrigin) {
            "Notion base URL must use the official HTTPS origin or a loopback test host"
        }
        return value.trim().trimEnd('/')
    }

    private fun parseBaseUri(value: String): URI =
        try {
            URI(value.trim())
        } catch (exception: URISyntaxException) {
            throw IllegalArgumentException("Notion base URL must be an absolute HTTP URL", exception)
        }

    private companion object {
        const val SUPPORTED_API_VERSION = "2026-03-11"
        const val NOTION_VERSION_HEADER = "Notion-Version"
        const val OFFICIAL_HOST = "api.notion.com"
        const val HTTPS_PORT = 443
        val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}

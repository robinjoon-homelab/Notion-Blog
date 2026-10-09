package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("notion")
data class NotionProperties
    // quality-exception: 기존 notion.* 환경 설정 9개를 평탄한 바인딩 계약으로 유지한다.
    @Suppress("LongParameterList")
    constructor(
        val token: String = "",
        val settingsDataSourceId: String = "",
        val sourceId: String = "notion",
        val apiVersion: String = SUPPORTED_API_VERSION,
        val baseUrl: String = "https://api.notion.com/v1",
        val requestTimeout: Duration = Duration.ofSeconds(10),
        val collectionTimeout: Duration = Duration.ofSeconds(30),
        val maxBlockDepth: Int = 32,
        val maxBlockCount: Int = 10_000,
    ) {
        init {
            require(token.isNotBlank()) { "notion.token must not be blank" }
            require(settingsDataSourceId.isNotBlank()) { "notion.settings-data-source-id must not be blank" }
            require(sourceId.isNotBlank()) { "notion.source-id must not be blank" }
            require(apiVersion == SUPPORTED_API_VERSION) { "notion.api-version must be $SUPPORTED_API_VERSION" }
            require(baseUrl.isNotBlank()) { "notion.base-url must not be blank" }
            require(requestTimeout.isPositive) { "notion.request-timeout must be positive" }
            require(collectionTimeout.isPositive) { "notion.collection-timeout must be positive" }
            require(maxBlockDepth > 0) { "notion.max-block-depth must be positive" }
            require(maxBlockCount > 0) { "notion.max-block-count must be positive" }
        }

        private companion object {
            const val SUPPORTED_API_VERSION = "2026-03-11"
        }
    }

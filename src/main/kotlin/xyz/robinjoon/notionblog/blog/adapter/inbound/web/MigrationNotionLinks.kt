package xyz.robinjoon.notionblog.blog.adapter.inbound.web

// 오류 화면에서도 원본 링크는 입력 주소를 그대로 쓰지 않고 Notion UUID로 다시 만든다.
internal object MigrationNotionLinks {
    fun source(value: String): String? {
        val trimmed = value.trim()
        val candidate = if (identifier.matches(trimmed)) trimmed else pathId(trimmed)
        return candidate?.let { "https://www.notion.so/${it.replace("-", "")}" }
    }

    private fun pathId(value: String): String? {
        val path = notionUrl.matchEntire(value)?.groupValues?.get(1) ?: return null
        return trailingIdentifier.find(path.trimEnd('/').substringAfterLast('/'))?.value
    }

    private const val UUID_PATTERN = "(?:[a-fA-F0-9]{32}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})"
    private val identifier = Regex(UUID_PATTERN)
    private val trailingIdentifier = Regex("$UUID_PATTERN$")
    private val notionUrl =
        Regex(
            "https://(?:notion\\.so|www\\.notion\\.so|app\\.notion\\.com|(?:[a-z0-9-]+\\.)+notion\\.site)(?::443)?/([^?#\\s]*)(?:\\?[^#\\s]*)?(?:#[^\\s]*)?",
            RegexOption.IGNORE_CASE,
        )
}

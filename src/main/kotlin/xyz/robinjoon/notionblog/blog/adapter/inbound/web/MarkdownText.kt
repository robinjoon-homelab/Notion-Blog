package xyz.robinjoon.notionblog.blog.adapter.inbound.web

// Serializes stored text as literal CommonMark text. Inline syntax characters are backslash-escaped everywhere so
// titles and summaries cannot add links, raw HTML, code spans, or emphasis; a leading block marker is escaped so text
// placed after a heading or quote marker stays a paragraph.
internal object MarkdownText {
    private const val INLINE_SYNTAX = "\\`*_[]<>&!~|"
    private val leadingBlockMarker = Regex("^(?:[#+=-]|\\d+(?=[.)]))")

    fun escape(value: String): String {
        val escaped =
            buildString {
                value.forEach { character ->
                    if (character in INLINE_SYNTAX) append('\\')
                    append(character)
                }
            }
        val marker = leadingBlockMarker.find(escaped) ?: return escaped
        val end = marker.range.last + 1
        return if (marker.value.first().isDigit()) {
            escaped.substring(0, end) + "\\" + escaped.substring(end)
        } else {
            "\\" + escaped
        }
    }
}

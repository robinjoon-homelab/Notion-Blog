package xyz.robinjoon.notionblog.blog.domain.presentation

data class PresentationTokens(
    val colorMode: PresentationColorMode = PresentationColorMode.SYSTEM,
    val contentWidth: PresentationContentWidth = PresentationContentWidth.STANDARD,
    val density: PresentationDensity = PresentationDensity.COMFORTABLE,
)

enum class PresentationColorMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class PresentationContentWidth {
    STANDARD,
    WIDE,
}

enum class PresentationDensity {
    COMFORTABLE,
    COMPACT,
}

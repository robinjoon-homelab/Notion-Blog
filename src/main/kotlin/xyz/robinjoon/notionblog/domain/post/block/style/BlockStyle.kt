package xyz.robinjoon.notionblog.domain.post.block.style

enum class ColorToken {
    DEFAULT,
    GRAY,
    BROWN,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    PURPLE,
    PINK,
    RED,
}

enum class Alignment {
    LEFT,
    CENTER,
    RIGHT,
}

@JvmInline
value class WidthToken(
    val ratio: Double,
) {
    init {
        require(ratio.isFinite() && ratio > 0.0 && ratio <= 1.0) { "width ratio must be greater than zero and at most one" }
    }
}

@JvmInline
value class StyleVariant(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "style variant must not be blank" }
    }
}

data class BlockStyle(
    val foreground: ColorToken? = null,
    val background: ColorToken? = null,
    val alignment: Alignment? = null,
    val width: WidthToken? = null,
    val variant: StyleVariant? = null,
) {
    companion object {
        val default = BlockStyle()
    }
}

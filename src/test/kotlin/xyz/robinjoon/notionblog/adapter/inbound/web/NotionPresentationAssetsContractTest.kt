package xyz.robinjoon.notionblog.adapter.inbound.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotionPresentationAssetsContractTest {
    @Test
    fun `ships the versioned notion stylesheet with semantic themes responsive layout and block coverage`() {
        val css = resourceText("static/presentation/notion/v1/notion.css")

        assertThat(css).contains(
            ":root",
            "--notion-page",
            "--notion-text",
            "--notion-surface",
            "--notion-border",
            "--notion-focus",
            "--notion-content-width",
            "--notion-font-sans",
            "--notion-media-max-width",
            ".notion-color-mode-light",
            ".notion-color-mode-dark",
            "@media (prefers-color-scheme: dark)",
            "@media (prefers-contrast: more)",
            "@media (prefers-reduced-motion: reduce)",
            ".notion-content-width-wide",
            ".notion-density-compact",
            ".notion-title",
            ".notion-heading-toggle",
            ".notion-columns",
            ".notion-column",
            ".notion-column-width-5",
            ".notion-column-width-100",
            "@media (max-width: 720px)",
            ".notion-tabs",
            "[role=tab]",
            "[role=tabpanel]",
            ".notion-table-wrapper",
            ".notion-table",
            ".notion-breadcrumb",
            ".notion-toc",
            ".notion-figure",
            ".notion-caption",
            ".notion-bookmark",
            ".notion-link-preview",
            ".notion-embed",
            ".notion-document-reference",
            ".notion-synced-block",
            ".notion-template",
            ".notion-meeting-notes",
            ".notion-unsupported",
            ".notion-sr-only",
            "@media print",
        )

        val palette = listOf("default", "gray", "brown", "orange", "yellow", "green", "blue", "purple", "pink", "red")
        palette.forEach { color ->
            assertThat(css).contains(".notion-color-$color", ".notion-background-$color")
        }
        assertThat(css).doesNotContain("--notion-column-count", "--notion-column-ratio")
    }

    @Test
    fun `ships a self contained progressive tabs enhancement without dynamic code execution or network access`() {
        val script = resourceText("static/presentation/notion/v1/notion.js")

        assertThat(script).contains("DOMContentLoaded", "ArrowRight", "ArrowLeft", "Home", "End", "Enter", " ")
        assertThat(script).contains("aria-selected", "aria-controls", "hidden", "role=\"tablist\"")
        assertThat(script).doesNotContain("eval(", "Function(", "fetch(", "XMLHttpRequest", "<script", "onkeydown=")
    }

    @Test
    fun `ships enhancement overrides for todo markers icons full canvas and system dark palettes`() {
        val css = resourceText("static/presentation/notion/enhancements/v1/notion-enhancements.css")

        assertThat(css).contains(
            "html",
            "body",
            "min-block-size: 100%",
            ".notion-page",
            "min-block-size: 100vh",
            "body:has(> .notion-page.notion-color-mode-dark)",
            ".notion-list-todo",
            "list-style: none",
            ".notion-callout-icon",
            ".notion-tab-icon",
            ".notion-native-icon",
            ".notion-math",
            "background: transparent",
            "@media (prefers-color-scheme: dark)",
            ".notion-page.notion-color-mode-system .notion-background-blue",
            "background-color: #293b4a",
            ".notion-page.notion-color-mode-system .notion-color-red",
            "color: #ff8b8b",
        )
        assertThat(css).doesNotContain("javascript:", "url(http", "expression(")
    }

    @Test
    fun `ships a no network math enhancement that preserves source on missing katex or render failure`() {
        val script = resourceText("static/presentation/notion/math/v1/notion-math.js")

        assertThat(script).contains(
            "DOMContentLoaded",
            "window.katex",
            "katex.render",
            "data-expression",
            "displayMode",
            "throwOnError: false",
            "trust: false",
            "output: \"mathml\"",
            "textContent = source",
        )
        assertThat(script).doesNotContain(
            "htmlAndMathml",
            "eval(",
            "Function(",
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "sendBeacon",
            "innerHTML",
            "<script",
        )
    }

    @Test
    fun `ships database table styling with bounded horizontal scrolling focus and theme tokens`() {
        val css = resourceText("static/presentation/notion/database/v1/notion-database.css")

        assertThat(css).contains(
            ".notion-database",
            ".notion-data-table-wrapper",
            "max-inline-size: 100%",
            "overflow-x: auto",
            ".notion-data-table-wrapper:focus-visible",
            "var(--notion-focus)",
            ".notion-data-table caption",
            ".notion-data-table-cell",
            "overflow-wrap: anywhere",
            ".notion-data-table-empty",
            "var(--notion-text-muted)",
            "var(--notion-border)",
            "var(--notion-page)",
            "@media (max-width: 720px)",
        )
        assertThat(css).doesNotContain("javascript:", "url(http", "expression(")
    }

    @Test
    fun `ships three database layouts with bounded widths mobile cards and measured sticky columns`() {
        val css = resourceText("static/presentation/notion/database/v2/notion-database.css")
        val script = resourceText("static/presentation/notion/database/v2/notion-database.js")

        assertThat(css).contains(
            ".notion-data-list",
            ".notion-data-gallery",
            ".notion-data-card",
            ".notion-data-wrap",
            ".notion-data-nowrap",
            ".notion-data-table-no-vertical-lines",
            "position: sticky",
            "overflow-x: auto",
            "overflow-wrap: anywhere",
            "var(--notion-page)",
            "var(--notion-border)",
            "var(--notion-focus)",
            "@media (max-width: 720px)",
            "object-fit: contain",
            "object-fit: cover",
            "grid-template-columns:",
        )
        (80..640 step 40).forEach { width -> assertThat(css).contains(".notion-data-width-$width") }
        assertThat(
            script,
        ).contains("getBoundingClientRect", "ResizeObserver", "resize", "requestAnimationFrame", "data-frozen-columns", "style.left")
        assertThat(
            script,
        ).doesNotContain(
            "innerHTML",
            "eval(",
            "Function(",
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "sendBeacon",
            "cssText",
            "setAttribute('style'",
        )
        assertThat(css).doesNotContain("javascript:", "url(http", "expression(")
    }

    @Test
    fun `ships Notion-like database sections without repeated visible view headings or generic reference card chrome`() {
        val css = resourceText("static/presentation/notion/database/v3/notion-database.css")

        assertThat(css).contains(
            ".notion-database",
            ".notion-database-title",
            "border: 0",
            "box-shadow: none",
            "background: transparent",
            ".notion-data-view-title.notion-sr-only",
            ".notion-data-table caption.notion-sr-only",
            "@media (max-width: 720px)",
        )
        assertThat(css).doesNotContain("javascript:", "url(http", "expression(")
    }

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.classLoader.getResource(path)) {
            "Missing presentation asset: $path"
        }.readText()
}

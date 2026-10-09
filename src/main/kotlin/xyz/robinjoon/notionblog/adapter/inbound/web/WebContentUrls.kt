package xyz.robinjoon.notionblog.adapter.inbound.web

import xyz.robinjoon.notionblog.adapter.inbound.web.view.EmbedProviderView
import java.net.URI

internal object WebContentUrls {
    private val safeSchemes = setOf("http", "https")
    private val embedIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val vimeoIdPattern = Regex("[0-9]{1,20}")

    fun external(url: URI?): String? = url?.takeIf(::isSafe)?.toASCIIString()

    private fun isSafe(url: URI): Boolean = url.isAbsolute && url.host != null && url.scheme?.lowercase() in safeSchemes

    fun embed(url: URI): CanonicalEmbed? {
        if (!isSafe(url)) return null
        val segments =
            url.rawPath
                .orEmpty()
                .split('/')
                .filter(String::isNotBlank)
        return when (url.host.lowercase()) {
            "youtube.com", "www.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com" -> youtube(youtubeId(url, segments))
            "youtu.be", "www.youtu.be" -> youtube(segments.firstOrNull())
            "vimeo.com", "www.vimeo.com", "player.vimeo.com" -> vimeo(segments)
            else -> null
        }
    }

    private fun youtubeId(
        url: URI,
        segments: List<String>,
    ): String? =
        when (segments.firstOrNull()) {
            "embed", "shorts" -> segments.getOrNull(1)
            "watch" -> url.rawQuery.queryParameter("v")
            else -> null
        }

    private fun youtube(videoId: String?): CanonicalEmbed? =
        videoId?.takeIf(embedIdPattern::matches)?.let {
            CanonicalEmbed(EmbedProviderView.YOUTUBE, "https://www.youtube-nocookie.com/embed/$it")
        }

    private fun vimeo(segments: List<String>): CanonicalEmbed? {
        val videoId = if (segments.firstOrNull() == "video") segments.getOrNull(1) else segments.firstOrNull()
        return videoId?.takeIf(vimeoIdPattern::matches)?.let {
            CanonicalEmbed(EmbedProviderView.VIMEO, "https://player.vimeo.com/video/$it")
        }
    }

    private fun String?.queryParameter(name: String): String? =
        this?.split('&')?.firstNotNullOfOrNull { entry ->
            val parts = entry.split('=', limit = 2)
            parts.getOrNull(1)?.takeIf { parts.firstOrNull() == name }
        }

    data class CanonicalEmbed(
        val provider: EmbedProviderView,
        val url: String,
    )
}

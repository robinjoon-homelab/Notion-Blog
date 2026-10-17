package xyz.robinjoon.notionblog.blog.adapter.outbound.media

import java.util.Locale

// 저장할 콘텐츠 타입은 응답 헤더의 type/subtype이 형식에 맞고 허용 목록에 있을 때만 그대로 쓰고, 나머지는
// application/octet-stream으로 낮춘다. 웹은 image·video·audio·PDF를 inline으로 보내므로 SVG·HTML·XML·스크립트처럼
// 문서로 실행될 수 있는 타입은 목록에 두지 않는다. 낮춘 파일은 첨부 다운로드로만 제공된다.
internal object MediaContentTypes {
    const val FALLBACK = "application/octet-stream"
    private val pattern = Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$")

    private val retained =
        setOf(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "image/avif",
            "video/mp4",
            "video/webm",
            "video/ogg",
            "video/quicktime",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/wav",
            "audio/webm",
            "audio/flac",
            "audio/aac",
            "application/pdf",
            "text/plain",
            "text/csv",
            "application/zip",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        )

    fun stored(header: String?): String {
        val type = header?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return if (type != null && pattern.matches(type) && type in retained) type else FALLBACK
    }
}

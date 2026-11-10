package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.ConfigurationProperties

internal const val BCRYPT_PREFIX = "{bcrypt}"

private const val BCRYPT_MAX_BYTES = 72

// 둘 중 하나라도 비어 있거나 쓸 수 없는 값이면 관리 화면은 닫힌 상태로 남고 공개 읽기만 동작한다.
@ConfigurationProperties("app.admin")
class AdminSecurityProperties(
    val username: String = "",
    val password: String = "",
) {
    val enabled: Boolean
        get() = closedReason == null

    // 사용자 저장소와 체인이 함께 닫히도록 여기서 정한다. 로그에 남으므로 값이나 길이를 담지 않는다.
    val closedReason: String?
        get() =
            when {
                username.isBlank() || password.isBlank() -> "ADMIN_USERNAME or ADMIN_PASSWORD is not set"
                isPlaintextTooLong -> "ADMIN_PASSWORD is too long for BCrypt; store a {bcrypt} hash instead"
                else -> null
            }

    // BCrypt는 더 긴 입력을 예외로 거부하며, 그 예외는 애플리케이션 시작 자체를 멈춘다.
    private val isPlaintextTooLong: Boolean
        get() = !password.startsWith(BCRYPT_PREFIX) && password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_BYTES
}

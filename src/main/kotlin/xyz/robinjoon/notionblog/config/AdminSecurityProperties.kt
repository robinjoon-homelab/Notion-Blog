package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 둘 중 하나라도 비어 있으면 관리 화면은 닫힌 상태로 남고 공개 읽기만 동작한다.
@ConfigurationProperties("blog.admin")
class AdminSecurityProperties(
    val username: String = "",
    val password: String = "",
) {
    val enabled: Boolean
        get() = username.isNotBlank() && password.isNotBlank()

    // 비밀번호가 로그나 예외 메시지에 실리지 않도록 값을 출력하지 않는다.
    override fun toString(): String = "AdminSecurityProperties(enabled=$enabled)"
}

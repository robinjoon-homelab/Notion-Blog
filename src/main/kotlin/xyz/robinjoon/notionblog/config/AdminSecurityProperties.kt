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
}

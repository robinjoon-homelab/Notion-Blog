package xyz.robinjoon.notionblog.config

import org.springframework.boot.context.properties.ConfigurationProperties

// OIDC 값이 하나라도 비어 있으면 관리 화면은 닫힌 상태로 남고 공개 읽기만 동작한다.
@ConfigurationProperties("blog.admin")
data class AdminSecurityProperties(
    val oidc: Oidc = Oidc(),
) {
    data class Oidc(
        val issuerUri: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val allowedSubject: String = "",
    ) {
        val enabled: Boolean
            get() = listOf(issuerUri, clientId, clientSecret, allowedSubject).all(String::isNotBlank)

        fun allows(
            issuer: String?,
            subject: String?,
        ): Boolean = enabled && issuer == issuerUri && subject == allowedSubject
    }

    val enabled: Boolean
        get() = oidc.enabled
}

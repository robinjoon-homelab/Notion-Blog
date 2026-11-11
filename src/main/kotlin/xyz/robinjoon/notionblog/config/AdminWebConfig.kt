package xyz.robinjoon.notionblog.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import xyz.robinjoon.notionblog.adapter.inbound.web.WebSecurityHeadersFilter

@Configuration(proxyBeanMethods = false)
internal class AdminWebConfig {
    // 보안 체인보다 먼저 실행해 체인이 만드는 302·403 응답에도 헤더가 붙게 한다.
    @Bean
    fun webSecurityHeadersFilterRegistration(): FilterRegistrationBean<WebSecurityHeadersFilter> =
        FilterRegistrationBean(WebSecurityHeadersFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }
}

package xyz.robinjoon.notionblog.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// 표현 자산은 버전별로 SRI 해시를 고정하므로 UI 린트·포맷 대상인 static/ 밖에 두고 같은 URL로 제공한다.
// 캐시 설정과 리소스 체인을 지정하지 않아 기본 정적 리소스 핸들러와 응답 헤더가 같다.
@Configuration(proxyBeanMethods = false)
internal class PresentationResourceConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/presentation/**").addResourceLocations("classpath:/presentation/")
    }
}

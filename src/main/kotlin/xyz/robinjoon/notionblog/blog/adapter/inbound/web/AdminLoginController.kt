package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// 로그인 화면과 /admin 진입만 제공한다. 제출(POST /admin/login)과 로그아웃은 보안 필터가 처리한다.
// 아키텍처 테스트가 경로를 어노테이션 컨트롤러로만 선언하게 하므로 뷰 컨트롤러 등록 대신 컨트롤러 메서드로 둔다.
@Controller
internal class AdminLoginController {
    @GetMapping("/admin/login")
    fun login(): String = "app/login"

    @GetMapping("/admin")
    fun home(): String = "redirect:/admin/posts"
}

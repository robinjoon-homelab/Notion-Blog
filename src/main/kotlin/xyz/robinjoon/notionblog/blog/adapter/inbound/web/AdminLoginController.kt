package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// 로그인 화면만 제공한다. 제출(POST /admin/login)과 로그아웃은 보안 필터가 처리한다.
@Controller
internal class AdminLoginController {
    @GetMapping("/admin/login")
    fun login(): String = "admin/login"
}

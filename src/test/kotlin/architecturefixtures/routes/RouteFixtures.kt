package architecturefixtures.routes

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.service.annotation.GetExchange

// 블로그 경로 목록 검사용 컨트롤러다. 컴포넌트 스캔이 등록하지 않도록 애플리케이션 패키지 밖에 둔다.
// DeclaredRoutes, PostPageRoute, PublishRoute가 함께 운영 경로를 정확히 선언하므로
// 각 사례는 매핑 하나만 바꾸거나 더한다.

private const val CONSTANT_ROUTE = "/constant"
private const val POST_PAGE_ROUTE = "/posts/{postId}"

@Controller
class DeclaredRoutes {
    @GetMapping("/")
    fun home() = Unit

    @GetMapping("/feed.xml", produces = ["application/rss+xml"])
    fun feed() = Unit

    @GetMapping("/media/{mediaKey}")
    fun media() = Unit

    @GetMapping("/admin/login")
    fun login() = Unit

    @GetMapping("/admin/posts")
    fun posts() = Unit

    @GetMapping("/admin/posts/{postId}")
    fun preview() = Unit

    @PostMapping("/admin/posts/{postId}/refresh")
    fun refresh() = Unit

    @PostMapping("/admin/posts/{postId}/unpublish")
    fun unpublish() = Unit

    @PostMapping("/admin/posts/{postId}/archive")
    fun archive() = Unit

    @PostMapping("/admin/posts/{postId}/restore")
    fun restore() = Unit

    @GetMapping("/admin/media/{mediaKey}")
    fun adminMedia() = Unit
}

@Controller
class PostPageRoute {
    @GetMapping("/posts/{postId}")
    fun post() = Unit
}

@Controller
class PublishRoute {
    @PostMapping("/admin/posts/{postId}/publish")
    fun publish() = Unit
}

@Controller
class PrivateRoute {
    @GetMapping("/private")
    fun hidden() = Unit
}

@Controller
class DuplicateRootRoute {
    @GetMapping("/")
    fun home() = Unit
}

@Controller
class ExtraRoute {
    @GetMapping("/extra")
    fun extra() = Unit
}

@Controller
class PublishAsGetRoute {
    @GetMapping("/admin/posts/{postId}/publish")
    fun publish() = Unit
}

@Controller
class PublishForAnyMethodRoute {
    @RequestMapping("/admin/posts/{postId}/publish")
    fun publish() = Unit
}

@Controller
class PostPageAsDeleteRoute {
    @DeleteMapping("/posts/{postId}")
    fun post() = Unit
}

@Controller
class CreatePostRoute {
    @PostMapping("/admin/posts")
    fun create() = Unit
}

@Controller
class PatchRoute {
    @PatchMapping("/admin/posts/{postId}")
    fun edit() = Unit
}

@Controller
class ValueAttributeRoute {
    @GetMapping(value = ["/value"])
    fun hidden() = Unit
}

@Controller
class PathAttributeRoute {
    @GetMapping(path = ["/path"])
    fun hidden() = Unit
}

@Controller
class ConstantRoute {
    @GetMapping(CONSTANT_ROUTE)
    fun hidden() = Unit
}

@Controller
class PathArrayRoute {
    @GetMapping("/first", "/second")
    fun hidden() = Unit
}

@Controller
@RequestMapping("/prefix")
class TypePrefixedRoute {
    @GetMapping("/child")
    fun hidden() = Unit
}

@Controller
class ExchangeRoute {
    @GetExchange("/exchange")
    fun hidden() = Unit
}

interface InheritedMapping {
    @GetMapping("/inherited")
    fun hidden()
}

@Controller
class InheritedRoute : InheritedMapping {
    override fun hidden() = Unit
}

// 선언된 경로를 이름 있는 배열 속성, 상수, 타입 수준 접두사로 적은 형태다.
@Controller
class NamedConstantPostPageRoute {
    @GetMapping(path = [POST_PAGE_ROUTE])
    fun post() = Unit
}

@Controller
@RequestMapping("/admin/posts/{postId}")
class TypePrefixedPublishRoute {
    @RequestMapping(value = ["/publish"], method = [RequestMethod.POST])
    fun publish() = Unit
}

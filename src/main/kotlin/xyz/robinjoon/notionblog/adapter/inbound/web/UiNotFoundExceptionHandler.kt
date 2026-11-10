package xyz.robinjoon.notionblog.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.resource.NoResourceFoundException

private val log = LoggerFactory.getLogger(UiNotFoundExceptionHandler::class.java)

// produces로 거절할 수 없다. text/html은 */*와 호환되므로 와일드카드 클라이언트도 HTML을 받게 된다.
// 등록된 경로의 404(블로그 글 없음, 형식이 잘못된 글 ID)는 각 컨트롤러와 WebExceptionHandler가 그대로 처리한다.
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class UiNotFoundExceptionHandler {
    @ExceptionHandler(NoResourceFoundException::class)
    fun handle(
        exception: NoResourceFoundException,
        request: HttpServletRequest,
    ): Any =
        if (acceptsHtmlExplicitly(request)) {
            ModelAndView("error/404", HttpStatus.NOT_FOUND)
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.body)
        }

    // 이 핸들러를 벗어난 예외는 요청마다 WARN 스택을 남기고, 어떤 클라이언트든 잘못된 헤더를 보낼 수 있다.
    private fun acceptsHtmlExplicitly(request: HttpServletRequest): Boolean =
        try {
            request.getHeaders("Accept").toList().any { header ->
                MediaType.parseMediaTypes(header).any { it.equalsTypeAndSubtype(MediaType.TEXT_HTML) }
            }
        } catch (exception: InvalidMediaTypeException) {
            log.debug("Treating a malformed Accept header as non-HTML", exception)
            false
        }
}

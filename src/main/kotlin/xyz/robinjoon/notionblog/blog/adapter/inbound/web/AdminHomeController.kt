package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminHomeView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.ChoiceView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.port.input.HomeConfigurationUseCase
import xyz.robinjoon.notionblog.blog.domain.home.HomeConfiguration
import xyz.robinjoon.notionblog.blog.domain.home.HomeLayout
import xyz.robinjoon.notionblog.blog.domain.home.HomePostDetails
import xyz.robinjoon.notionblog.blog.domain.home.HomePostOrder

// 공개 홈 표시 설정. 명시적으로 저장하기 전까지 공개 화면은 바뀌지 않는다.
@Controller
internal class AdminHomeController(
    private val homeConfigurations: HomeConfigurationUseCase,
) {
    @GetMapping("/admin/home")
    fun edit(model: Model): ModelAndView =
        ModelAndView("admin/home").addObject("view", editorView(homeConfigurations.get(), model.notice()))

    @PostMapping("/admin/home")
    fun save(
        form: AdminHomeForm,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val configuration = form.toConfiguration() ?: return redirect(HOME_PATH, attributes, "입력한 값을 확인하세요. 설정을 저장하지 않았습니다.", true)
        homeConfigurations.update(configuration)
        return redirect(HOME_PATH, attributes, "홈 화면 설정을 저장했습니다. 공개 홈에 바로 반영됩니다.")
    }

    private fun editorView(
        configuration: HomeConfiguration,
        notice: NoticeView?,
    ): AdminHomeView =
        AdminHomeView(
            layouts = HomeLayout.entries.map { choice(it.name, layoutText.getValue(it), it == configuration.layout) },
            orders = HomePostOrder.entries.map { choice(it.name, orderText.getValue(it), it == configuration.order) },
            pageSize = configuration.pageSize,
            details = configuration.details,
            notice = notice,
        )

    private fun choice(
        value: String,
        text: Pair<String, String>,
        selected: Boolean,
    ): ChoiceView = ChoiceView(value, text.first, text.second, selected)

    private companion object {
        const val HOME_PATH = "/admin/home"
        val layoutText =
            mapOf(
                HomeLayout.LIST to ("목록" to "제목과 요약을 한 줄씩 보여 줍니다."),
                HomeLayout.THUMBNAIL_LIST to ("썸네일 목록" to "왼쪽에 썸네일을 두고 목록으로 보여 줍니다."),
                HomeLayout.CARDS to ("카드" to "썸네일이 큰 카드를 격자로 보여 줍니다."),
            )
        val orderText =
            mapOf(
                HomePostOrder.NEWEST_FIRST to ("최신 글 먼저" to "처음 게시한 시각이 늦은 글부터 보여 줍니다."),
                HomePostOrder.OLDEST_FIRST to ("오래된 글 먼저" to "처음 게시한 시각이 이른 글부터 보여 줍니다."),
            )
    }
}

// 체크박스는 체크하지 않으면 값이 전송되지 않으므로 생성자 기본값을 false로 두고 불변 값으로 바인딩한다.
internal data class AdminHomeForm(
    val layout: String = "",
    val order: String = "",
    val pageSize: String = "",
    val showSummary: Boolean = false,
    val showDate: Boolean = false,
    val showCategories: Boolean = false,
) {
    // 알 수 없는 값이나 범위를 벗어난 쪽 크기는 null로 돌려 저장하지 않는다.
    fun toConfiguration(): HomeConfiguration? {
        val selectedLayout = HomeLayout.entries.firstOrNull { it.name == layout }
        val selectedOrder = HomePostOrder.entries.firstOrNull { it.name == order }
        val size = pageSize.trim().toIntOrNull()?.takeIf { it in pageSizeRange }
        if (selectedLayout == null || selectedOrder == null || size == null) return null
        return HomeConfiguration.create(selectedLayout, selectedOrder, size, HomePostDetails(showSummary, showDate, showCategories))
    }

    private companion object {
        val pageSizeRange = 1..50
    }
}

package xyz.robinjoon.notionblog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.adapter.inbound.web.view.PostPageView
import xyz.robinjoon.notionblog.application.model.BlogPage
import xyz.robinjoon.notionblog.application.model.BlogPageLookupResult
import xyz.robinjoon.notionblog.application.port.input.GetBlogPageUseCase
import xyz.robinjoon.notionblog.domain.post.PostId
import java.util.UUID

class BlogControllerTest {
    private val service = mockk<GetBlogPageUseCase>()
    private val assembler = mockk<PostPageViewAssembler>()
    private val page = mockk<BlogPage>()
    private val viewModel = mockk<PostPageView>()
    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(BlogController(service, assembler))
            .setControllerAdvice(WebExceptionHandler())
            .build()

    @Test
    fun `root found is rendered as post view with page model`() {
        every { service.getRoot() } returns BlogPageLookupResult.Found(page)
        every { assembler.assemble(page) } returns viewModel

        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk)
            .andExpect(view().name("blog/post"))
            .andExpect(model().attribute("page", viewModel))
    }

    @Test
    fun `post found is rendered as post view with page model`() {
        val postId = PostId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        every { service.get(postId) } returns BlogPageLookupResult.Found(page)
        every { assembler.assemble(page) } returns viewModel

        mockMvc
            .perform(get("/posts/${postId.value}"))
            .andExpect(status().isOk)
            .andExpect(view().name("blog/post"))
            .andExpect(model().attribute("page", viewModel))
    }

    @Test
    fun `not found and unavailable results map to dedicated error views`() {
        every { service.getRoot() } returns BlogPageLookupResult.NotFound
        mockMvc
            .perform(get("/"))
            .andExpect(status().isNotFound)
            .andExpect(view().name("error/blog-not-found"))

        every { service.getRoot() } returns BlogPageLookupResult.ContentUnavailable
        mockMvc
            .perform(get("/"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(view().name("error/blog-unavailable"))
    }

    @Test
    fun `malformed post id is not found without invoking service`() {
        mockMvc
            .perform(get("/posts/not-a-uuid"))
            .andExpect(status().isNotFound)
            .andExpect(view().name("error/blog-not-found"))

        verify(exactly = 0) { service.get(any<PostId>()) }
    }

    @Test
    fun `unsupported legacy route is not found without invoking service`() {
        mockMvc
            .perform(get("/notion/00000000-0000-0000-0000-000000000001"))
            .andExpect(status().isNotFound)

        verify(exactly = 0) { service.getRoot() }
        verify(exactly = 0) { service.get(any<PostId>()) }
    }
}

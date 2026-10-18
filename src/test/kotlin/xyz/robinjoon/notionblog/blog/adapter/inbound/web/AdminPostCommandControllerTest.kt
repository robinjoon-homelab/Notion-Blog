package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.PublishPostCommand
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.application.port.input.PostPublicationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.RefreshPostUseCase
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

class AdminPostCommandControllerTest {
    private val refreshes = mockk<RefreshPostUseCase>()
    private val publications = mockk<PostPublicationUseCase>()
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(AdminPostCommandController(refreshes, publications)).build()
    private val postId = WebFixtures.postId
    private val revisionId = WebFixtures.revisionId
    private val command = PublishPostCommand(postId, revisionId, 7)
    private val detail = "/admin/posts/${postId.value}"

    @Test
    fun `refresh returns to the preview and reports whether anything changed`() {
        every { refreshes.refresh(postId) } returns RefreshPostResult.Updated(revisionId)
        assertThat(notice("/admin/posts/${postId.value}/refresh").text).contains("발행 확인에서")

        every { refreshes.refresh(postId) } returns RefreshPostResult.Unchanged
        assertThat(notice("/admin/posts/${postId.value}/refresh").text).contains("바뀐 내용이 없습니다")

        every { refreshes.refresh(postId) } returns RefreshPostResult.Failed(SyncFailureKind.RETRYABLE_SOURCE)
        assertThat(notice("/admin/posts/${postId.value}/refresh").error).isTrue()
    }

    @Test
    fun `refresh of an unknown post is not found`() {
        every { refreshes.refresh(postId) } returns RefreshPostResult.NotFound

        mockMvc.perform(post("/admin/posts/${postId.value}/refresh")).andExpect(status().isNotFound)
        mockMvc.perform(post("/admin/posts/nope/refresh")).andExpect(status().isNotFound)
    }

    @Test
    fun `publish posts the reviewed revision and returns to the detail on success`() {
        every { publications.publish(command) } returns PublishPostResult.Published
        val published = publish()
        assertThat(published.error).isFalse()
        assertThat(published.text).contains("발행했습니다")

        every { publications.publish(command) } returns PublishPostResult.AlreadyPublished
        assertThat(publish().text).contains("이미 발행된 내용")

        every { publications.publish(command) } returns PublishPostResult.Archived
        val archived = publish()
        assertThat(archived.error).isTrue()
        assertThat(archived.text).contains("보관을 해제")
    }

    @Test
    fun `a stale or unready publish goes back to the review so the new content is checked again`() {
        every { publications.publish(command) } returns PublishPostResult.StaleRevision
        val stale = notice("$detail/publish", revisionId.value.toString(), "7", "$detail/review")
        assertThat(stale.error).isTrue()
        assertThat(stale.text).contains("발행하지 않았습니다", "다시 확인")

        every { publications.publish(command) } returns PublishPostResult.MediaNotReady
        assertThat(notice("$detail/publish", revisionId.value.toString(), "7", "$detail/review").error).isTrue()
    }

    @Test
    fun `publish needs a well formed revision and a known post`() {
        every { publications.publish(command) } returns PublishPostResult.NotFound

        mockMvc.perform(post("$detail/publish").param("revisionId", "bad").param("generation", "7")).andExpect(status().isBadRequest)
        mockMvc
            .perform(post("$detail/publish").param("revisionId", revisionId.value.toString()).param("generation", "7"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `unpublish succeeds idempotently and reports unknown posts`() {
        every { publications.unpublish(postId) } returns UnpublishPostResult.Unpublished
        assertThat(notice("$detail/unpublish").text).contains("비공개로 전환했습니다")

        every { publications.unpublish(postId) } returns UnpublishPostResult.AlreadyUnpublished
        assertThat(notice("$detail/unpublish").text).contains("이미")

        every { publications.unpublish(postId) } returns UnpublishPostResult.NotFound
        mockMvc.perform(post("$detail/unpublish")).andExpect(status().isNotFound)
    }

    @Test
    fun `archive and restore report their outcome and unknown posts`() {
        every { publications.archive(postId) } returns ArchivePostResult.Archived
        assertThat(notice("$detail/archive").error).isFalse()

        every { publications.restore(postId) } returns RestorePostResult.NotArchived
        assertThat(notice("$detail/restore").text).contains("보관된 글이 아닙니다")

        every { publications.restore(postId) } returns RestorePostResult.NotFound
        mockMvc.perform(post("$detail/restore")).andExpect(status().isNotFound)
    }

    private fun publish(): NoticeView = notice("$detail/publish", revisionId.value.toString(), "7")

    private fun notice(
        path: String,
        revision: String? = null,
        generation: String? = null,
        redirect: String = detail,
    ): NoticeView {
        val request = post(path)
        if (revision != null) request.param("revisionId", revision).param("generation", checkNotNull(generation))
        val result =
            mockMvc
                .perform(request)
                .andExpect(status().isSeeOther)
                .andExpect(redirectedUrl(redirect))
                .andReturn()
        return checkNotNull(result.flashMap["notice"]) as NoticeView
    }
}

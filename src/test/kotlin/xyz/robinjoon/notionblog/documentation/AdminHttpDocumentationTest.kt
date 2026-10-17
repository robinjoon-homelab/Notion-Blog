package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminFixtures
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminLoginController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminPostCommandController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminPostController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.MediaController
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.PostPageViewAssembler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebExceptionHandler
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebFixtures
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.application.model.AdminPostListQuery
import xyz.robinjoon.notionblog.blog.application.model.AdminPostLookupResult
import xyz.robinjoon.notionblog.blog.application.model.AdminPostStatus
import xyz.robinjoon.notionblog.blog.application.model.ArchivePostResult
import xyz.robinjoon.notionblog.blog.application.model.MediaContent
import xyz.robinjoon.notionblog.blog.application.model.MediaLookupResult
import xyz.robinjoon.notionblog.blog.application.model.PageQuery
import xyz.robinjoon.notionblog.blog.application.model.PreviewVersion
import xyz.robinjoon.notionblog.blog.application.model.PublishPostResult
import xyz.robinjoon.notionblog.blog.application.model.RefreshPostResult
import xyz.robinjoon.notionblog.blog.application.model.RestorePostResult
import xyz.robinjoon.notionblog.blog.application.model.UnpublishPostResult
import xyz.robinjoon.notionblog.blog.application.port.input.AdminPostQueryUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.GetMediaUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.PostPublicationUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.RefreshPostUseCase
import xyz.robinjoon.notionblog.blog.domain.post.block.media.MediaKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// 관리·미디어 경로를 실제 컨트롤러, 화면 조립, Thymeleaf 템플릿으로 문서화한다. 입력 포트는 엄격한 목이다.
// 인증과 CSRF는 보안 필터 체인의 책임이고 보안 설정 테스트가 다루므로, 이 테스트는 요청이 통과한 뒤
// 컨트롤러가 답하는 내용만 문서화한다.
class AdminHttpDocumentationTest {
    private val restDocumentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val queries = mockk<AdminPostQueryUseCase>()
    private val refreshes = mockk<RefreshPostUseCase>()
    private val publications = mockk<PostPublicationUseCase>()
    private val media = mockk<GetMediaUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T05:20:00Z"), ZoneOffset.UTC)
    private val postId = WebFixtures.postId
    private val mediaKey = MediaKey("a".repeat(KEY_LENGTH))
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AdminLoginController(),
                AdminPostController(queries, AdminViewAssembler(clock, PostPageViewAssembler(clock))),
                AdminPostCommandController(refreshes, publications),
                MediaController(media),
            ).setControllerAdvice(WebExceptionHandler())
            .setViewResolvers(thymeleafViewResolver())
            .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
            .apply<StandaloneMockMvcBuilder>(
                documentationConfiguration(restDocumentation)
                    .uris()
                    .withScheme("https")
                    .withHost("blog.example")
                    .withPort(HTTPS_PORT),
            ).build()

    @BeforeEach
    fun startDocumentation(testInfo: TestInfo) {
        restDocumentation.beforeTest(javaClass, testInfo.testMethod.orElseThrow().name)
    }

    @AfterEach
    fun finishDocumentation() {
        restDocumentation.afterTest()
    }

    @Test
    fun `login page documents the form and the generic failure notice`() {
        mvc
            .perform(get("/admin/login"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name=\"password\"")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andDo(document("admin-login"))
        mvc
            .perform(get("/admin/login").param("error", ""))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("아이디 또는 비밀번호가 올바르지 않습니다.")))
            .andDo(document("admin-login-failed"))
    }

    @Test
    fun `admin list documents the filtered post table`() {
        every { queries.list(AdminPostListQuery(AdminPostStatus.PUBLISHED, "kotlin", PageQuery(1, PAGE_SIZE))) } returns
            AdminFixtures.list(listOf(AdminFixtures.summary("Kotlin tips", AdminPostStatus.PUBLISHED)))

        mvc
            .perform(get("/admin/posts").param("status", "published").param("q", "kotlin"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Kotlin tips")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Security-Policy", containsString("form-action 'self'")))
            .andDo(document("admin-posts-list"))
    }

    @Test
    fun `preview documents the draft with its publish action`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns
            AdminPostLookupResult.Found(AdminFixtures.preview(AdminPostStatus.DRAFT, AdminFixtures.actions()))

        mvc
            .perform(get("/admin/posts/{postId}", postId.value.toString()))
            .andExpect(status().isOk)
            .andDo(document("admin-post-preview", postIdParameter()))
    }

    @Test
    fun `unknown or malformed post ids are not found and an unavailable site is 503`() {
        every { queries.get(postId, PreviewVersion.LATEST) } returns AdminPostLookupResult.NotFound

        mvc
            .perform(
                get("/admin/posts/{postId}", postId.value.toString()),
            ).andExpect(status().isNotFound)
            .andDo(document("admin-post-missing", postIdParameter()))
        mvc
            .perform(
                get("/admin/posts/{postId}", "nope"),
            ).andExpect(status().isNotFound)
            .andDo(document("admin-post-invalid-id", postIdParameter()))

        every { queries.get(postId, PreviewVersion.LATEST) } returns AdminPostLookupResult.ContentUnavailable
        mvc
            .perform(
                get("/admin/posts/{postId}", postId.value.toString()),
            ).andExpect(status().isServiceUnavailable)
            .andDo(document("admin-post-unavailable", postIdParameter()))
    }

    @Test
    fun `commands answer with a redirect back to the preview`() {
        val preview = "/admin/posts/${postId.value}"
        val command = AdminFixtures.publishCommand
        every { refreshes.refresh(postId) } returns RefreshPostResult.Unchanged
        every { publications.publish(command) } returns PublishPostResult.StaleRevision
        every { publications.unpublish(postId) } returns UnpublishPostResult.Unpublished
        every { publications.archive(postId) } returns ArchivePostResult.Archived
        every { publications.restore(postId) } returns RestorePostResult.Restored

        mvc
            .perform(
                post("$preview/refresh"),
            ).andExpect(status().isSeeOther)
            .andExpect(redirectedUrl(preview))
            .andDo(document("admin-post-refresh"))
        mvc
            .perform(
                post(
                    "$preview/publish",
                ).param("revisionId", command.revisionId.value.toString()).param("generation", command.generation.toString()),
            ).andExpect(status().isSeeOther)
            .andExpect(redirectedUrl(preview))
            .andDo(document("admin-post-publish-stale"))
        mvc.perform(post("$preview/unpublish")).andExpect(status().isSeeOther).andDo(document("admin-post-unpublish"))
        mvc.perform(post("$preview/archive")).andExpect(status().isSeeOther).andDo(document("admin-post-archive"))
        mvc.perform(post("$preview/restore")).andExpect(status().isSeeOther).andDo(document("admin-post-restore"))
    }

    @Test
    fun `commands on unknown posts are not found`() {
        every { publications.archive(postId) } returns ArchivePostResult.NotFound

        mvc
            .perform(
                post("/admin/posts/{postId}/archive", postId.value.toString()),
            ).andExpect(status().isNotFound)
            .andDo(document("admin-post-archive-missing", postIdParameter()))
    }

    @Test
    fun `public media is served with a validator and revalidates with 304`() {
        every { media.getPublic(mediaKey) } returns MediaLookupResult.Found(MediaContent(mediaKey, "image/png", byteArrayOf(1, 2, 3)))

        mvc
            .perform(get("/media/{mediaKey}", mediaKey.value))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"${mediaKey.value}\""))
            .andDo(document("media-found", mediaKeyParameter()))
        mvc
            .perform(get("/media/{mediaKey}", mediaKey.value).header("If-None-Match", "\"${mediaKey.value}\""))
            .andExpect(status().isNotModified)
            .andDo(document("media-not-modified", mediaKeyParameter()))
    }

    @Test
    fun `missing or malformed public media is not found`() {
        every { media.getPublic(mediaKey) } returns MediaLookupResult.NotFound

        mvc
            .perform(
                get("/media/{mediaKey}", mediaKey.value),
            ).andExpect(status().isNotFound)
            .andDo(document("media-missing", mediaKeyParameter()))
        mvc
            .perform(
                get("/media/{mediaKey}", "nope"),
            ).andExpect(status().isNotFound)
            .andDo(document("media-invalid-key", mediaKeyParameter()))
    }

    @Test
    fun `preview media is never cached`() {
        every { media.getForPreview(mediaKey) } returns MediaLookupResult.Found(MediaContent(mediaKey, "image/png", byteArrayOf(1, 2, 3)))

        mvc
            .perform(get("/admin/media/{mediaKey}", mediaKey.value))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andDo(document("admin-media-found", mediaKeyParameter()))
    }

    private fun postIdParameter() = pathParameters(parameterWithName("postId").description("블로그가 저장한 게시글 UUID"))

    private fun mediaKeyParameter() = pathParameters(parameterWithName("mediaKey").description("파일 내용의 SHA-256 소문자 16진수 64자"))

    // Spring Boot의 Thymeleaf 기본값을 그대로 따라 문서화한 응답이 운영 템플릿을 쓰게 한다.
    private fun thymeleafViewResolver(): ThymeleafViewResolver {
        val templateResolver =
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
            }
        val engine = SpringTemplateEngine()
        engine.setTemplateResolver(templateResolver)
        return ThymeleafViewResolver().apply {
            templateEngine = engine
            characterEncoding = "UTF-8"
            contentType = "text/html;charset=UTF-8"
        }
    }

    private companion object {
        const val KEY_LENGTH = 64
        const val PAGE_SIZE = 20
        const val HTTPS_PORT = 443
    }
}

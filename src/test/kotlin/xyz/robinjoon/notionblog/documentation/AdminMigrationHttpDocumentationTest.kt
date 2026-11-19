package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.transaction.TransactionSystemException
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminMigrationController
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationDataSource
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureDetail
import xyz.robinjoon.notionblog.blog.application.model.MigrationFailureStage
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobCounts
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobHistory
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobItem
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobProgress
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobStorageException
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobSummary
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobUpdates
import xyz.robinjoon.notionblog.blog.application.model.MigrationProperty
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationJobsUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.web.pageMockMvc
import xyz.robinjoon.notionblog.support.web.renderedPage
import xyz.robinjoon.notionblog.support.web.saveRenderedPage
import java.time.Instant
import java.util.UUID

class AdminMigrationHttpDocumentationTest {
    private val documentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val migrations = mockk<PostMigrationUseCase>()
    private val jobs = mockk<MigrationJobsUseCase>()
    private val pages = pageMockMvc(AdminMigrationController(migrations, jobs))
    private val mvc = migrationMvc(AdminMigrationController(migrations, jobs), documentation)

    @BeforeEach
    fun startDocumentation(testInfo: TestInfo) {
        documentation.beforeTest(javaClass, testInfo.testMethod.orElseThrow().name)
        every { jobs.history(1) } returns MigrationJobHistory(emptyList(), hasNext = false)
    }

    @AfterEach
    fun finishDocumentation() {
        documentation.afterTest()
    }

    @Test
    fun `migration editor documents permission instructions without tokens`() {
        val running = MigrationJobSummary(jobId, createdAt, 3, 3, paused = false, processing = true)
        every { jobs.history(1) } returns MigrationJobHistory(listOf(running), hasNext = true)

        mvc
            .perform(get("/admin/migrations?page=1"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Read content")))
            .andExpect(content().string(containsString("연결 추가")))
            .andExpect(content().string(containsString("https://www.notion.so/profile/integrations")))
            .andExpect(content().string(containsString("새 글은 초안으로")))
            .andExpect(content().string(containsString("마이그레이션 작업 이력")))
            .andExpect(content().string(containsString("0개 처리 · 진행 중")))
            .andExpect(content().string(containsString("href=\"$jobPath\"")))
            .andDo(document("admin-migrations"))
        assertThat(pages.renderedPage("/admin/migrations", "admin-migrations"))
            .contains("/ui/tokens.css", "/app/app.css", "aria-current=\"page\"", "글 마이그레이션")
            .contains("href=\"/admin/migrations?page=2\"")
            .doesNotContain("rel=\"prev\"")
    }

    @Test
    fun `history keeps job links and previous navigation even beyond the last page`() {
        val draining = MigrationJobSummary(jobId, createdAt, 1, 1, paused = true, processing = true)
        every { jobs.history(2) } returns MigrationJobHistory(listOf(draining), hasNext = true)
        every { jobs.history(4) } returns MigrationJobHistory(emptyList(), hasNext = false)

        assertThat(pages.renderedPage("/admin/migrations?page=2", "admin-migrations-history-2"))
            .contains("href=\"$jobPath\"", "현재 글을 마치면 일시 정지합니다.", "2페이지")
            .contains("href=\"/admin/migrations?page=1\"", "href=\"/admin/migrations?page=3\"")
        assertThat(pages.renderedPage("/admin/migrations?page=4", "admin-migrations-history-empty"))
            .contains("이 페이지에 작업이 없습니다.", "4페이지", "href=\"/admin/migrations?page=3\"")
            .doesNotContain("rel=\"next\"")
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid", "0", "-1", "2147483648"])
    fun `invalid history page numbers open the first page`(page: String) {
        val paused = MigrationJobSummary(jobId, createdAt, 1, 1, paused = true)
        every { jobs.history(1) } returns MigrationJobHistory(listOf(paused), hasNext = true)
        assertThat(pages.renderedPage("/admin/migrations?page=$page", "admin-migrations-history-$page"))
            .contains("0개 처리 · 일시 정지", "1페이지", "href=\"/admin/migrations?page=2\"")
            .doesNotContain("rel=\"prev\"")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a paused job polls only while a live claim drains`(processing: Boolean) {
        val paused = MigrationJobSummary(jobId, createdAt, 1, 1, paused = true, processing = processing)
        val item = MigrationJobItem(0, entry, processing)
        every { jobs.progress(jobId) } returns MigrationJobProgress(paused, listOf(item), version = 5)
        val changed = if (processing) listOf(item) else emptyList()
        every { jobs.updates(jobId, 5) } returns MigrationJobUpdates(paused, 5, MigrationJobCounts(), changed)
        val expectedStatus = if (processing) "현재 글을 마치면 일시 정지합니다." else "일시 정지"
        val page = Jsoup.parse(pages.renderedPage(jobPath, "admin-migrations-paused-$processing"))
        assertThat(page.select("[data-migration-status]").text()).isEqualTo(expectedStatus)
        assertThat(page.select("script[src=/app/post-migration.js]").isNotEmpty()).isEqualTo(processing)
        assertThat(page.select("form[data-migration-resume]:not([hidden]) button").attr("value")).isEqualTo("RESUME")
        assertThat(page.select("a[href=$jobPath]").text()).isEqualTo("상태 새로고침")
        mvc
            .perform(get("$jobPath/status?after=5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value("5"))
            .andExpect(jsonPath("$.results.length()").value(changed.size))
            .andExpect(jsonPath("$.progress.status").value(expectedStatus))
            .andExpect(jsonPath("$.progress.shouldPoll").value(processing))
    }

    @Test
    fun `schema shows each data source and only compatible field choices`() {
        val properties =
            listOf(
                MigrationProperty("title", "제목", "title"),
                MigrationProperty("summary", "요약", "rich_text"),
                MigrationProperty("tags", "태그", "multi_select"),
                MigrationProperty("date", "작성일", "date"),
                MigrationProperty("rating", "평점", "number"),
            )
        every { migrations.inspect(databaseUrl) } returns
            MigrationDatabase(
                databaseId,
                "예전 블로그",
                listOf(MigrationDataSource(dataSourceId, "게시글", properties), MigrationDataSource(pageId, "독서록", properties)),
            )

        val result =
            mvc
                .perform(post("/admin/migrations/inspect").param("databaseUrl", databaseUrl))
                .andExpect(status().isOk)
                .andDo(document("admin-migrations-inspect"))
                .andDo { saveRenderedPage("admin-migrations-inspect", it.response.contentAsString) }
                .andReturn()

        assertThat(result.response.contentAsString)
            .contains("예전 블로그", "게시글", "독서록", "name=\"authoredAtPropertyId\"", "value=\"$dataSourceId\"", "value=\"$pageId\"")
            .contains("https://www.notion.so/${databaseId.replace("-", "")}")
            .doesNotContain("value=\"rating\"", "읽을 수 있는 데이터 소스가 없습니다")

        every { migrations.inspect(databaseUrl) } returns MigrationDatabase(databaseId, "빈 데이터베이스", emptyList())
        mvc
            .perform(post("/admin/migrations/inspect").param("databaseUrl", databaseUrl))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("읽을 수 있는 데이터 소스가 없습니다")))
            .andDo { saveRenderedPage("admin-migrations-empty-database", it.response.contentAsString) }
    }

    @Test
    fun `denied access keeps safe source shortcut and permission instructions without exposing exception text`() {
        every { migrations.inspect(databaseUrl) } throws
            MigrationSourceException(SyncFailureKind.ACCESS, IllegalStateException("private-server-token"))

        val result =
            mvc
                .perform(post("/admin/migrations/inspect").param("databaseUrl", databaseUrl))
                .andExpect(status().isBadRequest)
                .andDo(document("admin-migrations-access-denied"))
                .andDo { saveRenderedPage("admin-migrations-access-denied", it.response.contentAsString) }
                .andReturn()

        assertThat(result.response.contentAsString)
            .contains("읽기 권한을 부여", "원본 DB 열기", "https://www.notion.so/${databaseId.replace("-", "")}")
            .doesNotContain("private-server-token")
    }

    @Test
    fun `rejected external URLs stay text and never become source links`() {
        val url = "https://evil.example/$databaseId?value=<script>"
        every { migrations.inspect(url) } throws
            MigrationSourceException(SyncFailureKind.CONFIGURATION, IllegalArgumentException("private-server-token"))

        val result =
            mvc
                .perform(post("/admin/migrations/inspect").param("databaseUrl", url))
                .andExpect(status().isBadRequest)
                .andDo(document("admin-migrations-invalid"))
                .andDo { saveRenderedPage("admin-migrations-invalid", it.response.contentAsString) }
                .andReturn()

        assertThat(result.response.contentAsString)
            .contains("링크와 필드 매핑을 확인")
            .doesNotContain("href=\"https://evil.example", "<script>", "private-server-token")
    }

    @Test
    fun `mapping registers server work and redirects to a reusable job URL`() {
        every { jobs.start(selection) } returns jobId

        mvc
            .perform(mappingRequest())
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", jobPath))
            .andDo(document("admin-migrations-prepare"))
    }

    @Test
    fun `a revisited job renders saved results before the browser reads live status`() {
        every { jobs.progress(jobId) } returns
            progress(
                MigrationJobItem(0, entry, result = MigratePostResult.Imported(postId)),
                MigrationJobItem(1, entry.copy(title = "남은 글"), processing = true),
            ).copy(version = 17)

        mvc
            .perform(get(jobPath))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("화면을 닫아도 서버에서 작업을 계속합니다.")))
            .andExpect(content().string(containsString("초안으로 가져왔습니다.")))
            .andExpect(content().string(containsString("가져오는 중…")))
            .andExpect(content().string(containsString("href=\"/admin/posts/${postId.value}\"")))
            .andExpect(content().string(containsString("data-status-endpoint=\"$jobPath/status\"")))
            .andExpect(content().string(containsString("data-version=\"17\"")))
            .andExpect(content().string(containsString("/app/post-migration.js")))
            .andExpect(content().string(containsString("action=\"$jobPath/action\"")))
            .andExpect(content().string(containsString("value=\"PAUSE\"")))
            .andExpect(content().string(not(containsString("/admin/migrations/import"))))
            .andDo(document("admin-migrations-progress"))
            .andDo { saveRenderedPage("admin-migrations-progress", it.response.contentAsString) }
    }

    @Test
    fun `an empty job reports completion with no displayed progress bar`() {
        every { jobs.progress(jobId) } returns progress()

        mvc
            .perform(get(jobPath))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("가져올 글이 없습니다")))
            .andExpect(content().string(containsString("처리 완료")))
            .andExpect(content().string(not(containsString("data-migration-progress"))))
            .andExpect(content().string(not(containsString("/app/post-migration.js"))))
            .andDo { saveRenderedPage("admin-migrations-empty-progress", it.response.contentAsString) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "?after=invalid", "?after=-2", "?after=9223372036854775808"])
    fun `a missing or invalid version returns all persisted results and retry control`(query: String) {
        val block = UUID.fromString("335e2d0a-976a-837d-b9f1-819ca3e19953")
        val warning = MigrationFailureDetail(MigrationFailureStage.SOURCE, "본문 블록을 읽을 수 없습니다.", "NotFound", block, 404)
        val failure =
            MigrationFailureDetail(MigrationFailureStage.AUTHORED_AT, "매핑한 작성일이 비어 있습니다.", "SourceMappingException", limitBytes = 1024)
        every { jobs.updates(jobId, -1) } returns
            MigrationJobUpdates(
                MigrationJobSummary(jobId, createdAt, 4, 0, paused = false),
                8,
                MigrationJobCounts(imported = 1, partial = 1, skipped = 1, failed = 1),
                listOf(
                    MigrationJobItem(0, entry, result = MigratePostResult.Imported(postId)),
                    MigrationJobItem(1, entry, result = MigratePostResult.Imported(postId, listOf(warning))),
                    MigrationJobItem(2, entry, result = MigratePostResult.Skipped(postId)),
                    MigrationJobItem(3, entry, result = MigratePostResult.Failed(SyncFailureKind.MAPPING, failure)),
                ),
            )

        mvc
            .perform(get("$jobPath/status$query").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value("8"))
            .andExpect(jsonPath("$.progress.total").value(4))
            .andExpect(jsonPath("$.progress.completed").value(4))
            .andExpect(jsonPath("$.progress.status").value("처리 완료"))
            .andExpect(jsonPath("$.progress.shouldPoll").value(false))
            .andExpect(jsonPath("$.progress.actions.retryFailed").value(true))
            .andExpect(jsonPath("$.results['0'].status").value("imported"))
            .andExpect(jsonPath("$.results['0'].href").value("/admin/posts/${postId.value}"))
            .andExpect(jsonPath("$.results['1'].status").value("partial"))
            .andExpect(jsonPath("$.results['1'].href").value("/admin/posts/${postId.value}"))
            .andExpect(jsonPath("$.results['1'].message").value(containsString("일부 가져옴 · 확인할 항목 1개")))
            .andExpect(jsonPath("$.results['1'].message").value(containsString("Notion 블록: $block")))
            .andExpect(jsonPath("$.results['1'].message").value(containsString("HTTP 상태: 404")))
            .andExpect(jsonPath("$.results['2'].status").value("skipped"))
            .andExpect(jsonPath("$.results['3'].status").value("failed"))
            .andExpect(jsonPath("$.results['3'].message").value(containsString("단계: 작성일 매핑")))
            .andExpect(jsonPath("$.results['3'].message").value(containsString("사유: 매핑한 작성일이 비어 있습니다.")))
            .andExpect(jsonPath("$.results['3'].message").value(containsString("예외: SourceMappingException")))
            .andExpect(jsonPath("$.results['3'].message").value(containsString("크기 제한: 1024 바이트")))
    }

    @Test
    fun `polling after a version returns one changed result with counts for the whole job`() {
        val changed = MigrationJobItem(9999, entry, result = MigratePostResult.Imported(postId))
        every { jobs.updates(jobId, 20000) } returns
            MigrationJobUpdates(
                MigrationJobSummary(jobId, createdAt, 10000, 0, paused = false),
                20001,
                MigrationJobCounts(imported = 9990, partial = 6, skipped = 3, failed = 1),
                listOf(changed),
            )
        mvc
            .perform(get("$jobPath/status?after=20000").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value("20001"))
            .andExpect(jsonPath("$.results.length()").value(1))
            .andExpect(jsonPath("$.results['9999'].status").value("imported"))
            .andExpect(jsonPath("$.progress.completed").value(10000))
            .andExpect(jsonPath("$.progress.summary").value("전체 10000개 · 가져옴 9990개 · 일부 가져옴 6개 · 기존 글 3개 · 실패 1개"))
            .andExpect(jsonPath("$.progress.actions.retryFailed").value(true))
            .andDo(document("admin-migrations-status"))
    }

    @Test
    fun `retrying failures redirects back to the same job`() {
        every { jobs.change(jobId, MigrationJobAction.RETRY_FAILED) } returns true

        mvc
            .perform(post("$jobPath/action").param("action", "RETRY_FAILED"))
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", jobPath))
            .andDo(document("admin-migrations-action"))
    }

    @Test
    fun `unknown job IDs return 404 on the page and status resource`() {
        every { jobs.progress(jobId) } returns null
        every { jobs.updates(jobId, -1) } returns null

        mvc.perform(get(jobPath)).andExpect(status().isNotFound)
        mvc
            .perform(get("$jobPath/status").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
        mvc.perform(get("/admin/migrations/jobs/invalid")).andExpect(status().isNotFound)
    }

    @Test
    fun `invalid mapping returns the editor with source guidance`() {
        every { jobs.start(selection) } throws
            MigrationSourceException(SyncFailureKind.CONFIGURATION, IllegalArgumentException("invalid mapping"))

        mvc
            .perform(mappingRequest())
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("글은 변경하지 않았습니다")))
            .andDo(document("admin-migrations-mapping-invalid"))
            .andDo { saveRenderedPage("admin-migrations-mapping-invalid", it.response.contentAsString) }
    }
}

@ExtendWith(OutputCaptureExtension::class)
class AdminMigrationRegistrationFailureDocumentationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `write and commit errors advise checking history without leaking private data`(
        atCommit: Boolean,
        output: CapturedOutput,
    ) {
        val documentation =
            ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
        val jobs = mockk<MigrationJobsUseCase>()
        val cause = IllegalStateException("private-notion-title")
        val failure = if (atCommit) TransactionSystemException("commit reply lost", cause) else MigrationJobStorageException(cause)
        every { jobs.start(selection) } throws failure
        documentation.beforeTest(javaClass, "registrationUnavailable")
        try {
            migrationMvc(AdminMigrationController(mockk(), jobs), documentation)
                .perform(mappingRequest())
                .andExpect(status().isServiceUnavailable)
                .andExpect(content().string(containsString("최근 작업 목록을 확인하세요.")))
                .andExpect(content().string(not(containsString("private-notion-title"))))
                .andDo(document("admin-migrations-registration-unavailable"))
                .andDo { saveRenderedPage("admin-migrations-registration-unavailable", it.response.contentAsString) }
        } finally {
            documentation.afterTest()
        }
        assertThat(output.out + output.err)
            .contains("Migration job storage unavailable", "errorType=${failure.javaClass.name}")
            .doesNotContain("private-notion-title", "commit reply lost")
    }
}

private val databaseId = "00000000-0000-0000-0000-0000000000d1"
private val dataSourceId = "00000000-0000-0000-0000-0000000000d2"
private val pageId = "00000000-0000-0000-0000-0000000000d3"
private val databaseUrl = "https://app.notion.com/p/$databaseId"
private val source = SourceDocumentRef(SourceId("notion"), pageId)
private val fields = MigrationFields("title", "summary", "tags", "date")
private val selection = MigrationSelection(dataSourceId, fields)
private val postId = PostId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
private val jobId = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
private val jobPath = "/admin/migrations/jobs/$jobId"
private val createdAt = Instant.parse("2026-11-19T09:00:00Z")
private val entry = MigrationEntry(source, "기존 작성일이 있는 글")

private fun progress(vararg items: MigrationJobItem): MigrationJobProgress =
    MigrationJobProgress(
        MigrationJobSummary(jobId, createdAt, items.size, items.count { it.result == null }, false, items.any { it.processing }),
        items.toList(),
    )

private fun mappingRequest(): MockHttpServletRequestBuilder =
    post("/admin/migrations/prepare")
        .param("databaseUrl", databaseUrl)
        .param("dataSourceId", dataSourceId)
        .param("titlePropertyId", "title")
        .param("summaryPropertyId", "summary")
        .param("tagsPropertyId", "tags")
        .param("authoredAtPropertyId", "date")

private fun migrationMvc(
    controller: AdminMigrationController,
    documentation: ManualRestDocumentation,
): MockMvc =
    MockMvcBuilders
        .standaloneSetup(controller)
        .setViewResolvers(viewResolver())
        .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
        .apply<StandaloneMockMvcBuilder>(documentationConfiguration(documentation))
        .build()

private fun viewResolver(): ThymeleafViewResolver {
    val resolver =
        ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
        }
    val engine = SpringTemplateEngine()
    engine.setTemplateResolver(resolver)
    return ThymeleafViewResolver().apply {
        templateEngine = engine
        characterEncoding = "UTF-8"
        contentType = "text/html;charset=UTF-8"
    }
}

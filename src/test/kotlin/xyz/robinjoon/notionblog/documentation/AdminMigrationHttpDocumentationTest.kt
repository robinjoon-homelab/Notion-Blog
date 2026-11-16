package xyz.robinjoon.notionblog.documentation

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.http.MediaType
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import xyz.robinjoon.notionblog.adapter.inbound.web.WebSecurityHeadersFilter
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.AdminMigrationController
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationDataSource
import xyz.robinjoon.notionblog.blog.application.model.MigrationDatabase
import xyz.robinjoon.notionblog.blog.application.model.MigrationEntry
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationPlan
import xyz.robinjoon.notionblog.blog.application.model.MigrationProperty
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.post.PostId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import xyz.robinjoon.notionblog.support.web.pageMockMvc
import xyz.robinjoon.notionblog.support.web.renderedPage
import xyz.robinjoon.notionblog.support.web.saveRenderedPage
import java.util.UUID

class AdminMigrationHttpDocumentationTest {
    private val documentation =
        ManualRestDocumentation(System.getProperty("org.springframework.restdocs.outputDir", "build/generated-snippets"))
    private val migrations = mockk<PostMigrationUseCase>()
    private val databaseId = "00000000-0000-0000-0000-0000000000d1"
    private val dataSourceId = "00000000-0000-0000-0000-0000000000d2"
    private val pageId = "00000000-0000-0000-0000-0000000000d3"
    private val databaseUrl = "https://app.notion.com/p/$databaseId"
    private val source = SourceDocumentRef(SourceId("notion"), pageId)
    private val fields = MigrationFields("title", "summary", "tags", "date")
    private val selection = MigrationSelection(dataSourceId, fields)
    private val postId = PostId(UUID.fromString("00000000-0000-0000-0000-0000000000e1"))
    private val pages = pageMockMvc(AdminMigrationController(migrations))
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(AdminMigrationController(migrations))
            .setViewResolvers(viewResolver())
            .addFilters<StandaloneMockMvcBuilder>(WebSecurityHeadersFilter())
            .apply<StandaloneMockMvcBuilder>(documentationConfiguration(documentation))
            .build()

    @BeforeEach
    fun startDocumentation(testInfo: TestInfo) {
        documentation.beforeTest(javaClass, testInfo.testMethod.orElseThrow().name)
    }

    @AfterEach
    fun finishDocumentation() {
        documentation.afterTest()
    }

    @Test
    fun `migration editor documents permission instructions without tokens`() {
        mvc
            .perform(get("/admin/migrations"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Read content")))
            .andExpect(content().string(containsString("연결 추가")))
            .andExpect(content().string(containsString("https://www.notion.so/profile/integrations")))
            .andExpect(content().string(containsString("새 글은 초안으로")))
            .andDo(document("admin-migrations"))
        assertThat(pages.renderedPage("/admin/migrations", "admin-migrations"))
            .contains("/ui/tokens.css", "/app/app.css", "aria-current=\"page\"", "글 마이그레이션")
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
            .doesNotContain("value=\"rating\"")
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
    fun `mapping prepares an automatic sequential queue with the original fields`() {
        every { migrations.prepare(selection) } returns MigrationPlan(selection, listOf(MigrationEntry(source, "기존 작성일이 있는 글")))

        mvc
            .perform(mappingRequest("/admin/migrations/prepare"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("기존 작성일이 있는 글")))
            .andExpect(content().string(containsString("data-post-migration")))
            .andExpect(content().string(containsString("/app/post-migration.js")))
            .andExpect(content().string(containsString("실패한 글만 다시 시도")))
            .andExpect(content().string(containsString("name=\"authoredAtPropertyId\" value=\"date\"")))
            .andDo(document("admin-migrations-prepare"))
            .andDo { saveRenderedPage("admin-migrations-progress", it.response.contentAsString) }
    }

    @Test
    fun `import responses distinguish a saved draft, existing post and failed row`() {
        every { migrations.migrate(MigratePostCommand(selection, source)) } returnsMany
            listOf(MigratePostResult.Imported(postId), MigratePostResult.Skipped(postId), MigratePostResult.Failed(SyncFailureKind.MAPPING))

        mvc
            .perform(importRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("imported"))
            .andExpect(jsonPath("$.href").value("/admin/posts/${postId.value}"))
            .andDo(document("admin-migrations-import"))
        mvc
            .perform(importRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("skipped"))
            .andExpect(jsonPath("$.message").value(containsString("기존 글을 유지")))
            .andDo(document("admin-migrations-skipped"))
        mvc
            .perform(importRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.message").value(containsString("작성일")))
            .andDo(document("admin-migrations-failed"))
    }

    @Test
    fun `invalid mapping returns the editor instead of starting a queue`() {
        every { migrations.prepare(selection) } throws
            MigrationSourceException(SyncFailureKind.CONFIGURATION, IllegalArgumentException("invalid mapping"))

        mvc
            .perform(mappingRequest("/admin/migrations/prepare"))
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("글은 변경하지 않았습니다")))
            .andDo(document("admin-migrations-mapping-invalid"))
            .andDo { saveRenderedPage("admin-migrations-mapping-invalid", it.response.contentAsString) }
    }

    @Test
    fun `missing source identity is rejected before creating a source reference`() {
        mvc
            .perform(importRequest(""))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value(containsString("원본 글을 확인")))
    }

    @Test
    fun `import source errors use problem detail understood by the shared API client`() {
        every { migrations.migrate(MigratePostCommand(selection, source)) } throws
            MigrationSourceException(SyncFailureKind.ACCESS, IllegalStateException("private-server-token"))

        mvc
            .perform(importRequest())
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value(containsString("읽기 권한을 부여")))
    }

    private fun mappingRequest(path: String): MockHttpServletRequestBuilder =
        post(path)
            .param("databaseUrl", databaseUrl)
            .param("dataSourceId", dataSourceId)
            .param("titlePropertyId", "title")
            .param("summaryPropertyId", "summary")
            .param("tagsPropertyId", "tags")
            .param("authoredAtPropertyId", "date")

    private fun importRequest(sourceId: String = "notion"): MockHttpServletRequestBuilder =
        post("/admin/migrations/import")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "mapping": {
                    "dataSourceId": "$dataSourceId",
                    "titlePropertyId": "title",
                    "summaryPropertyId": "summary",
                    "tagsPropertyId": "tags",
                    "authoredAtPropertyId": "date"
                  },
                  "sourceId": "$sourceId",
                  "externalId": "$pageId"
                }
                """.trimIndent(),
            )

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
}

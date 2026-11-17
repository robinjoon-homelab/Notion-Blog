package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.ModelAndView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminMigrationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationImportResponse
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.MigratePostCommand
import xyz.robinjoon.notionblog.blog.application.model.MigratePostResult
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.domain.SourceDocumentRef
import xyz.robinjoon.notionblog.blog.domain.SourceId
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind

@Controller
internal class AdminMigrationController(
    private val migrations: PostMigrationUseCase,
) {
    @GetMapping("/admin/migrations")
    fun index(): ModelAndView = editor("")

    @PostMapping("/admin/migrations/inspect")
    fun inspect(
        @RequestParam(defaultValue = "") databaseUrl: String,
    ): ModelAndView =
        guardedPage(databaseUrl) {
            val database = migrations.inspect(databaseUrl)
            editor(
                databaseUrl,
            ).addObject(
                "view",
                AdminMigrationView(databaseUrl, MigrationNotionLinks.source(databaseUrl), MigrationViewAssembler.database(database), null),
            )
        }

    @PostMapping("/admin/migrations/prepare")
    fun prepare(form: AdminMigrationForm): ModelAndView =
        guardedPage(form.databaseUrl) {
            ModelAndView(
                "admin/migration-progress",
            ).addObject("view", MigrationViewAssembler.progress(migrations.prepare(form.selection())))
        }

    @PostMapping("/admin/migrations/import", consumes = ["application/json"])
    @ResponseBody
    fun importPost(
        @RequestBody request: AdminMigrationImportRequest,
    ): ResponseEntity<*> {
        if (request.sourceId.isBlank() || request.externalId.isBlank()) {
            return rejectedImport("필드 매핑과 원본 글을 확인한 뒤 다시 시도하세요.")
        }
        return try {
            val command = MigratePostCommand(request.mapping.selection(), SourceDocumentRef(SourceId(request.sourceId), request.externalId))
            ResponseEntity.ok(response(migrations.migrate(command)))
        } catch (exception: MigrationSourceException) {
            rethrowInterruption(exception)
            rejectedImport(sourceError(exception))
        }
    }

    private fun rejectedImport(message: String): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .badRequest()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message))

    private fun editor(
        databaseUrl: String,
        notice: NoticeView? = null,
    ): ModelAndView =
        ModelAndView(
            "admin/migration",
        ).addObject("view", AdminMigrationView(databaseUrl, MigrationNotionLinks.source(databaseUrl), null, notice))

    private fun guardedPage(
        databaseUrl: String,
        action: () -> ModelAndView,
    ): ModelAndView =
        try {
            action()
        } catch (exception: MigrationSourceException) {
            rethrowInterruption(exception)
            editor(databaseUrl, NoticeView(sourceError(exception), true)).apply { status = HttpStatus.BAD_REQUEST }
        }

    private fun response(result: MigratePostResult): MigrationImportResponse =
        when (result) {
            is MigratePostResult.Imported -> {
                MigrationImportResponse("imported", "초안으로 가져왔습니다.", "/admin/posts/${result.postId.value}")
            }

            is MigratePostResult.Skipped -> {
                MigrationImportResponse(
                    "skipped",
                    "이미 있는 원본 글입니다. 기존 글을 유지했습니다.",
                    "/admin/posts/${result.postId.value}",
                )
            }

            is MigratePostResult.Failed -> {
                MigrationImportResponse("failed", MigrationFailureMessages.append(failureMessages.getValue(result.kind), result.diagnostic))
            }
        }

    private fun sourceError(exception: MigrationSourceException): String {
        val message =
            when (exception.kind) {
                SyncFailureKind.ACCESS -> "원본을 읽을 수 없습니다. 원본 DB에서 서버의 Notion 연동에 읽기 권한을 부여한 뒤 다시 확인하세요."
                SyncFailureKind.AUTHENTICATION -> "서버의 Notion 연동 인증을 확인하세요. 이 화면에는 토큰을 입력하지 않습니다."
                SyncFailureKind.CONFIGURATION -> "Notion 데이터베이스 링크와 필드 매핑을 확인하세요. 글은 변경하지 않았습니다."
                else -> "원본 DB를 읽지 못했습니다. 링크·필드 타입·연동 권한을 확인한 뒤 다시 시도하세요."
            }
        return MigrationFailureMessages.append(message, exception.diagnostic)
    }

    private fun rethrowInterruption(exception: MigrationSourceException) {
        if (Thread.currentThread().isInterrupted ||
            generateSequence<Throwable>(exception) { it.cause }.any { it is InterruptedException }
        ) {
            throw exception
        }
    }

    private companion object {
        val failureMessages =
            mapOf(
                SyncFailureKind.RETRYABLE_SOURCE to "Notion 응답을 받지 못했습니다. 잠시 후 다시 시도하세요.",
                SyncFailureKind.AUTHENTICATION to "서버의 Notion 연동 인증을 확인하세요.",
                SyncFailureKind.ACCESS to "원본 DB와 본문에서 참조한 페이지의 읽기 권한을 확인하세요.",
                SyncFailureKind.CONFIGURATION to "선택한 데이터 소스와 필드 매핑을 확인하세요.",
                SyncFailureKind.MAPPING to "본문 또는 글 속성을 변환하지 못했습니다.",
                SyncFailureKind.MEDIA to "첨부 파일을 보관하지 못했습니다. 원본 파일과 크기를 확인한 뒤 다시 시도하세요.",
            )
    }
}

internal data class AdminMigrationImportRequest(
    val mapping: AdminMigrationForm = AdminMigrationForm(),
    val sourceId: String = "",
    val externalId: String = "",
)

internal data class AdminMigrationForm(
    val dataSourceId: String = "",
    val titlePropertyId: String = "",
    val summaryPropertyId: String = "",
    val tagsPropertyId: String = "",
    val authoredAtPropertyId: String = "",
    val databaseUrl: String = "",
) {
    fun selection(): MigrationSelection =
        MigrationSelection(
            dataSourceId.trim(),
            MigrationFields(titlePropertyId.trim(), summaryPropertyId.trim(), tagsPropertyId.trim(), authoredAtPropertyId.trim()),
        )
}

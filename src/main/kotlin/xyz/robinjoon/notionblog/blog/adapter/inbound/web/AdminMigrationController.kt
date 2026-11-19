package xyz.robinjoon.notionblog.blog.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.transaction.TransactionException
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.AdminMigrationView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationDatabaseView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.MigrationJobHistoryView
import xyz.robinjoon.notionblog.blog.adapter.inbound.web.view.NoticeView
import xyz.robinjoon.notionblog.blog.application.model.MigrationFields
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobAction
import xyz.robinjoon.notionblog.blog.application.model.MigrationJobStorageException
import xyz.robinjoon.notionblog.blog.application.model.MigrationSelection
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationJobsUseCase
import xyz.robinjoon.notionblog.blog.application.port.input.MigrationSourceException
import xyz.robinjoon.notionblog.blog.application.port.input.PostMigrationUseCase
import xyz.robinjoon.notionblog.blog.domain.sync.SyncFailureKind
import java.util.UUID

@Controller
internal class AdminMigrationController(
    private val migrations: PostMigrationUseCase,
    private val jobs: MigrationJobsUseCase,
) {
    @GetMapping("/admin/migrations")
    fun index(
        @RequestParam(defaultValue = "1") page: String,
    ): ModelAndView = editor("", page = page.toIntOrNull()?.coerceAtLeast(1) ?: 1)

    @PostMapping("/admin/migrations/inspect")
    fun inspect(
        @RequestParam(defaultValue = "") databaseUrl: String,
    ): ModelAndView =
        guardedPage(databaseUrl) {
            editor(databaseUrl, database = MigrationViewAssembler.database(migrations.inspect(databaseUrl)))
        }

    @PostMapping("/admin/migrations/prepare")
    fun prepare(
        form: AdminMigrationForm,
        attributes: RedirectAttributes,
    ): ModelAndView =
        guardedPage(form.databaseUrl) {
            val jobId = jobs.start(form.selection())
            redirect(jobPath(jobId), attributes, "마이그레이션 작업을 등록했습니다.")
        }

    @GetMapping("/admin/migrations/jobs/{jobId}")
    fun progress(
        @PathVariable jobId: String,
        model: Model,
    ): ModelAndView {
        val job = WebIds.uuid(jobId)?.let(jobs::progress) ?: return missingJob()
        val view = MigrationViewAssembler.progress(job, model.notice())
        return ModelAndView("admin/migration-progress").addObject("view", view)
    }

    @GetMapping("/admin/migrations/jobs/{jobId}/status", produces = ["application/json"])
    @ResponseBody
    fun status(
        @PathVariable jobId: String,
        @RequestParam(defaultValue = "-1") after: String,
    ): ResponseEntity<*> {
        val id = WebIds.uuid(jobId) ?: return missingJobStatus()
        val version = after.toLongOrNull()?.coerceAtLeast(-1) ?: -1
        val updates = jobs.updates(id, version) ?: return missingJobStatus()
        return ResponseEntity.ok(MigrationViewAssembler.status(updates))
    }

    @PostMapping("/admin/migrations/jobs/{jobId}/action")
    fun change(
        @PathVariable jobId: String,
        @RequestParam action: MigrationJobAction,
        attributes: RedirectAttributes,
    ): ModelAndView {
        val id = WebIds.uuid(jobId) ?: return missingJob()
        if (!jobs.change(id, action)) return missingJob()
        return redirect(jobPath(id), attributes, "마이그레이션 작업 상태를 갱신했습니다.")
    }

    private fun jobPath(jobId: UUID): String = "/admin/migrations/jobs/$jobId"

    private fun missingJob(): ModelAndView = errorView("error/404", HttpStatus.NOT_FOUND)

    private fun missingJobStatus(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "마이그레이션 작업을 찾을 수 없습니다."))

    private fun editor(
        databaseUrl: String,
        notice: NoticeView? = null,
        database: MigrationDatabaseView? = null,
        page: Int = 1,
    ): ModelAndView {
        val view =
            AdminMigrationView(
                databaseUrl,
                MigrationNotionLinks.source(databaseUrl),
                database,
                notice,
                MigrationViewAssembler.history(page, jobs.history(page)),
            )
        return ModelAndView("admin/migration").addObject("view", view)
    }

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

    @ExceptionHandler(MigrationJobStorageException::class, TransactionException::class)
    fun unavailable(
        request: HttpServletRequest,
        exception: RuntimeException,
    ): ModelAndView {
        if (wasInterrupted(exception)) Thread.currentThread().interrupt()
        logger.warn(
            "Migration job storage unavailable errorType={} causeType={}",
            exception.javaClass.name,
            exception.cause?.javaClass?.name ?: "none",
        )
        val databaseUrl = request.getParameter("databaseUrl").orEmpty()
        val notice = NoticeView("마이그레이션 작업을 확인하지 못했습니다. 다시 시도하기 전에 최근 작업 목록을 확인하세요.", true)
        val history = MigrationJobHistoryView(1, emptyList(), hasNext = false)
        val view = AdminMigrationView(databaseUrl, MigrationNotionLinks.source(databaseUrl), null, notice, history)
        return ModelAndView("admin/migration", mapOf("view" to view), HttpStatus.SERVICE_UNAVAILABLE)
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
        if (wasInterrupted(exception)) throw exception
    }

    private fun wasInterrupted(failure: RuntimeException): Boolean =
        Thread.currentThread().isInterrupted || generateSequence<Throwable>(failure) { it.cause }.any { it is InterruptedException }

    private companion object {
        val logger = LoggerFactory.getLogger(AdminMigrationController::class.java)
    }
}

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

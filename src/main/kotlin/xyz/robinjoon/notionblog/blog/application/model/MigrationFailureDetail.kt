package xyz.robinjoon.notionblog.blog.application.model

import java.util.UUID

enum class MigrationFailureStage { SOURCE, BODY, PROPERTIES, AUTHORED_AT, MEDIA, IMPORT }

// reason과 exceptionType은 앱이 정한 문구만 전달한다. 외부 예외 메시지·응답·주소를 복사하지 않는다.
data class MigrationFailureDetail(
    val stage: MigrationFailureStage,
    val reason: String,
    val exceptionType: String,
    val blockId: UUID? = null,
    val httpStatus: Int? = null,
    val limitBytes: Long? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "diagnostic HTTP status must be valid" }
        require(limitBytes == null || limitBytes > 0) { "diagnostic size limit must be positive" }
    }
}

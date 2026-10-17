package xyz.robinjoon.notionblog.blog.domain.category

import xyz.robinjoon.notionblog.domain.AggregateRoot
import java.util.Locale
import java.util.UUID

@JvmInline
value class CategoryId(
    val value: UUID,
)

// 관리자가 앱에서 만드는 글 분류다. Notion 태그와 무관하며 글과의 연결은 글이 ID로만 가진다. 이름 유일성은 여러 분류에 걸친
// 규칙이므로 저장소가 normalizedName으로 보장한다.
@AggregateRoot
@ConsistentCopyVisibility
data class Category private constructor(
    val id: CategoryId,
    val name: String,
    val position: Int,
) {
    init {
        require(name.isNotEmpty()) { "category name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "category name must be at most $MAX_NAME_LENGTH characters" }
        require(position >= 0) { "category position must not be negative" }
    }

    // 대소문자만 다른 이름을 같은 이름으로 본다. 실행 환경의 언어 설정에 따라 결과가 달라지지 않게 고정 로캘을 쓴다.
    val normalizedName: String
        get() = name.lowercase(Locale.ROOT)

    fun update(
        name: String,
        position: Int,
    ): Category = Category(id, name.trim(), position)

    companion object {
        const val MAX_NAME_LENGTH = 50

        fun create(
            id: CategoryId,
            name: String,
            position: Int,
        ): Category = Category(id, name.trim(), position)

        fun restore(
            id: CategoryId,
            name: String,
            position: Int,
        ): Category = Category(id, name.trim(), position)
    }
}

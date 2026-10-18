package xyz.robinjoon.notionblog.blog.domain.category

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.util.UUID

class CategoryTest {
    private val id = CategoryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `creating a category trims the name and keeps the display order`() {
        val category = Category.create(id, "  Kotlin  ", 3)

        assertThat(category.id).isEqualTo(id)
        assertThat(category.name).isEqualTo("Kotlin")
        assertThat(category.position).isEqualTo(3)
    }

    @Test
    fun `updating keeps the identity and trims the new name`() {
        val updated = Category.create(id, "Kotlin", 0).update(" Spring ", 2)

        assertThat(updated).isEqualTo(Category.restore(id, "Spring", 2))
    }

    @Test
    fun `a name of up to fifty characters is accepted after trimming`() {
        val longest = "가".repeat(Category.MAX_NAME_LENGTH)

        assertThat(Category.create(id, " $longest ", 0).name).isEqualTo(longest)
    }

    @Test
    fun `blank or too long names and negative display orders are rejected`() {
        val category = Category.create(id, "Kotlin", 0)

        assertThatIllegalArgumentException().isThrownBy { Category.create(id, "   ", 0) }
        assertThatIllegalArgumentException().isThrownBy { Category.create(id, "a".repeat(Category.MAX_NAME_LENGTH + 1), 0) }
        assertThatIllegalArgumentException().isThrownBy { Category.create(id, "Kotlin", -1) }
        assertThatIllegalArgumentException().isThrownBy { category.update("", 0) }
        assertThatIllegalArgumentException().isThrownBy { category.update("Kotlin", -1) }
    }

    @Test
    fun `names that differ only in case or surrounding spaces normalize to the same name`() {
        assertThat(Category.normalize(" KOTLIN ")).isEqualTo(Category.create(id, "Kotlin", 0).normalizedName)
    }
}

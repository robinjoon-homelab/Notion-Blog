package xyz.robinjoon.notionblog.domain.post.block.content

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import xyz.robinjoon.notionblog.domain.post.block.inline.InlineContent

class DataViewContentTest {
    @Test
    fun `shares the same dataset between table list and gallery layouts`() {
        val data = DataSet("Tasks", listOf(DataColumn("Name"), DataColumn("Status")), emptyList(), titleColumnIndex = 0)
        val views = listOf(DataViewContent.Table(data), DataViewContent.ListView(data), DataViewContent.Gallery(data))

        assertThat(views.map(DataViewContent::data)).containsExactly(data, data, data)
        assertThat(views.map { it.data.rows }).allMatch { it.isEmpty() }
    }

    @Test
    fun `requires a nonblank title and at least one nonblank column`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataSet(" ", listOf(DataColumn("Name")), emptyList())
            }.withMessage("data set title must not be blank")
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataSet("Tasks", emptyList(), emptyList())
            }.withMessage("data set columns must not be empty")
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataColumn(" ")
            }.withMessage("data column name must not be blank")
    }

    @Test
    fun `rejects rows narrower or wider than the shared column count`() {
        listOf(1, 3).forEach { width ->
            assertThatIllegalArgumentException()
                .isThrownBy {
                    DataSet("Tasks", listOf(DataColumn("Name"), DataColumn("Status")), listOf(DataRow(List(width) { emptyList() })))
                }.withMessage("data set rows must contain exactly the column count")
        }
    }

    @Test
    fun `allows empty cells when every row has the column count`() {
        val data =
            DataSet(
                "Tasks",
                listOf(DataColumn("Name"), DataColumn("Status")),
                listOf(DataRow(listOf(listOf(InlineContent.Text("Write tests")), emptyList()))),
            )

        assertThat(data.rows.single().cells).hasSize(2)
        assertThat(
            data.rows
                .single()
                .cells
                .last(),
        ).isEmpty()
    }

    @Test
    fun `validates an optional title column against the shared columns`() {
        val data = DataSet("Tasks", listOf(DataColumn("Name")), emptyList())

        assertThat(data.titleColumnIndex).isNull()
        assertThat(data.copy(titleColumnIndex = 0).titleColumnIndex).isZero()
        listOf(-1, 1).forEach { index ->
            assertThatIllegalArgumentException()
                .isThrownBy {
                    data.copy(titleColumnIndex = index)
                }.withMessage("data set title column index must be within the columns")
        }
    }

    @Test
    fun `rejects negative column widths and preserves explicit zero width`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataColumn("Name", widthPixels = -1)
            }.withMessage("data column width must not be negative")
        assertThat(DataColumn("Name", widthPixels = 0).widthPixels).isZero()
    }

    @Test
    fun `validates frozen column counts against the table dataset`() {
        val data = DataSet("Tasks", listOf(DataColumn("Name")), emptyList())

        assertThat(DataViewContent.Table(data, DataTableOptions(frozenColumns = 1)).options.frozenColumns).isEqualTo(1)
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataTableOptions(frozenColumns = -1)
            }.withMessage("frozen column count must not be negative")
        assertThatIllegalArgumentException()
            .isThrownBy {
                DataViewContent.Table(data, DataTableOptions(frozenColumns = 2))
            }.withMessage("frozen column count must not exceed the column count")
    }
}

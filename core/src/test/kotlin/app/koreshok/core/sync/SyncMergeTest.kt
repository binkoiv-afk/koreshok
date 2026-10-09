package app.koreshok.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {
    private fun note(start: Int, color: Int = 0) = SyncNote("HIGHLIGHT", 1, 2, start, start + 5, color, null, "текст", null, 0)
    private fun book(position: String, openedAt: Long, vararg notes: SyncNote) = SyncBook("k", "Книга", position, 0.5f, openedAt, notes.toList())
    private fun snap(book: SyncBook?) = SyncSnapshot(listOfNotNull(book).associateBy { it.key })

    @Test
    fun laterReadingWins() {
        val merged = SyncMerge.merge(SyncSnapshot.EMPTY, snap(book("1:0:0", 100)), snap(book("5:3:0", 200)))
        assertEquals("5:3:0", merged.books["k"]?.position)
    }

    @Test
    fun addsFromBothSidesAndHonoursDeletes() {
        val base = snap(book("1:0:0", 100, note(1), note(2)))
        // Local deleted note 1 and added note 3; remote deleted note 2 and added note 4.
        val local = snap(book("1:0:0", 100, note(2), note(3)))
        val remote = snap(book("1:0:0", 100, note(1), note(4)))
        val merged = SyncMerge.merge(base, local, remote)
        assertEquals(listOf(3, 4), merged.books["k"]?.notes?.map { it.start })
    }

    @Test
    fun keepsEditFromTheOtherDevice() {
        val base = snap(book("1:0:0", 100, note(1, color = 0)))
        val merged = SyncMerge.merge(base, base, snap(book("1:0:0", 100, note(1, color = 3))))
        assertEquals(3, merged.books["k"]?.notes?.single()?.color)
    }

    @Test
    fun booksMatchAcrossNameOrder() {
        assertEquals(
            SyncMerge.bookKey("Омон Ра", "Виктор Пелевин"),
            SyncMerge.bookKey("Омон Ра [litres]", "Пелевин Виктор"),
        )
    }
}

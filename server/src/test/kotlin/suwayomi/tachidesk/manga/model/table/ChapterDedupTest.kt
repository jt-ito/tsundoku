package suwayomi.tachidesk.manga.model.table

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.ChapterDedup.distinctChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.unreadChapterCount

class ChapterDedupTest {
    private data class Chapter(
        val number: Float,
        val name: String,
        val isRead: Boolean = false,
    )

    private val chapterNumberOf = { chapter: Chapter -> chapter.number }
    private val chapterNameOf = { chapter: Chapter -> chapter.name }

    @Test
    fun `copies of one chapter by several scanlators count once`() {
        val chapters = listOf(Chapter(1f, "Chapter 1"), Chapter(1f, "Chapter 1"), Chapter(2f, "Chapter 2"))

        assertEquals(2, chapters.distinctChapterCount(chapterNumberOf, chapterNameOf))
    }

    @Test
    fun `chapter 0 is not counted when the manga has numbered chapters`() {
        val chapters = listOf(Chapter(0f, "Prologue"), Chapter(1f, "Chapter 1"), Chapter(2f, "Chapter 2"))

        assertEquals(2, chapters.distinctChapterCount(chapterNumberOf, chapterNameOf))
        assertEquals(2, chapters.unreadChapterCount(chapterNumberOf, chapterNameOf) { it.isRead })
    }

    @Test
    fun `a 0 is kept when nothing has a number above 0`() {
        val chapters = listOf(Chapter(0f, "Oneshot"))

        assertEquals(1, chapters.distinctChapterCount(chapterNumberOf, chapterNameOf))
    }

    @Test
    fun `decimal chapters and chapters without a number are counted`() {
        val chapters = listOf(Chapter(1f, "Chapter 1"), Chapter(1.5f, "Chapter 1.5"), Chapter(-1f, "Extra"))

        assertEquals(3, chapters.distinctChapterCount(chapterNumberOf, chapterNameOf))
    }

    @Test
    fun `a chapter is read once any copy was read`() {
        val chapters =
            listOf(Chapter(1f, "Chapter 1", isRead = true), Chapter(1f, "Chapter 1"), Chapter(2f, "Chapter 2"))

        assertEquals(1, chapters.unreadChapterCount(chapterNumberOf, chapterNameOf) { it.isRead })
    }
}

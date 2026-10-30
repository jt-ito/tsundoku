package eu.kanade.tachiyomi.util.chapter

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterRecognitionTest {
    @Test
    fun `dash parts of a chapter keep their decimal when the source rounds them down`() {
        assertEquals(14.2, ChapterRecognition.parseChapterNumber("Series", "Chapter 14-2", 14.0))
        assertEquals(14.3, ChapterRecognition.parseChapterNumber("Series", "Chapter 14 - 3", 14.0))
    }

    @Test
    fun `a plain chapter number from the source is kept`() {
        assertEquals(14.0, ChapterRecognition.parseChapterNumber("Series", "Chapter 14", 14.0))
        assertEquals(14.0, ChapterRecognition.parseChapterNumber("Series", "Chapter 114-2", 14.0))
        assertEquals(14.5, ChapterRecognition.parseChapterNumber("Series", "Chapter 14.5", 14.5))
    }

    @Test
    fun `dash decimals are read from the name when the source gives no number`() {
        assertEquals(14.2, ChapterRecognition.parseChapterNumber("Series", "Chapter 14-2"))
    }
}

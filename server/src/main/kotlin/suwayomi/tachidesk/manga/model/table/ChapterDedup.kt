package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Some sources release the same chapter multiple times under different scanlators/release
 * groups (e.g. chapter 5 by "Alpha", "Beta", and "Gamma"). Naively counting chapter rows
 * inflates unread/total/download counts for these manga. These helpers group chapters by
 * their real-world identity — [chapterNumber] when it's known ([ChapterTable.chapter_number]
 * defaults to -1f when a source doesn't provide one), otherwise the trimmed, lowercased
 * [name] — so each logical chapter is only counted once regardless of how many scanlator
 * copies exist for it. Chapter 0 (a prologue or the like) is not counted at all, as long as the
 * manga has numbered chapters: then 0 is a real number, not a source's "unknown".
 */
object ChapterDedup {
    private fun identityOf(
        chapterNumber: Float,
        name: String,
    ): Any = if (chapterNumber >= 0f) chapterNumber else name.trim().lowercase()

    private fun <T> List<T>.groupByChapterIdentity(
        chapterNumber: (T) -> Float,
        name: (T) -> String,
    ): Collection<List<T>> {
        // when nothing has a number above 0, a 0 is more likely a source's "unknown" than a chapter
        val skipZero = any { chapterNumber(it) > 0f }

        return filter { !skipZero || chapterNumber(it) != 0f }
            .groupBy { identityOf(chapterNumber(it), name(it)) }
            .values
    }

    /** Total number of distinct logical chapters, regardless of read/download/bookmark state. */
    fun <T> List<T>.distinctChapterCount(
        chapterNumber: (T) -> Float,
        name: (T) -> String,
    ): Int = groupByChapterIdentity(chapterNumber, name).size

    /** A logical chapter is unread only while none of its scanlator copies have been read. */
    fun <T> List<T>.unreadChapterCount(
        chapterNumber: (T) -> Float,
        name: (T) -> String,
        isRead: (T) -> Boolean,
    ): Int = groupByChapterIdentity(chapterNumber, name).count { group -> group.none(isRead) }

    /** A logical chapter counts as downloaded once any of its scanlator copies is. */
    fun <T> List<T>.downloadedChapterCount(
        chapterNumber: (T) -> Float,
        name: (T) -> String,
        isDownloaded: (T) -> Boolean,
    ): Int = groupByChapterIdentity(chapterNumber, name).count { group -> group.any(isDownloaded) }

    /** A logical chapter counts as bookmarked once any of its scanlator copies is. */
    fun <T> List<T>.bookmarkedChapterCount(
        chapterNumber: (T) -> Float,
        name: (T) -> String,
        isBookmarked: (T) -> Boolean,
    ): Int = groupByChapterIdentity(chapterNumber, name).count { group -> group.any(isBookmarked) }
}

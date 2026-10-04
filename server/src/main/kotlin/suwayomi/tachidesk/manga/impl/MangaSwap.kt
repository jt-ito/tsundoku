package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaSwapTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.server.user.model.UserTable
import java.time.Instant

/**
 * Migrating a series to another source happens in the WebUI as two plain updates, so nothing tells the server which new
 * series replaced which old one. The WebUI says so ([record]); when a shared library then swaps the series for another
 * account, that account's progress and tracker records follow like they do for the account that migrated.
 */
object MangaSwap {
    private const val KEEP_DAYS = 30L

    fun record(
        userId: Int,
        oldMangaId: Int,
        newMangaId: Int,
    ) = transaction {
        require(oldMangaId != newMangaId) { "A series can't replace itself" }
        require(MangaTable.selectAll().where { MangaTable.id inList listOf(oldMangaId, newMangaId) }.count() == 2L) { "Unknown manga" }
        MangaSwapTable.deleteWhere { (user eq userId) and (oldManga eq oldMangaId) }
        MangaSwapTable.deleteWhere { createdAt lessEq Instant.now().epochSecond - KEEP_DAYS * 86_400 }
        MangaSwapTable.insert {
            it[user] = EntityID(userId, UserTable)
            it[oldManga] = EntityID(oldMangaId, MangaTable)
            it[newManga] = EntityID(newMangaId, MangaTable)
            it[createdAt] = Instant.now().epochSecond
        }
    }

    /** The sender replaced some series and the share just removed the old ones from the recipient: move what they had. */
    fun carryOver(
        senderId: Int,
        recipientId: Int,
        removedManga: Collection<Int>,
    ) {
        if (removedManga.isEmpty()) return
        MangaSwapTable
            .selectAll()
            .where { (MangaSwapTable.user eq senderId) and (MangaSwapTable.oldManga inList removedManga.toList()) }
            .forEach { swap -> carryOver(recipientId, swap[MangaSwapTable.oldManga].value, swap[MangaSwapTable.newManga].value) }
    }

    /** Same rules as the migration of the WebUI: read up to the highest read chapter, bookmarks by chapter number. */
    private fun carryOver(
        userId: Int,
        oldMangaId: Int,
        newMangaId: Int,
    ) {
        val oldChapters = ChapterTable.selectAll().where { ChapterTable.manga eq oldMangaId }.toList()
        val ownStates =
            UserChapterTable
                .selectAll()
                .where { (UserChapterTable.user eq userId) and (UserChapterTable.chapter inList oldChapters.map { it[ChapterTable.id].value }) }
                .associateBy { it[UserChapterTable.chapter].value }

        // the first account is mirrored into the chapter table
        fun isRead(chapter: org.jetbrains.exposed.v1.core.ResultRow) =
            ownStates[chapter[ChapterTable.id].value]?.get(UserChapterTable.isRead) ?: (userId == 1 && chapter[ChapterTable.isRead])

        fun isBookmarked(chapter: org.jetbrains.exposed.v1.core.ResultRow) =
            ownStates[chapter[ChapterTable.id].value]?.get(UserChapterTable.isBookmarked) ?: (userId == 1 && chapter[ChapterTable.isBookmarked])

        val highestRead = oldChapters.filter(::isRead).maxOfOrNull { it[ChapterTable.chapter_number] }
        val bookmarkedNumbers = oldChapters.filter(::isBookmarked).map { it[ChapterTable.chapter_number] }.toSet()

        if (highestRead != null || bookmarkedNumbers.isNotEmpty()) {
            ChapterTable.selectAll().where { ChapterTable.manga eq newMangaId }.forEach { chapter ->
                val read = highestRead != null && chapter[ChapterTable.chapter_number] <= highestRead
                val bookmark = chapter[ChapterTable.chapter_number] in bookmarkedNumbers
                if (read || bookmark) markState(userId, chapter[ChapterTable.id].value, read, bookmark)
            }
        }

        // a tracker entry is about the series, not the source: it follows unless the new series has that tracker already
        val taken = TrackRecordTable.selectAll().where { TrackRecordTable.mangaId eq newMangaId and TrackRecordTable.ownedBy(userId) }.map { it[TrackRecordTable.trackerId] }
        TrackRecordTable
            .selectAll()
            .where { (TrackRecordTable.mangaId eq oldMangaId) and TrackRecordTable.ownedBy(userId) }
            .filter { it[TrackRecordTable.trackerId] !in taken }
            .forEach { record ->
                TrackRecordTable.update({ TrackRecordTable.id eq record[TrackRecordTable.id] }) {
                    it[mangaId] = EntityID(newMangaId, MangaTable)
                }
            }
    }

    /** Only ever sets to read / bookmarked, what the account already has on the new series stays. */
    private fun markState(
        userId: Int,
        chapterId: Int,
        read: Boolean,
        bookmark: Boolean,
    ) {
        val existing = UserChapterTable.selectAll().where { (UserChapterTable.user eq userId) and (UserChapterTable.chapter eq chapterId) }.firstOrNull()
        if (existing == null) {
            UserChapterTable.insert {
                it[user] = EntityID(userId, UserTable)
                it[chapter] = EntityID(chapterId, ChapterTable)
                it[isRead] = read
                it[isBookmarked] = bookmark
            }
        } else {
            UserChapterTable.update({ (UserChapterTable.user eq userId) and (UserChapterTable.chapter eq chapterId) }) {
                if (read) it[isRead] = true
                if (bookmark) it[isBookmarked] = true
            }
        }
        if (userId == 1) {
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                if (read) it[isRead] = true
                if (bookmark) it[isBookmarked] = true
            }
        }
    }
}

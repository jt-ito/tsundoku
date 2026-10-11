/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.types

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import java.util.concurrent.CompletableFuture

data class SyncConflictInfoType(
    val deviceName: String,
    val remotePage: Int,
)

class ChapterType(
    val id: Int,
    val url: String,
    val name: String,
    val uploadDate: Long,
    val chapterNumber: Float,
    val scanlator: String?,
    val mangaId: Int,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val lastPageRead: Int,
    /** how far into the last read page (0 to 1 of its height) */
    val lastPageOffset: Double,
    val lastReadAt: Long,
    val sourceOrder: Int,
    val realUrl: String?,
    val fetchedAt: Long,
    val isDownloaded: Boolean,
    val pageCount: Int,
//    val chapterCount: Int?,
) : Node {
    companion object {
        fun clearCacheFor(
            chapterId: Int,
            mangaId: Int,
            dataFetchingEnvironment: DataFetchingEnvironment,
        ) {
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("ChapterDataLoader")?.clear(chapterId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterNodeList>("ChaptersForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, Int>("DownloadedChapterCountForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("LastReadChapterForMangaDataLoader")?.clear(mangaId)
        }

        fun resolveUserChapter(chapterId: Int, userId: Int): ResultRow? {
            return suwayomi.tachidesk.manga.model.table.UserChapterTable
                .selectAll()
                .where {
                    (suwayomi.tachidesk.manga.model.table.UserChapterTable.user eq userId) and
                        (suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter eq chapterId)
                }
                .firstOrNull()
        }

        fun resolveIsRead(row: ResultRow, userId: Int): Boolean {
            val userRow = resolveUserChapter(row[ChapterTable.id].value, userId)
            return userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isRead)
                ?: if (userId == 1) row[ChapterTable.isRead] else false
        }

        fun resolveIsBookmarked(row: ResultRow, userId: Int): Boolean {
            val userRow = resolveUserChapter(row[ChapterTable.id].value, userId)
            return userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isBookmarked)
                ?: if (userId == 1) row[ChapterTable.isBookmarked] else false
        }

        fun resolveLastPageRead(row: ResultRow, userId: Int): Int {
            val userRow = resolveUserChapter(row[ChapterTable.id].value, userId)
            return userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageRead)
                ?: if (userId == 1) row[ChapterTable.lastPageRead] else 0
        }

        fun resolveLastPageOffset(row: ResultRow, userId: Int): Double =
            resolveUserChapter(row[ChapterTable.id].value, userId)
                ?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageOffset) ?: 0.0

        fun resolveLastReadAt(row: ResultRow, userId: Int): Long {
            val userRow = resolveUserChapter(row[ChapterTable.id].value, userId)
            return userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastReadAt)
                ?: if (userId == 1) row[ChapterTable.lastReadAt] else 0L
        }
    }

    constructor(row: ResultRow, userId: Int? = null) : this(
        row[ChapterTable.id].value,
        row[ChapterTable.url],
        row[ChapterTable.name],
        row[ChapterTable.date_upload],
        row[ChapterTable.chapter_number],
        row[ChapterTable.scanlator],
        row[ChapterTable.manga].value,
        if (userId != null) resolveIsRead(row, userId) else row[ChapterTable.isRead],
        if (userId != null) resolveIsBookmarked(row, userId) else row[ChapterTable.isBookmarked],
        if (userId != null) resolveLastPageRead(row, userId) else row[ChapterTable.lastPageRead],
        if (userId != null) resolveLastPageOffset(row, userId) else 0.0,
        if (userId != null) resolveLastReadAt(row, userId) else row[ChapterTable.lastReadAt],
        row[ChapterTable.sourceOrder],
        row[ChapterTable.realUrl],
        row[ChapterTable.fetchedAt],
        row[ChapterTable.isDownloaded],
        row[ChapterTable.pageCount],
    )

    constructor(dataClass: ChapterDataClass) : this(
        dataClass.id,
        dataClass.url,
        dataClass.name,
        dataClass.uploadDate,
        dataClass.chapterNumber,
        dataClass.scanlator,
        dataClass.mangaId,
        dataClass.read,
        dataClass.bookmarked,
        dataClass.lastPageRead,
        0.0,
        dataClass.lastReadAt,
        dataClass.index,
        dataClass.realUrl,
        dataClass.fetchedAt,
        dataClass.downloaded,
        dataClass.pageCount,
    )

    fun manga(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<MangaType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, MangaType>("MangaDataLoader", mangaId)

    fun meta(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<List<ChapterMetaType>> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, List<ChapterMetaType>>("ChapterMetaDataLoader", id)
}

data class ChapterNodeList(
    override val nodes: List<ChapterType>,
    override val edges: List<ChapterEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class ChapterEdge(
        override val cursor: Cursor,
        override val node: ChapterType,
    ) : Edge()

    companion object {
        fun List<ChapterType>.toNodeList(): ChapterNodeList =
            ChapterNodeList(
                nodes = this,
                edges = getEdges(),
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )

        private fun List<ChapterType>.getEdges(): List<ChapterEdge> {
            if (isEmpty()) return emptyList()
            return listOf(
                ChapterEdge(
                    cursor = Cursor("0"),
                    node = first(),
                ),
                ChapterEdge(
                    cursor = Cursor(lastIndex.toString()),
                    node = last(),
                ),
            )
        }
    }
}

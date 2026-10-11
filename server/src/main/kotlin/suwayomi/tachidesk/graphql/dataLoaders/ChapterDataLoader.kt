/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import suwayomi.tachidesk.manga.model.table.UserChapterTable
import suwayomi.tachidesk.graphql.server.currentUserId
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.JoinType
import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Slf4jSqlDebugLogger
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterDedup.bookmarkedChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.distinctChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.downloadedChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.unreadChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.idOrNull

/** The chapters with the read state of one account (chapters without a row of their own have null there). */
private fun ownReadState(userId: Int) =
    ChapterTable.join(
        UserChapterTable,
        JoinType.LEFT,
        onColumn = ChapterTable.id,
        otherColumn = UserChapterTable.chapter,
        additionalConstraint = { UserChapterTable.user eq userId },
    )

class ChapterDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "ChapterDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userType = graphQLContext.get<suwayomi.tachidesk.server.user.UserType>(suwayomi.tachidesk.server.JavalinSetup.Attribute.TachideskUser)
                    val userId = userType?.idOrNull ?: 1

                    val userProgressMap =
                        suwayomi.tachidesk.manga.model.table.UserChapterTable
                            .selectAll()
                            .where {
                                (suwayomi.tachidesk.manga.model.table.UserChapterTable.user eq userId) and
                                    (suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter inList ids)
                            }
                            .associateBy { it[suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter].value }

                    val chapters =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList ids }
                            .map { row ->
                                val chId = row[ChapterTable.id].value
                                val userRow = userProgressMap[chId]
                                ChapterType(
                                    id = chId,
                                    url = row[ChapterTable.url],
                                    name = row[ChapterTable.name],
                                    uploadDate = row[ChapterTable.date_upload],
                                    chapterNumber = row[ChapterTable.chapter_number],
                                    scanlator = row[ChapterTable.scanlator],
                                    mangaId = row[ChapterTable.manga].value,
                                    isRead = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isRead) ?: (if (userId == 1) row[ChapterTable.isRead] else false),
                                    isBookmarked = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isBookmarked) ?: (if (userId == 1) row[ChapterTable.isBookmarked] else false),
                                    lastPageRead = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageRead) ?: (if (userId == 1) row[ChapterTable.lastPageRead] else 0),
                                    lastPageOffset = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageOffset) ?: 0.0,
                                    lastReadAt = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastReadAt) ?: (if (userId == 1) row[ChapterTable.lastReadAt] else 0L),
                                    sourceOrder = row[ChapterTable.sourceOrder],
                                    realUrl = row[ChapterTable.realUrl],
                                    fetchedAt = row[ChapterTable.fetchedAt],
                                    isDownloaded = row[ChapterTable.isDownloaded],
                                    pageCount = row[ChapterTable.pageCount],
                                )
                            }
                            .associateBy { it.id }
                    ids.map { chapters[it] }
                }
            }
        }
}

class ChaptersForMangaDataLoader : KotlinDataLoader<Int, ChapterNodeList> {
    override val dataLoaderName = "ChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterNodeList> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userType = graphQLContext.get<suwayomi.tachidesk.server.user.UserType>(suwayomi.tachidesk.server.JavalinSetup.Attribute.TachideskUser)
                    val userId = userType?.idOrNull ?: 1

                    val chapterRows =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .toList()

                    val chapterIds = chapterRows.map { it[ChapterTable.id].value }

                    val userProgressMap =
                        if (chapterIds.isNotEmpty()) {
                            suwayomi.tachidesk.manga.model.table.UserChapterTable
                                .selectAll()
                                .where {
                                    (suwayomi.tachidesk.manga.model.table.UserChapterTable.user eq userId) and
                                        (suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter inList chapterIds)
                                }
                                .associateBy { it[suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter].value }
                        } else {
                            emptyMap()
                        }

                    val chaptersByMangaId =
                        chapterRows
                            .map { row ->
                                val chId = row[ChapterTable.id].value
                                val userRow = userProgressMap[chId]
                                ChapterType(
                                    id = chId,
                                    url = row[ChapterTable.url],
                                    name = row[ChapterTable.name],
                                    uploadDate = row[ChapterTable.date_upload],
                                    chapterNumber = row[ChapterTable.chapter_number],
                                    scanlator = row[ChapterTable.scanlator],
                                    mangaId = row[ChapterTable.manga].value,
                                    isRead = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isRead) ?: (if (userId == 1) row[ChapterTable.isRead] else false),
                                    isBookmarked = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isBookmarked) ?: (if (userId == 1) row[ChapterTable.isBookmarked] else false),
                                    lastPageRead = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageRead) ?: (if (userId == 1) row[ChapterTable.lastPageRead] else 0),
                                    lastPageOffset = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastPageOffset) ?: 0.0,
                                    lastReadAt = userRow?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.lastReadAt) ?: (if (userId == 1) row[ChapterTable.lastReadAt] else 0L),
                                    sourceOrder = row[ChapterTable.sourceOrder],
                                    realUrl = row[ChapterTable.realUrl],
                                    fetchedAt = row[ChapterTable.fetchedAt],
                                    isDownloaded = row[ChapterTable.isDownloaded],
                                    pageCount = row[ChapterTable.pageCount],
                                )
                            }
                            .groupBy { it.mangaId }
                    ids.map { mangaId ->
                        val chapters = chaptersByMangaId[mangaId] ?: emptyList()
                        chapters.toNodeList().copy(
                            totalCount = chapters.distinctChapterCount({ it.chapterNumber }, { it.name }),
                        )
                    }
                }
            }
        }
}

data class MangaChapterStats(
    val unreadCount: Int,
    val downloadCount: Int,
    val bookmarkCount: Int,
)

class ChapterFlagCountForMangaDataLoader : KotlinDataLoader<Int, MangaChapterStats> {
    override val dataLoaderName = "ChapterFlagCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaChapterStats> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userType = graphQLContext.get<suwayomi.tachidesk.server.user.UserType>(suwayomi.tachidesk.server.JavalinSetup.Attribute.TachideskUser)
                    val userId = userType?.idOrNull ?: 1

                    val allChapters =
                        ChapterTable
                            .select(
                                ChapterTable.id,
                                ChapterTable.manga,
                                ChapterTable.chapter_number,
                                ChapterTable.name,
                                ChapterTable.isRead,
                                ChapterTable.isDownloaded,
                                ChapterTable.isBookmarked,
                            ).where {
                                ChapterTable.manga inList ids
                            }.toList()

                    val chaptersByManga = allChapters.groupBy { it[ChapterTable.manga].value }

                    val chapterNumberOf: (ResultRow) -> Float = { it[ChapterTable.chapter_number] }
                    val chapterNameOf: (ResultRow) -> String = { it[ChapterTable.name] }

                    if (userId == 1) {
                        ids.map { mangaId ->
                            val chapters = chaptersByManga[mangaId] ?: emptyList()
                            if (chapters.isEmpty()) {
                                MangaChapterStats(0, 0, 0)
                            } else {
                                MangaChapterStats(
                                    unreadCount = chapters.unreadChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isRead] },
                                    downloadCount = chapters.downloadedChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isDownloaded] },
                                    bookmarkCount = chapters.bookmarkedChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isBookmarked] },
                                )
                            }
                        }
                    } else {
                        val allChapterIds = allChapters.map { it[ChapterTable.id].value }

                        val userRows =
                            if (allChapterIds.isNotEmpty()) {
                                suwayomi.tachidesk.manga.model.table.UserChapterTable
                                    .selectAll()
                                    .where {
                                        (suwayomi.tachidesk.manga.model.table.UserChapterTable.user eq userId) and
                                            (suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter inList allChapterIds)
                                    }.associateBy { it[suwayomi.tachidesk.manga.model.table.UserChapterTable.chapter].value }
                            } else {
                                emptyMap()
                            }

                        val isReadOf: (ResultRow) -> Boolean = { ch ->
                            userRows[ch[ChapterTable.id].value]?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isRead) == true
                        }
                        val isBookmarkedOf: (ResultRow) -> Boolean = { ch ->
                            userRows[ch[ChapterTable.id].value]?.get(suwayomi.tachidesk.manga.model.table.UserChapterTable.isBookmarked) == true
                        }

                        ids.map { mangaId ->
                            val chapters = chaptersByManga[mangaId] ?: emptyList()
                            if (chapters.isEmpty()) {
                                MangaChapterStats(0, 0, 0)
                            } else {
                                MangaChapterStats(
                                    unreadCount = chapters.unreadChapterCount(chapterNumberOf, chapterNameOf, isReadOf),
                                    downloadCount = chapters.downloadedChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isDownloaded] },
                                    bookmarkCount = chapters.bookmarkedChapterCount(chapterNumberOf, chapterNameOf, isBookmarkedOf),
                                )
                            }
                        }
                    }
                }
            }
        }
}

class HasDuplicateChaptersForMangaDataLoader : KotlinDataLoader<Int, Boolean> {
    override val dataLoaderName = "HasDuplicateChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Boolean> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val duplicatedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.chapter_number, ChapterTable.chapter_number.count())
                            .where {
                                (
                                    ChapterTable.manga inList
                                        ids
                                ) and
                                    (ChapterTable.chapter_number greaterEq 0f)
                            }.groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 }
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.chapter_number.count()] }

                    ids.map { duplicatedChapterCountByMangaId.contains(it) }
                }
            }
        }
}

class LastReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LastReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val lastReadChaptersByMangaId =
                        if (userId == 1) {
                            ChapterTable
                                .selectAll()
                                .where { (ChapterTable.manga inList ids) }
                                .orderBy(ChapterTable.lastReadAt to SortOrder.DESC)
                        } else {
                            // the reading of the other accounts is kept in user_chapter
                            ownReadState(userId)
                                .selectAll()
                                .where { (ChapterTable.manga inList ids) }
                                .orderBy(UserChapterTable.lastReadAt to SortOrder.DESC_NULLS_LAST)
                        }.groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> lastReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first(), userId) } }
                }
            }
        }
}

class LatestReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestReadChaptersByMangaId =
                        if (userId == 1) {
                            ChapterTable
                                .selectAll()
                                .where { (ChapterTable.manga inList ids) and (ChapterTable.isRead eq true) }
                                .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                        } else {
                            ownReadState(userId)
                                .selectAll()
                                .where { (ChapterTable.manga inList ids) and (UserChapterTable.isRead eq true) }
                                .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                        }.groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first(), userId) } }
                }
            }
        }
}

class LatestFetchedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestFetchedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestFetchedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestFetchedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestUploadedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestUploadedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestUploadedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.date_upload to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestUploadedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class FirstUnreadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "FirstUnreadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val firstUnreadChaptersByMangaId =
                        if (userId == 1) {
                            ChapterTable
                                .selectAll()
                                .where { (ChapterTable.manga inList ids) and (ChapterTable.isRead eq false) }
                                .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                        } else {
                            // no row of its own means not read yet
                            ownReadState(userId)
                                .selectAll()
                                .where {
                                    (ChapterTable.manga inList ids) and
                                        ((UserChapterTable.isRead eq false) or UserChapterTable.isRead.isNull())
                                }.orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                        }.groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> firstUnreadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first(), userId) } }
                }
            }
        }
}

class HighestNumberedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "HighestNumberedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val highestNumberedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.chapter_number greater 0f) }
                            .orderBy(ChapterTable.chapter_number to SortOrder.DESC_NULLS_LAST)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id ->
                        highestNumberedChaptersByMangaId[id]
                            ?.firstOrNull()
                            ?.let { chapter -> ChapterType(chapter) }
                    }
                }
            }
        }
}

package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_ID
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterDedup.distinctChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.downloadedChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterDedup.unreadChapterCount
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.allLibraryMangaIds
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.database.dbTransaction

object CategoryManga {
    fun addMangaToCategory(
        mangaId: Int,
        categoryId: Int,
    ) {
        addMangaToCategories(mangaId, listOf(categoryId))
    }

    fun addMangaToCategories(
        mangaId: Int,
        categoryIds: List<Int>,
    ) {
        addMangasToCategories(listOf(mangaId), categoryIds)
    }

    fun addMangasToCategories(
        mangaIds: List<Int>,
        categoryIds: List<Int>,
    ) {
        // the Default category of an account is never linked: it means "in none of my categories"
        val defaultRowIds = Category.defaultRowIds()
        val filteredCategoryIds = categoryIds.filter { it != DEFAULT_CATEGORY_ID && it !in defaultRowIds }

        val mangaIdsToCategoryIds = getMangasCategories(mangaIds).mapValues { it.value.map { category -> category.id } }
        val mangaIdsToNewCategoryIds =
            mangaIds.associateWith { mangaId ->
                filteredCategoryIds.filter { categoryId ->
                    !(mangaIdsToCategoryIds[mangaId]?.contains(categoryId) ?: false)
                }
            }

        val newMangaCategoryMappings =
            mangaIdsToNewCategoryIds.flatMap { (mangaId, newCategoryIds) ->
                newCategoryIds.map { mangaId to it }
            }

        dbTransaction {
            CategoryMangaTable.batchUpsert(
                newMangaCategoryMappings,
                CategoryMangaTable.manga,
                CategoryMangaTable.category,
            ) { (mangaId, categoryId) ->
                this[CategoryMangaTable.manga] = mangaId
                this[CategoryMangaTable.category] = categoryId
            }
        }
    }

    fun removeMangaFromCategory(
        mangaId: Int,
        categoryId: Int,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            CategoryMangaTable.deleteWhere { (CategoryMangaTable.category eq categoryId) and (CategoryMangaTable.manga eq mangaId) }
        }
    }

    fun removeMangaFromAllCategories(mangaId: Int) {
        transaction {
            CategoryMangaTable.deleteWhere { CategoryMangaTable.manga eq mangaId }
        }
    }

    /**
     * list of mangas that belong to a category
     */
    fun getCategoryMangaList(
        categoryId: Int,
        inAnyLibrary: Boolean = false,
    ): List<MangaDataClass> {
        // Some sources release the same chapter under multiple scanlators, which would
        // inflate a plain COUNT(*) — unread/download/chapter counts are computed from the
        // raw chapter rows below (via ChapterDedup) instead of as SQL aggregates, so
        // duplicate scanlator copies of the same chapter are only counted once.
        val lastReadAt = ChapterTable.lastReadAt.max().alias("last_read_at")
        val selectedColumns = MangaTable.columns + lastReadAt

        // the library updater has to keep the manga of every account up to date, not only the first account's
        val inLibrary =
            if (inAnyLibrary) {
                (MangaTable.inLibrary eq true) or (MangaTable.id inSubQuery UserMangaTable.allLibraryMangaIds())
            } else {
                MangaTable.inLibrary eq true
            }

        return transaction {
            // Fetch data from the MangaTable and join with the CategoryMangaTable, if a category is specified
            val query =
                if (categoryId == DEFAULT_CATEGORY_ID) {
                    MangaTable
                        .leftJoin(ChapterTable, { MangaTable.id }, { ChapterTable.manga })
                        .select(columns = selectedColumns)
                        .where { inLibrary and Category.uncategorizedOf(1) }
                } else {
                    MangaTable
                        .innerJoin(CategoryMangaTable)
                        .leftJoin(ChapterTable, { MangaTable.id }, { ChapterTable.manga })
                        .select(columns = selectedColumns)
                        .where { inLibrary and (CategoryMangaTable.category eq categoryId) }
                }

            val mangaRows = query.groupBy(*MangaTable.columns.toTypedArray()).toList()
            val mangaIds = mangaRows.map { it[MangaTable.id].value }

            val chaptersByManga =
                if (mangaIds.isEmpty()) {
                    emptyMap()
                } else {
                    ChapterTable
                        .select(ChapterTable.manga, ChapterTable.chapter_number, ChapterTable.name, ChapterTable.isRead, ChapterTable.isDownloaded)
                        .where { ChapterTable.manga inList mangaIds }
                        .toList()
                        .groupBy { it[ChapterTable.manga].value }
                }

            val chapterNumberOf: (ResultRow) -> Float = { it[ChapterTable.chapter_number] }
            val chapterNameOf: (ResultRow) -> String = { it[ChapterTable.name] }

            mangaRows.map { row ->
                val chapters = chaptersByManga[row[MangaTable.id].value].orEmpty()
                MangaTable
                    .toDataClass(row)
                    .copy(
                        lastReadAt = row[lastReadAt],
                        unreadCount = chapters.unreadChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isRead] }.toLong(),
                        downloadCount = chapters.downloadedChapterCount(chapterNumberOf, chapterNameOf) { it[ChapterTable.isDownloaded] }.toLong(),
                        chapterCount = chapters.distinctChapterCount(chapterNumberOf, chapterNameOf).toLong(),
                    )
            }
        }
    }

    /**
     * list of categories that a manga belongs to
     */
    fun getMangaCategories(mangaId: Int): List<CategoryDataClass> =
        transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where {
                    CategoryMangaTable.manga eq mangaId
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .map {
                    CategoryTable.toDataClass(it)
                }
        }

    fun getMangasCategories(mangaIDs: List<Int>): Map<Int, List<CategoryDataClass>> =
        buildMap {
            transaction {
                CategoryMangaTable
                    .innerJoin(CategoryTable)
                    .selectAll()
                    .where { CategoryMangaTable.manga inList mangaIDs }
                    .groupBy { it[CategoryMangaTable.manga] }
                    .forEach {
                        val mangaId = it.key.value
                        val categories = it.value

                        set(mangaId, categories.map { category -> CategoryTable.toDataClass(category) })
                    }
            }
        }
}

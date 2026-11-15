package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.global.impl.sync.SyncYomiSyncService
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.libraryOf
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.user.model.UserTable

object Category {
    /**
     * The new category will be placed at the end of the list
     */
    fun createCategory(name: String): Int = createCategories(listOf(name)).first()

    fun createCategories(names: List<String>): List<Int> =
        transaction {
            val categoryIdToName = getCategoryList().associate { it.id to it.name.lowercase() }

            val categoriesToCreate =
                names
                    .filter {
                        !it.equals(DEFAULT_CATEGORY_NAME, true)
                    }.filter { !categoryIdToName.values.contains(it.lowercase()) }

            val newCategoryIdsByName =
                CategoryTable
                    .batchInsert(categoriesToCreate) {
                        this[CategoryTable.name] = it
                        this[CategoryTable.order] = Int.MAX_VALUE
                    }.associate { it[CategoryTable.name] to it[CategoryTable.id].value }

            normalizeCategories()

            names.map {
                // creating a category named Default is illegal
                if (it.equals(DEFAULT_CATEGORY_NAME, true)) {
                    DEFAULT_CATEGORY_ID
                } else {
                    newCategoryIdsByName[it] ?: categoryIdToName.entries.find { (_, name) -> name.equals(it, true) }!!.key
                }
            }
        }

    fun updateCategory(
        categoryId: Int,
        name: String?,
        isDefault: Boolean?,
        includeInUpdate: Int?,
        includeInDownload: Int?,
    ) {
        transaction {
            if (categoryId != DEFAULT_CATEGORY_ID && isDefault != null) setDefaultFlag(1, listOf(categoryId), isDefault)
            CategoryTable.update({ CategoryTable.id eq categoryId }) {
                // the Default category can be renamed, no other category can take the name Default (backups skip it)
                if (name != null && (categoryId == DEFAULT_CATEGORY_ID || !name.equals(DEFAULT_CATEGORY_NAME, ignoreCase = true))) {
                    it[CategoryTable.name] = name
                }
                if (includeInUpdate != null) it[CategoryTable.includeInUpdate] = includeInUpdate
                if (includeInDownload != null) it[CategoryTable.includeInDownload] = includeInDownload
            }
        }
    }

    /**
     * Move the category from order number `from` to `to`
     */
    fun reorderCategory(
        from: Int,
        to: Int,
    ) {
        if (from == 0 || to == 0) return
        transaction {
            val categories =
                CategoryTable
                    .selectAll()
                    .where {
                        CategoryTable.id neq DEFAULT_CATEGORY_ID
                    }.orderBy(CategoryTable.order to SortOrder.ASC)
                    .toMutableList()
            categories.add(to - 1, categories.removeAt(from - 1))
            categories.forEachIndexed { index, cat ->
                CategoryTable.update({ CategoryTable.id eq cat[CategoryTable.id].value }) {
                    it[CategoryTable.order] = index + 1
                }
            }
            normalizeCategories()
        }
    }

    /**
     * Move the category to 1-based [position] among the categories of the account, ignoring raw order values. Only the
     * account's own categories count (plus the default one when it is shown): the positions the WebUI sends are indexes
     * into that list, other accounts' categories must not shift them.
     */
    fun moveCategoryToPosition(
        categoryId: Int,
        position: Int,
        userId: Int = 1,
    ) {
        require(position > 0) { "'position' must be > 0" }
        transaction {
            val showDefault = isDefaultCategoryVisible(userId)
            val categories =
                CategoryTable
                    .selectAll()
                    .where {
                        CategoryTable.ownedBy(userId) and (if (showDefault) Op.TRUE else CategoryTable.id neq defaultCategoryId(userId))
                    }.orderBy(CategoryTable.order to SortOrder.ASC, CategoryTable.id to SortOrder.ASC)
                    .toMutableList()
            val from = categories.indexOfFirst { it[CategoryTable.id].value == categoryId }
            if (from == -1) return@transaction
            categories.add((position - 1).coerceAtMost(categories.size - 1), categories.removeAt(from))
            categories.forEachIndexed { index, cat ->
                if (cat[CategoryTable.order] != index + 1) {
                    CategoryTable.update({ CategoryTable.id eq cat[CategoryTable.id].value }) {
                        it[CategoryTable.order] = index + 1
                    }
                }
            }
            normalizeCategories()
        }
    }

    fun removeCategory(categoryId: Int) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            val uid =
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.id eq categoryId }
                    .firstOrNull()
                    ?.get(CategoryTable.uid)
            CategoryTable.deleteWhere { CategoryTable.id eq categoryId }
            normalizeCategories()
            if (uid != null) {
                SyncYomiSyncService.rememberDeletedCategory(uid)
            }
        }
    }

    /** make sure category order numbers starts from 1 and is consecutive */
    fun normalizeCategories() {
        transaction {
            CategoryTable
                .selectAll()
                .orderBy(CategoryTable.order to SortOrder.ASC)
                .sortedWith(compareBy({ it[CategoryTable.order] }, { it[CategoryTable.id].value }))
                .forEachIndexed { index, cat ->
                    CategoryTable.update({ CategoryTable.id eq cat[CategoryTable.id].value }) {
                        it[CategoryTable.order] = index
                    }
                }
        }
    }

    private fun needsDefaultCategory(userId: Int = 1) =
        transaction {
            MangaTable
                .selectAll()
                .where { (if (userId == 1) MangaTable.inLibrary eq true else UserMangaTable.libraryOf(userId)) and uncategorizedOf(userId) }
                .empty()
                .not()
        }

    /**
     * The "Default" category of an account means "in none of my categories". For the first account it is the built-in
     * row with the id 0, every other account has a row of its own that holds the order and the hidden flag. The API shows
     * all of them as id 0 ([apiId]) and the manga of it are always worked out ([uncategorizedOf]), never linked.
     */
    fun defaultCategoryId(userId: Int): Int =
        if (userId == 1) {
            DEFAULT_CATEGORY_ID
        } else {
            transaction {
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.ownedBy(userId) and (CategoryTable.isBuiltIn eq true) }
                    .firstOrNull()
                    ?.get(CategoryTable.id)
                    ?.value
                    ?: CategoryTable
                        .insertAndGetId {
                            it[CategoryTable.name] = DEFAULT_CATEGORY_NAME
                            it[CategoryTable.isBuiltIn] = true
                            it[CategoryTable.isDefault] = true
                            it[CategoryTable.order] = Int.MAX_VALUE
                            it[CategoryTable.user] = EntityID(userId, UserTable)
                        }.value
                    .also { normalizeCategories() }
            }
        }

    /** The rows that are the Default category of an account (all of them are shown as the id 0). */
    fun defaultRowIds(): Set<Int> =
        transaction {
            CategoryTable
                .select(CategoryTable.id, CategoryTable.name)
                .where { (CategoryTable.id eq DEFAULT_CATEGORY_ID) or (CategoryTable.isBuiltIn eq true) }
                .map { it[CategoryTable.id].value }
                .toSet()
        }

    /** The row is the Default category of its account, whatever it is named. */
    fun isDefaultRow(row: ResultRow): Boolean = row[CategoryTable.id].value == DEFAULT_CATEGORY_ID || row[CategoryTable.isBuiltIn]

    /** The id the API shows: the Default category of every account is the id 0. */
    fun apiId(row: ResultRow): Int = if (isDefaultRow(row)) DEFAULT_CATEGORY_ID else row[CategoryTable.id].value

    /** The id of the row behind an id the API was given. */
    fun resolveId(
        apiCategoryId: Int,
        userId: Int,
    ): Int = if (apiCategoryId == DEFAULT_CATEGORY_ID) defaultCategoryId(userId) else apiCategoryId

    const val DEFAULT_CATEGORY_ID = 0

    /**
     * The manga that are in none of the categories of the account (the Default category is not one of them). Category
     * links are shared by all accounts, so a link into the category of another account (for example a shared library)
     * must not count as categorized here.
     */
    fun uncategorizedOf(userId: Int): Op<Boolean> =
        MangaTable.id notInSubQuery
            CategoryMangaTable
                .select(CategoryMangaTable.manga)
                .where {
                    CategoryMangaTable.category inSubQuery
                        CategoryTable
                            .select(CategoryTable.id)
                            .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq DEFAULT_CATEGORY_ID) and (CategoryTable.isBuiltIn eq false) }
                }

    /** The manga that were put into the Default category of the account on purpose, besides the ones without a category. */
    fun linkedToDefaultOf(userId: Int): Op<Boolean> =
        MangaTable.id inSubQuery
            CategoryMangaTable
                .select(CategoryMangaTable.manga)
                .where {
                    CategoryMangaTable.category inSubQuery
                        CategoryTable
                            .select(CategoryTable.id)
                            .where { CategoryTable.ownedBy(userId) and ((CategoryTable.id eq DEFAULT_CATEGORY_ID) or (CategoryTable.isBuiltIn eq true)) }
                }

    /** The manga of the Default category of the account: the ones without a category and the ones put into it on purpose. */
    fun defaultMembersOf(userId: Int): Op<Boolean> = uncategorizedOf(userId) or linkedToDefaultOf(userId)

    const val DEFAULT_CATEGORY_NAME = "Default"
    private const val DEFAULT_HIDDEN_META_KEY = "default_category_hidden"
    private const val DEFAULT_RESTORED_META_KEY = "default_category_restored"

    /**
     * The default category can be "deleted" by the user: its manga move into another category and it stays hidden,
     * unless manga without a category exist again or there is no other category left.
     */
    fun isDefaultCategoryVisible(userId: Int = 1): Boolean =
        transaction {
            val defaultId = defaultCategoryId(userId)
            val hidden =
                CategoryMetaTable
                    .selectAll()
                    .where { (CategoryMetaTable.ref eq defaultId) and (CategoryMetaTable.key eq DEFAULT_HIDDEN_META_KEY) }
                    .empty()
                    .not()
            !hidden ||
                needsDefaultCategory(userId) ||
                !CategoryMangaTable.selectAll().where { CategoryMangaTable.category eq defaultId }.empty() ||
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq defaultId) }
                    .empty()
        }

    /** Moves the manga the account has in its library without a category into its first other category and hides the default. */
    fun removeDefaultCategory(userId: Int): List<Int> =
        transaction {
            val target =
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq defaultCategoryId(userId)) }
                    .orderBy(CategoryTable.order to SortOrder.ASC, CategoryTable.id to SortOrder.ASC)
                    .firstOrNull()
                    ?.get(CategoryTable.id)
                    ?.value
            require(target != null) { "The default category can only be deleted when there are other categories" }

            val mangaIds =
                MangaTable
                    .select(MangaTable.id)
                    .where { UserMangaTable.libraryOf(userId) and uncategorizedOf(userId) }
                    .map { it[MangaTable.id].value }
            CategoryMangaTable.batchInsert(mangaIds) {
                this[CategoryMangaTable.category] = target
                this[CategoryMangaTable.manga] = it
            }

            // a manga that was put into the Default category on purpose keeps its other categories and leaves the Default one
            CategoryMangaTable.deleteWhere { CategoryMangaTable.category eq defaultCategoryId(userId) }

            modifyMeta(defaultCategoryId(userId), DEFAULT_HIDDEN_META_KEY, "true")
            CategoryMetaTable.deleteWhere { (CategoryMetaTable.ref eq defaultCategoryId(userId)) and (CategoryMetaTable.key eq DEFAULT_RESTORED_META_KEY) }
            ensureFallbackDefault(userId)
            mangaIds
        }

    /** The account deleted its Default category (it stays hidden, see [removeDefaultCategory]). */
    fun isDefaultDeleted(userId: Int): Boolean =
        transaction {
            val defaultId = defaultCategoryId(userId)
            !CategoryMetaTable
                .selectAll()
                .where { (CategoryMetaTable.ref eq defaultId) and (CategoryMetaTable.key eq DEFAULT_HIDDEN_META_KEY) }
                .empty()
        }

    /**
     * Brings a deleted Default category back, as the empty category it was left as (its manga were moved to other
     * categories when it was deleted). Its name is "Default" again, whatever it was renamed to before.
     */
    fun restoreDefaultCategory(userId: Int): ResultRow =
        transaction {
            val defaultId = defaultCategoryId(userId)
            CategoryMetaTable.deleteWhere { (CategoryMetaTable.ref eq defaultId) and (CategoryMetaTable.key eq DEFAULT_HIDDEN_META_KEY) }
            // Default is the default one for new manga again: the category that stood in for it (flagged on its own or picked
            // by ensureFallbackDefault) gives the mark back
            ownCategoryRows(userId)
                .filter { it[CategoryTable.isDefault] }
                .forEach { other -> CategoryTable.update({ CategoryTable.id eq other[CategoryTable.id] }) { it[isDefault] = false } }
            CategoryTable.update({ CategoryTable.id eq defaultId }) {
                it[name] = DEFAULT_CATEGORY_NAME
                it[isDefault] = true
            }
            // the library hides an empty Default tab, unless the user brought it back on purpose
            modifyMeta(defaultId, DEFAULT_RESTORED_META_KEY, "true")
            CategoryTable.selectAll().where { CategoryTable.id eq defaultId }.first()
        }

    /** The rows of the categories of the account that are not its Default category, in the order of the list. */
    private fun ownCategoryRows(userId: Int): List<ResultRow> =
        CategoryTable
            .selectAll()
            .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq DEFAULT_CATEGORY_ID) and (CategoryTable.isBuiltIn eq false) }
            .orderBy(CategoryTable.order to SortOrder.ASC, CategoryTable.id to SortOrder.ASC)
            .toList()

    /**
     * While the Default category is deleted, one of the other categories takes its place: manga without a chosen category
     * go into it. This is the category that is marked as the default one for new manga; if there is none, the one at the
     * top of the list becomes it.
     */
    fun ensureFallbackDefault(userId: Int) {
        transaction {
            if (!isDefaultDeleted(userId)) {
                return@transaction
            }

            val categories = ownCategoryRows(userId)
            if (categories.isNotEmpty() && categories.none { it[CategoryTable.isDefault] }) {
                CategoryTable.update({ CategoryTable.id eq categories.first()[CategoryTable.id] }) { it[isDefault] = true }
            }
        }
    }

    /**
     * Marks categories as the default ones for new manga, or takes the mark away. While the Default category is deleted
     * exactly one category has the mark: marking another one moves it, and the last one cannot lose it.
     */
    fun setDefaultFlag(
        userId: Int,
        categoryIds: List<Int>,
        isDefault: Boolean,
    ) {
        transaction {
            if (isDefaultDeleted(userId)) {
                if (isDefault) {
                    require(categoryIds.size == 1) { "Only one category can be the default one while the Default category is deleted" }
                    ownCategoryRows(userId)
                        .filter { it[CategoryTable.id].value != categoryIds.first() && it[CategoryTable.isDefault] }
                        .forEach { other -> CategoryTable.update({ CategoryTable.id eq other[CategoryTable.id] }) { it[CategoryTable.isDefault] = false } }
                } else {
                    val remaining = ownCategoryRows(userId).filter { it[CategoryTable.id].value !in categoryIds && it[CategoryTable.isDefault] }
                    val isOwn = ownCategoryRows(userId).any { it[CategoryTable.id].value in categoryIds }
                    require(!isOwn || remaining.isNotEmpty()) {
                        "One category has to be the default one while the Default category is deleted: choose another one instead"
                    }
                }
            }

            CategoryTable.update({ CategoryTable.id inList categoryIds }) { it[CategoryTable.isDefault] = isDefault }
        }
    }

    fun getCategoryList(): List<CategoryDataClass> =
        transaction {
            CategoryTable
                .selectAll()
                .orderBy(CategoryTable.order to SortOrder.ASC)
                .let {
                    if (needsDefaultCategory()) {
                        it
                    } else {
                        it.andWhere { CategoryTable.id neq DEFAULT_CATEGORY_ID }
                    }
                }.map {
                    CategoryTable.toDataClass(it)
                }
        }

    fun getCategoryById(categoryId: Int): CategoryDataClass? =
        transaction {
            CategoryTable.selectAll().where { CategoryTable.id eq categoryId }.firstOrNull()?.let {
                CategoryTable.toDataClass(it)
            }
        }

    fun getCategorySize(categoryId: Int): Int =
        transaction {
            if (categoryId == DEFAULT_CATEGORY_ID) {
                MangaTable
                    .leftJoin(CategoryMangaTable)
                    .selectAll()
                    .where { MangaTable.inLibrary eq true }
                    .andWhere { CategoryMangaTable.manga.isNull() }
            } else {
                CategoryMangaTable
                    .leftJoin(MangaTable)
                    .selectAll()
                    .where { CategoryMangaTable.category eq categoryId }
                    .andWhere { MangaTable.inLibrary eq true }
            }.count().toInt()
        }

    fun getCategoryMetaMap(categoryId: Int): Map<String, String> =
        transaction {
            CategoryMetaTable
                .selectAll()
                .where { CategoryMetaTable.ref eq categoryId }
                .associate { it[CategoryMetaTable.key] to it[CategoryMetaTable.value] }
        }

    fun getCategoriesMetaMaps(ids: List<Int>): Map<Int, Map<String, String>> =
        transaction {
            CategoryMetaTable
                .selectAll()
                .where { CategoryMetaTable.ref inList ids }
                .groupBy { it[CategoryMetaTable.ref].value }
                .mapValues { it.value.associate { it[CategoryMetaTable.key] to it[CategoryMetaTable.value] } }
                .withDefault { emptyMap() }
        }

    fun modifyMeta(
        categoryId: Int,
        key: String,
        value: String,
    ) {
        modifyCategoriesMetas(mapOf(categoryId to mapOf(key to value)))
    }

    fun modifyCategoriesMetas(metaByCategoryId: Map<Int, Map<String, String>>) {
        transaction {
            val categoryIds = metaByCategoryId.keys
            val metaKeys = metaByCategoryId.flatMap { it.value.keys }

            val dbMetaByCategoryId =
                CategoryMetaTable
                    .selectAll()
                    .where { (CategoryMetaTable.ref inList categoryIds) and (CategoryMetaTable.key inList metaKeys) }
                    .groupBy { it[CategoryMetaTable.ref].value }

            val existingMetaByMetaId =
                categoryIds.flatMap { categoryId ->
                    val dbMetaByKey = dbMetaByCategoryId[categoryId].orEmpty().associateBy { it[CategoryMetaTable.key] }
                    val existingMetas = metaByCategoryId[categoryId].orEmpty().filter { (key) -> key in dbMetaByKey.keys }

                    existingMetas.map { entry ->
                        val metaId = dbMetaByKey[entry.key]!![CategoryMetaTable.id].value

                        metaId to entry
                    }
                }

            val newMetaByCategoryId =
                categoryIds.flatMap { categoryID ->
                    val dbMetaByKey = dbMetaByCategoryId[categoryID].orEmpty().associateBy { it[CategoryMetaTable.key] }

                    metaByCategoryId[categoryID]
                        .orEmpty()
                        .filter { entry -> entry.key !in dbMetaByKey.keys }
                        .map { entry -> categoryID to entry }
                }

            if (existingMetaByMetaId.isNotEmpty()) {
                BatchUpdateStatement(CategoryMetaTable)
                    .apply {
                        existingMetaByMetaId.forEach { (metaId, entry) ->
                            addBatch(EntityID(metaId, CategoryMetaTable))
                            this[CategoryMetaTable.value] = entry.value
                        }
                    }.toExecutable()
                    .execute(this@transaction)
            }

            if (newMetaByCategoryId.isNotEmpty()) {
                CategoryMetaTable.batchInsert(newMetaByCategoryId) { (categoryId, entry) ->
                    this[CategoryMetaTable.ref] = EntityID(categoryId, CategoryTable)
                    this[CategoryMetaTable.key] = entry.key
                    this[CategoryMetaTable.value] = entry.value
                }
            }
        }
    }
}

package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.LibraryShareTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.libraryMangaIdsOf
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.server.user.model.UserTable
import java.time.Instant

/**
 * Sharing a library (or some categories) with another account takes two approvals: the sender names the recipient by
 * exact username (there is no way to browse accounts) and the recipient has to accept before anything is copied.
 */
object LibraryShare {
    enum class Scope { LIBRARY, CATEGORIES }

    enum class Status { PENDING, ACCEPTED, DECLINED, CANCELLED }

    data class Share(
        val id: Int,
        val senderId: Int,
        val senderUsername: String,
        val recipientId: Int,
        val recipientUsername: String,
        val scope: Scope,
        val categoryNames: List<String>,
        val mangaCount: Int,
        val status: Status,
        val createdAt: Long,
        val respondedAt: Long,
    )

    private fun ResultRow.categoryIdList() = this[LibraryShareTable.categoryIds].split(",").mapNotNull { it.toIntOrNull() }

    fun create(
        senderId: Int,
        recipientUsername: String,
        scope: Scope,
        categoryIds: List<Int>,
    ): Int =
        transaction {
            val recipientId =
                UserTable
                    .selectAll()
                    .where { UserTable.username eq recipientUsername.trim() }
                    .firstOrNull()
                    ?.get(UserTable.id)
                    ?.value
            require(recipientId != null) { "No account with that username" }
            require(recipientId != senderId) { "You can't share with yourself" }

            val ids = if (scope == Scope.CATEGORIES) categoryIds.distinct().sorted() else emptyList()
            if (scope == Scope.CATEGORIES) {
                require(ids.isNotEmpty()) { "Choose at least one category" }
                val owned =
                    CategoryTable
                        .selectAll()
                        .where { (CategoryTable.id inList ids) and CategoryTable.ownedBy(senderId) and (CategoryTable.id neq 0) }
                        .count()
                require(owned == ids.size.toLong()) { "Unknown category" }
            }

            val idsText = ids.joinToString(",")
            val duplicate =
                LibraryShareTable
                    .selectAll()
                    .where {
                        (LibraryShareTable.sender eq senderId) and
                            (LibraryShareTable.recipient eq recipientId) and
                            (LibraryShareTable.scope eq scope.name) and
                            (LibraryShareTable.categoryIds eq idsText) and
                            (LibraryShareTable.status eq Status.PENDING.name)
                    }.empty()
                    .not()
            require(!duplicate) { "This share is already waiting for approval" }

            LibraryShareTable
                .insertAndGetId {
                    it[sender] = EntityID(senderId, UserTable)
                    it[recipient] = EntityID(recipientId, UserTable)
                    it[LibraryShareTable.scope] = scope.name
                    it[LibraryShareTable.categoryIds] = idsText
                    it[createdAt] = Instant.now().epochSecond
                }.value
        }

    /** The shares the account sent or received, newest first. */
    fun list(userId: Int): List<Share> =
        transaction {
            LibraryShareTable
                .selectAll()
                .where { (LibraryShareTable.sender eq userId) or (LibraryShareTable.recipient eq userId) }
                .orderBy(LibraryShareTable.id to SortOrder.DESC)
                .map { toShare(it) }
        }

    fun get(
        shareId: Int,
        userId: Int,
    ): Share =
        list(userId).firstOrNull { it.id == shareId } ?: throw IllegalArgumentException("Unknown share")

    fun cancel(
        shareId: Int,
        userId: Int,
    ) = finish(shareId, Status.CANCELLED) { it[LibraryShareTable.sender].value == userId }

    fun decline(
        shareId: Int,
        userId: Int,
    ) = finish(shareId, Status.DECLINED) { it[LibraryShareTable.recipient].value == userId }

    /** Copies what the sender shares into the recipient's account. Returns the number of manga added. */
    fun accept(
        shareId: Int,
        userId: Int,
    ): Int =
        transaction {
            val row = pending(shareId) { it[LibraryShareTable.recipient].value == userId }
            val senderId = row[LibraryShareTable.sender].value
            val scope = Scope.valueOf(row[LibraryShareTable.scope])

            val senderCategories =
                CategoryTable
                    .selectAll()
                    .where { CategoryTable.ownedBy(senderId) and (CategoryTable.id neq 0) }
                    .filter { scope == Scope.LIBRARY || it[CategoryTable.id].value in row.categoryIdList() }
            val senderCategoryIds = senderCategories.map { it[CategoryTable.id].value }

            val senderLibrary = UserMangaTable.libraryMangaIdsOf(senderId).map { it[UserMangaTable.manga].value }.toSet()
            val links =
                CategoryMangaTable
                    .selectAll()
                    .where { CategoryMangaTable.category inList senderCategoryIds }
                    .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                    .filter { (_, mangaId) -> mangaId in senderLibrary }
            val mangaIds = if (scope == Scope.LIBRARY) senderLibrary else links.map { it.second }.toSet()

            val now = Instant.now().epochSecond
            val alreadyOwned =
                UserMangaTable
                    .selectAll()
                    .where { (UserMangaTable.user eq userId) and (UserMangaTable.manga inList mangaIds) }
                    .associateBy { it[UserMangaTable.manga].value }
            var added = 0
            mangaIds.forEach { mangaId ->
                val own = alreadyOwned[mangaId]
                if (own == null) {
                    UserMangaTable.insert {
                        it[user] = EntityID(userId, UserTable)
                        it[manga] = EntityID(mangaId, MangaTable)
                        it[inLibrary] = true
                        it[inLibraryAt] = now
                    }
                    added++
                } else if (!own[UserMangaTable.inLibrary]) {
                    UserMangaTable.update({ (UserMangaTable.user eq userId) and (UserMangaTable.manga eq mangaId) }) {
                        it[inLibrary] = true
                        it[inLibraryAt] = now
                    }
                    added++
                }
            }
            if (userId == 1 && mangaIds.isNotEmpty()) {
                // the first account is mirrored into the manga table
                MangaTable.update({ (MangaTable.id inList mangaIds) and (MangaTable.inLibrary eq false) }) {
                    it[inLibrary] = true
                    it[inLibraryAt] = now
                }
            }

            // categories are matched by name among the recipient's own, missing ones are created
            val ownCategories = CategoryTable.selectAll().where { CategoryTable.ownedBy(userId) }.associateBy { it[CategoryTable.name].lowercase() }
            val targetByName = mutableMapOf<String, Int>()
            val targetOf =
                senderCategories.associate { cat ->
                    val name = cat[CategoryTable.name]
                    cat[CategoryTable.id].value to
                        targetByName.getOrPut(name.lowercase()) {
                            ownCategories[name.lowercase()]?.get(CategoryTable.id)?.value
                                ?: CategoryTable
                                    .insertAndGetId {
                                        it[CategoryTable.name] = name
                                        it[CategoryTable.order] = Int.MAX_VALUE
                                        it[user] = EntityID(userId, UserTable)
                                    }.value
                        }
                }
            Category.normalizeCategories()

            val existingLinks =
                CategoryMangaTable
                    .selectAll()
                    .where { CategoryMangaTable.category inList targetOf.values.toList() }
                    .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                    .toSet()
            links
                .map { (categoryId, mangaId) -> targetOf.getValue(categoryId) to mangaId }
                .distinct()
                .filter { it !in existingLinks }
                .forEach { (categoryId, mangaId) ->
                    CategoryMangaTable.insert {
                        it[category] = categoryId
                        it[manga] = mangaId
                    }
                }

            // accounts other than the first only see manga that are in one of their categories, so manga without one go
            // where the WebUI puts newly added manga: into the categories flagged as default
            val ownCategoryIds =
                CategoryTable
                    .select(CategoryTable.id)
                    .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq 0) }
                    .map { it[CategoryTable.id].value }
            val categorized =
                CategoryMangaTable
                    .select(CategoryMangaTable.manga)
                    .where { CategoryMangaTable.category inList ownCategoryIds }
                    .map { it[CategoryMangaTable.manga].value }
                    .toSet()
            val defaultCategoryIds =
                CategoryTable
                    .select(CategoryTable.id)
                    .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq 0) and (CategoryTable.isDefault eq true) }
                    .map { it[CategoryTable.id].value }
            (mangaIds - categorized).forEach { mangaId ->
                defaultCategoryIds.forEach { categoryId ->
                    CategoryMangaTable.insert {
                        it[category] = categoryId
                        it[manga] = mangaId
                    }
                }
            }

            markDone(shareId, Status.ACCEPTED)
            added
        }

    private fun pending(
        shareId: Int,
        allowed: (ResultRow) -> Boolean,
    ): ResultRow {
        val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull()
        require(row != null && allowed(row)) { "Unknown share" }
        require(row[LibraryShareTable.status] == Status.PENDING.name) { "This share was already answered" }
        return row
    }

    private fun markDone(
        shareId: Int,
        status: Status,
    ) {
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
            it[LibraryShareTable.status] = status.name
            it[respondedAt] = Instant.now().epochSecond
        }
    }

    private fun finish(
        shareId: Int,
        status: Status,
        allowed: (ResultRow) -> Boolean,
    ) = transaction {
        pending(shareId, allowed)
        markDone(shareId, status)
    }

    private fun toShare(row: ResultRow): Share {
        val senderId = row[LibraryShareTable.sender].value
        val scope = Scope.valueOf(row[LibraryShareTable.scope])
        val categoryIds = row.categoryIdList()
        val senderLibrary = UserMangaTable.libraryMangaIdsOf(senderId).map { it[UserMangaTable.manga].value }.toSet()
        val categoryNames =
            CategoryTable
                .select(CategoryTable.name)
                .where { CategoryTable.id inList categoryIds }
                .map { it[CategoryTable.name] }
        val mangaCount =
            if (scope == Scope.LIBRARY) {
                senderLibrary.size
            } else {
                CategoryMangaTable
                    .select(CategoryMangaTable.manga)
                    .where { CategoryMangaTable.category inList categoryIds }
                    .map { it[CategoryMangaTable.manga].value }
                    .filter { it in senderLibrary }
                    .toSet()
                    .size
            }
        fun username(id: Int) = UserTable.selectAll().where { UserTable.id eq id }.first()[UserTable.username]
        return Share(
            id = row[LibraryShareTable.id].value,
            senderId = senderId,
            senderUsername = username(senderId),
            recipientId = row[LibraryShareTable.recipient].value,
            recipientUsername = username(row[LibraryShareTable.recipient].value),
            scope = scope,
            categoryNames = categoryNames,
            mangaCount = mangaCount,
            status = Status.valueOf(row[LibraryShareTable.status]),
            createdAt = row[LibraryShareTable.createdAt],
            respondedAt = row[LibraryShareTable.respondedAt],
        )
    }
}

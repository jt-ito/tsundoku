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
import suwayomi.tachidesk.manga.model.table.LibraryShareDeliveredTable
import suwayomi.tachidesk.manga.model.table.LibraryShareTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.manga.model.table.libraryMangaIdsOf
import suwayomi.tachidesk.manga.model.table.ownedBy
import suwayomi.tachidesk.server.user.model.UserTable
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val synced: Boolean,
        val autoSync: Boolean,
        val lastSyncedAt: Long,
        val pairedWith: Int?,
        val twoWayStatus: Status?,
    )

    private fun ResultRow.categoryIdList() = this[LibraryShareTable.categoryIds].split(",").mapNotNull { it.toIntOrNull() }

    /**
     * The manga in the library of the account. The first account is also mirrored into the manga table, and a
     * library that was never touched by the per account code only exists there, so it counts when the account has no
     * row of its own for the manga.
     */
    private fun libraryOf(userId: Int): Set<Int> {
        val own = UserMangaTable.libraryMangaIdsOf(userId).map { it[UserMangaTable.manga].value }.toSet()
        if (userId != 1) return own

        val withRow = UserMangaTable.select(UserMangaTable.manga).where { UserMangaTable.user eq userId }.map { it[UserMangaTable.manga].value }.toSet()
        val legacy = MangaTable.select(MangaTable.id).where { MangaTable.inLibrary eq true }.map { it[MangaTable.id].value }
        return own + legacy.filter { it !in withRow }
    }

    fun create(
        senderId: Int,
        recipientUsername: String,
        scope: Scope,
        categoryIds: List<Int>,
        synced: Boolean = false,
        pairedWith: Int? = null,
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

            require(scope != Scope.LIBRARY || libraryOf(senderId).isNotEmpty()) { "Your library is empty, there is nothing to share" }

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
                            (LibraryShareTable.synced eq synced) and
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
                    it[LibraryShareTable.synced] = synced
                    it[LibraryShareTable.pairedWith] = pairedWith
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

    /**
     * Copies what the sender shares into the recipient's account. Returns the number of manga added.
     * [autoSync] only counts for a share the sender made synced.
     */
    fun accept(
        shareId: Int,
        userId: Int,
        autoSync: Boolean = false,
    ): Int =
        transaction {
            val row = pending(shareId) { it[LibraryShareTable.recipient].value == userId }
            val added = deliver(row)
            markDone(shareId, Status.ACCEPTED)
            LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
                it[LibraryShareTable.autoSync] = autoSync && row[LibraryShareTable.synced]
            }
            added
        }

    /** The latest two way request that answers this share, if any. A declined or cancelled one can be asked again. */
    private fun twoWayRequestOf(shareId: Int): ResultRow? =
        LibraryShareTable
            .selectAll()
            .where { LibraryShareTable.pairedWith eq shareId }
            .orderBy(LibraryShareTable.id to SortOrder.DESC)
            .firstOrNull()

    /**
     * The recipient of a synced share asks the sender to receive their library changes as well. It is a synced share
     * of the other direction that the original sender has to accept.
     */
    fun requestTwoWay(
        shareId: Int,
        userId: Int,
    ): Int =
        transaction {
            val row = activeSynced(shareId, userId)
            require(row[LibraryShareTable.pairedWith] == null) { "This share is already the two way part of another share" }
            val existing = twoWayRequestOf(shareId)?.get(LibraryShareTable.status)
            require(existing == null || existing == Status.DECLINED.name || existing == Status.CANCELLED.name) {
                "A two way request for this share already exists"
            }

            val scope = Scope.valueOf(row[LibraryShareTable.scope])
            // the shared categories of the sender were matched by name among the recipient's own
            val names =
                CategoryTable
                    .select(CategoryTable.name)
                    .where { CategoryTable.id inList row.categoryIdList() }
                    .map { it[CategoryTable.name].lowercase() }
            val ownIds =
                if (scope == Scope.CATEGORIES) {
                    CategoryTable
                        .selectAll()
                        .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq 0) }
                        .filter { it[CategoryTable.name].lowercase() in names }
                        .map { it[CategoryTable.id].value }
                } else {
                    emptyList()
                }
            val senderName = UserTable.selectAll().where { UserTable.id eq row[LibraryShareTable.sender].value }.first()[UserTable.username]
            create(userId, senderName, scope, ownIds, synced = true, pairedWith = shareId)
        }

    private fun activeSynced(
        shareId: Int,
        userId: Int,
    ): ResultRow {
        val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull()
        require(row != null && row[LibraryShareTable.recipient].value == userId) { "Unknown share" }
        require(row[LibraryShareTable.status] == Status.ACCEPTED.name && row[LibraryShareTable.synced]) { "This share is not synced" }
        return row
    }

    /** The recipient decides whether a synced share follows the sender by itself. */
    fun setAutoSync(
        shareId: Int,
        userId: Int,
        autoSync: Boolean,
    ) = transaction {
        activeSynced(shareId, userId)
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) { it[LibraryShareTable.autoSync] = autoSync }
    }

    /** Brings a synced share up to date now. Returns the number of manga added. */
    fun syncNow(
        shareId: Int,
        userId: Int,
    ): Int = transaction { deliver(activeSynced(shareId, userId)) }

    /** The sender stops syncing, what was copied stays with the recipient. */
    private fun stopSync(
        shareId: Int,
        userId: Int,
    ) = transaction {
        val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull()
        require(row != null && row[LibraryShareTable.sender].value == userId) { "Unknown share" }
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
            it[synced] = false
            it[LibraryShareTable.autoSync] = false
        }
    }

    /** Pending shares are cancelled, a share that is already accepted just stops syncing. */
    fun cancelOrStop(
        shareId: Int,
        userId: Int,
    ) {
        val row = transaction { LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull() }
        if (row != null && row[LibraryShareTable.status] == Status.ACCEPTED.name) stopSync(shareId, userId) else cancel(shareId, userId)
    }

    /**
     * Synced shares follow the sender in three ways:
     * - push: a change of the sender's library or categories asks for a sync right away ([requestSync]),
     * - pull: the recipient logging in or refreshing their session asks for one too, so opening the app catches up,
     * - a slow timer as the safety net for changes that don't go through those paths (REST, backup restore, ...).
     */
    fun startAutoSync() {
        val timer = Timer("library-share-sync", true)
        timer.schedule(
            object : TimerTask() {
                override fun run() = syncAll()
            },
            AUTO_SYNC_INTERVAL_MINUTES * 60_000,
            AUTO_SYNC_INTERVAL_MINUTES * 60_000,
        )
    }

    private val syncExecutor =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "library-share-sync-now").apply { isDaemon = true } }
    private val pendingSyncs = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Syncs the automatic shares the account sends or receives, shortly from now. Many changes in a row (adding fifty
     * manga one by one) are collected into one sync. Never blocks or fails the caller.
     */
    fun requestSync(userId: Int) {
        if (pendingSyncs.add(userId)) {
            syncExecutor.schedule(
                {
                    pendingSyncs.remove(userId)
                    syncInvolving(userId)
                },
                SYNC_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun syncInvolving(userId: Int) = syncShares { (LibraryShareTable.sender eq userId) or (LibraryShareTable.recipient eq userId) }

    fun syncAll() = syncShares { Op.TRUE }

    private fun syncShares(accounts: () -> Op<Boolean>) {
        val shareIds =
            runCatching {
                transaction {
                    LibraryShareTable
                        .select(LibraryShareTable.id)
                        .where {
                            (LibraryShareTable.status eq Status.ACCEPTED.name) and
                                (LibraryShareTable.synced eq true) and
                                (LibraryShareTable.autoSync eq true) and
                                accounts()
                        }.map { it[LibraryShareTable.id].value }
                }
            }.getOrDefault(emptyList())
        shareIds.forEach { id ->
            runCatching {
                transaction {
                    val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq id }.first()
                    deliver(row)
                }
            }
        }
    }

    private const val SYNC_DELAY_SECONDS = 2L

    private const val AUTO_SYNC_INTERVAL_MINUTES = 15L

    private fun delivered(
        shareId: Int,
        kind: String,
    ): Set<Int> =
        LibraryShareDeliveredTable
            .select(LibraryShareDeliveredTable.ref)
            .where { (LibraryShareDeliveredTable.share eq shareId) and (LibraryShareDeliveredTable.kind eq kind) }
            .map { it[LibraryShareDeliveredTable.ref] }
            .toSet()

    private fun markDelivered(
        shareId: Int,
        kind: String,
        refs: Collection<Int>,
    ) {
        refs.forEach { ref ->
            LibraryShareDeliveredTable.insert {
                it[share] = EntityID(shareId, LibraryShareTable)
                it[LibraryShareDeliveredTable.kind] = kind
                it[LibraryShareDeliveredTable.ref] = ref
            }
        }
    }

    /**
     * Copies the manga and categories of the sender that this share has not delivered yet. Reading state is never
     * copied. What was delivered once is not delivered again, so a manga the recipient removed does not come back.
     */
    private fun deliver(row: ResultRow): Int {
        val shareId = row[LibraryShareTable.id].value
        val userId = row[LibraryShareTable.recipient].value
        val senderId = row[LibraryShareTable.sender].value
        val scope = Scope.valueOf(row[LibraryShareTable.scope])

        val senderCategories =
            CategoryTable
                .selectAll()
                .where { CategoryTable.ownedBy(senderId) and (CategoryTable.id neq 0) }
                .filter { !it[CategoryTable.name].equals(Category.DEFAULT_CATEGORY_NAME, ignoreCase = true) }
                .filter { scope == Scope.LIBRARY || it[CategoryTable.id].value in row.categoryIdList() }
        val senderCategoryIds = senderCategories.map { it[CategoryTable.id].value }

        val senderLibrary = libraryOf(senderId)
        val links =
            CategoryMangaTable
                .selectAll()
                .where { CategoryMangaTable.category inList senderCategoryIds }
                .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                .filter { (_, mangaId) -> mangaId in senderLibrary }
        val mangaIds = if (scope == Scope.LIBRARY) senderLibrary else links.map { it.second }.toSet()

        val newManga = mangaIds - delivered(shareId, "MANGA")
        val newCategoryIds = senderCategoryIds.toSet() - delivered(shareId, "CATEGORY")
        val newLinks = links.filter { (categoryId, mangaId) -> mangaId in newManga || categoryId in newCategoryIds }

        val now = Instant.now().epochSecond
        val alreadyOwned =
            UserMangaTable
                .selectAll()
                .where { (UserMangaTable.user eq userId) and (UserMangaTable.manga inList newManga) }
                .associateBy { it[UserMangaTable.manga].value }
        // manga the recipient already has are neither added again nor counted as added
        val libraryBefore = libraryOf(userId)
        var added = 0
        newManga.forEach { mangaId ->
            val own = alreadyOwned[mangaId]
            if (own == null) {
                UserMangaTable.insert {
                    it[user] = EntityID(userId, UserTable)
                    it[manga] = EntityID(mangaId, MangaTable)
                    it[inLibrary] = true
                    it[inLibraryAt] = now
                }
                if (mangaId !in libraryBefore) added++
            } else if (!own[UserMangaTable.inLibrary]) {
                UserMangaTable.update({ (UserMangaTable.user eq userId) and (UserMangaTable.manga eq mangaId) }) {
                    it[inLibrary] = true
                    it[inLibraryAt] = now
                }
                if (mangaId !in libraryBefore) added++
            }
        }
        if (userId == 1 && newManga.isNotEmpty()) {
            // the first account is mirrored into the manga table
            MangaTable.update({ (MangaTable.id inList newManga) and (MangaTable.inLibrary eq false) }) {
                it[inLibrary] = true
                it[inLibraryAt] = now
            }
        }

        // categories are matched by name among the recipient's own, missing ones are created
        val ownCategories = CategoryTable.selectAll().where { CategoryTable.ownedBy(userId) }.associateBy { it[CategoryTable.name].lowercase() }
        val categoriesToPlace = newCategoryIds + newLinks.map { it.first }
        val targetByName = mutableMapOf<String, Int>()
        val targetOf =
            senderCategories.filter { it[CategoryTable.id].value in categoriesToPlace }.associate { cat ->
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

        val recipientLibrary = libraryOf(userId)
        val existingLinks =
            CategoryMangaTable
                .selectAll()
                .where { CategoryMangaTable.category inList targetOf.values.toList() }
                .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                .toSet()
        newLinks
            .filter { (_, mangaId) -> mangaId in recipientLibrary }
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
        (newManga - categorized).forEach { mangaId ->
            defaultCategoryIds.forEach { categoryId ->
                CategoryMangaTable.insert {
                    it[category] = categoryId
                    it[manga] = mangaId
                }
            }
        }

        markDelivered(shareId, "MANGA", newManga)
        markDelivered(shareId, "CATEGORY", newCategoryIds)
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) { it[lastSyncedAt] = now }
        return added
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
        val senderLibrary = libraryOf(senderId)
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
            synced = row[LibraryShareTable.synced],
            autoSync = row[LibraryShareTable.autoSync],
            lastSyncedAt = row[LibraryShareTable.lastSyncedAt],
            pairedWith = row[LibraryShareTable.pairedWith],
            twoWayStatus = twoWayRequestOf(row[LibraryShareTable.id].value)?.let { Status.valueOf(it[LibraryShareTable.status]) },
        )
    }
}

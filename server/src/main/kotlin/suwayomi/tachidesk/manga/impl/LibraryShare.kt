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
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.LibraryShareCategoryTable
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
import java.util.concurrent.ScheduledFuture
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
        val mirror: Boolean,
        val proposedSynced: Boolean?,
        val proposedMirror: Boolean?,
        val proposedBy: Int?,
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
        mirror: Boolean = false,
    ): Int =
        transaction {
            require(!mirror || synced) { "One for one needs a synced share" }
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
                            (LibraryShareTable.mirror eq mirror) and
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
                    it[LibraryShareTable.mirror] = mirror
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
            create(userId, senderName, scope, ownIds, synced = true, pairedWith = shareId, mirror = row[LibraryShareTable.mirror])
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

    private fun acceptedShareOf(
        shareId: Int,
        userId: Int,
    ): ResultRow {
        val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull()
        require(row != null && (row[LibraryShareTable.sender].value == userId || row[LibraryShareTable.recipient].value == userId)) {
            "Unknown share"
        }
        require(row[LibraryShareTable.status] == Status.ACCEPTED.name) { "Only a share that was accepted can be changed" }
        return row
    }

    /**
     * Either account proposes new settings (synced, one for one) for a share that was accepted. They only apply once the
     * other account confirms. Leaving a share (stop syncing, remove) never needs a confirmation, changing its terms does.
     */
    fun proposeEdit(
        shareId: Int,
        userId: Int,
        synced: Boolean,
        mirror: Boolean,
    ) = transaction {
        val row = acceptedShareOf(shareId, userId)
        require(!mirror || synced) { "One for one needs a synced share" }
        require(row[LibraryShareTable.proposedBy] == null) { "A change of this share is already waiting for confirmation" }
        require(synced != row[LibraryShareTable.synced] || mirror != row[LibraryShareTable.mirror]) { "Nothing would change" }
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
            it[proposedSynced] = synced
            it[proposedMirror] = mirror
            it[proposedBy] = userId
        }
    }

    private fun clearProposal(shareId: Int) {
        LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
            it[proposedSynced] = null
            it[proposedMirror] = null
            it[proposedBy] = null
        }
    }

    /** The account that did not propose confirms (applies the settings) or declines. */
    fun respondToEdit(
        shareId: Int,
        userId: Int,
        accept: Boolean,
    ) = transaction {
        val row = acceptedShareOf(shareId, userId)
        val proposer = row[LibraryShareTable.proposedBy]
        require(proposer != null) { "There is nothing to confirm" }
        require(proposer != userId) { "The other account has to confirm your change" }
        if (accept) {
            val synced = row[LibraryShareTable.proposedSynced] ?: row[LibraryShareTable.synced]
            val mirror = (row[LibraryShareTable.proposedMirror] ?: row[LibraryShareTable.mirror]) && synced
            LibraryShareTable.update({ LibraryShareTable.id eq shareId }) {
                it[LibraryShareTable.synced] = synced
                it[LibraryShareTable.mirror] = mirror
                if (!synced) it[autoSync] = false
            }
        }
        clearProposal(shareId)
    }

    /** The account that proposed a change takes it back. */
    fun cancelEdit(
        shareId: Int,
        userId: Int,
    ) = transaction {
        val row = acceptedShareOf(shareId, userId)
        require(row[LibraryShareTable.proposedBy] == userId) { "Only the account that proposed the change can take it back" }
        clearProposal(shareId)
    }

    /**
     * Deletes a share that is not waiting for an answer. Syncing ends right away for both accounts, what was copied
     * stays. Either account may do it, nobody has to confirm leaving.
     */
    fun remove(
        shareId: Int,
        userId: Int,
    ) = transaction {
        val row = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.firstOrNull()
        require(row != null && (row[LibraryShareTable.sender].value == userId || row[LibraryShareTable.recipient].value == userId)) {
            "Unknown share"
        }
        require(row[LibraryShareTable.status] != Status.PENDING.name) { "Answer or cancel the request first" }
        LibraryShareTable.deleteWhere { LibraryShareTable.id eq shareId }
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
    private val pendingSyncs = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    /**
     * Syncs the automatic shares the account sends or receives, shortly after the last change. Many changes in a row
     * (adding fifty manga one by one, migrating a series: add the new one, then remove the old one) are collected into
     * one sync, the timer starts over with every change. Never blocks or fails the caller.
     */
    fun requestSync(userId: Int) {
        // ponytail: no upper limit, a change every few seconds for minutes delays the sync until it stops (the 15 minute timer still runs)
        pendingSyncs.compute(userId) { _, pending ->
            pending?.cancel(false)
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

    private fun unmarkDelivered(
        shareId: Int,
        kind: String,
        refs: Collection<Int>,
    ) {
        if (refs.isEmpty()) return
        LibraryShareDeliveredTable.deleteWhere {
            (LibraryShareDeliveredTable.share eq shareId) and
                (LibraryShareDeliveredTable.kind eq kind) and
                (LibraryShareDeliveredTable.ref inList refs.toList())
        }
    }

    /** sender category id -> recipient category id */
    private fun categoryMapping(shareId: Int): Map<Int, Int> =
        LibraryShareCategoryTable
            .selectAll()
            .where { LibraryShareCategoryTable.share eq shareId }
            .associate { it[LibraryShareCategoryTable.senderCategory].value to it[LibraryShareCategoryTable.recipientCategory].value }

    /**
     * One for one shares keep the names of the paired categories equal. Whichever side changed its name since the two
     * were last equal wins (the sender if both did). A name the other account already uses is skipped, so renaming
     * never makes two categories of one account share a name.
     */
    private fun mirrorCategoryNames(shareId: Int) {
        val names = CategoryTable.selectAll().associate { it[CategoryTable.id].value to it[CategoryTable.name] }
        val owners = CategoryTable.selectAll().associate { it[CategoryTable.id].value to it[CategoryTable.user]?.value }
        fun taken(
            owner: Int?,
            name: String,
            except: Int,
        ) = names.any { (id, other) -> id != except && owners[id] == owner && other.equals(name, ignoreCase = true) }

        LibraryShareCategoryTable.selectAll().where { LibraryShareCategoryTable.share eq shareId }.forEach { pair ->
            val senderId = pair[LibraryShareCategoryTable.senderCategory].value
            val recipientId = pair[LibraryShareCategoryTable.recipientCategory].value
            val senderName = names[senderId] ?: return@forEach
            val recipientName = names[recipientId] ?: return@forEach
            val last = pair[LibraryShareCategoryTable.lastName]
            val newName =
                when {
                    senderName != last && !senderName.equals(Category.DEFAULT_CATEGORY_NAME, ignoreCase = true) -> senderName
                    recipientName != last && !recipientName.equals(Category.DEFAULT_CATEGORY_NAME, ignoreCase = true) -> recipientName
                    else -> return@forEach
                }
            val renameSender = newName != senderName
            val renameRecipient = newName != recipientName
            if (renameSender && taken(owners[senderId], newName, senderId)) return@forEach
            if (renameRecipient && taken(owners[recipientId], newName, recipientId)) return@forEach
            if (renameSender) CategoryTable.update({ CategoryTable.id eq senderId }) { it[CategoryTable.name] = newName }
            if (renameRecipient) CategoryTable.update({ CategoryTable.id eq recipientId }) { it[CategoryTable.name] = newName }
            LibraryShareCategoryTable.update({ LibraryShareCategoryTable.id eq pair[LibraryShareCategoryTable.id].value }) {
                it[LibraryShareCategoryTable.lastName] = newName
            }
        }
    }

    /**
     * One for one shares also keep the order of the paired categories equal. Whichever side was reordered since the last
     * sync wins (the sender if both were). The categories that are not paired keep their places.
     */
    private fun mirrorCategoryOrder(shareId: Int) {
        // the two accounts' Default categories take part in the order too (they are never shared, only placed)
        fun defaultOf(owner: Int) =
            CategoryTable
                .selectAll()
                .where { CategoryTable.ownedBy(owner) }
                .firstOrNull { it[CategoryTable.name].equals(Category.DEFAULT_CATEGORY_NAME, ignoreCase = true) }
                ?.get(CategoryTable.id)
                ?.value
        val share = LibraryShareTable.selectAll().where { LibraryShareTable.id eq shareId }.first()
        val senderDefault = defaultOf(share[LibraryShareTable.sender].value)
        val recipientDefault = defaultOf(share[LibraryShareTable.recipient].value)
        if (senderDefault != null && recipientDefault != null &&
            LibraryShareCategoryTable.selectAll().where { (LibraryShareCategoryTable.share eq shareId) and (LibraryShareCategoryTable.senderCategory eq senderDefault) }.empty()
        ) {
            LibraryShareCategoryTable.insert {
                it[LibraryShareCategoryTable.share] = EntityID(shareId, LibraryShareTable)
                it[LibraryShareCategoryTable.senderCategory] = EntityID(senderDefault, CategoryTable)
                it[LibraryShareCategoryTable.recipientCategory] = EntityID(recipientDefault, CategoryTable)
                it[LibraryShareCategoryTable.lastName] = Category.DEFAULT_CATEGORY_NAME
            }
        }

        val pairs =
            LibraryShareCategoryTable.selectAll().where { LibraryShareCategoryTable.share eq shareId }.toList()
        if (pairs.size < 2) return
        val orders = CategoryTable.selectAll().associate { it[CategoryTable.id].value to it[CategoryTable.order] }

        fun sequence(pick: (ResultRow) -> Int) =
            pairs.filter { pick(it) in orders }.sortedWith(compareBy({ orders[pick(it)] }, { pick(it) })).map { it[LibraryShareCategoryTable.id].value }

        val sender = sequence { it[LibraryShareCategoryTable.senderCategory].value }
        val recipient = sequence { it[LibraryShareCategoryTable.recipientCategory].value }
        val last = pairs.sortedBy { it[LibraryShareCategoryTable.lastPosition] ?: Int.MAX_VALUE }.map { it[LibraryShareCategoryTable.id].value }
        val agreed =
            when {
                sender != last || pairs.any { it[LibraryShareCategoryTable.lastPosition] == null } -> sender
                recipient != last -> recipient
                else -> return
            }
        if (sender.size != pairs.size) return

        // the account that does not already have this order gets the paired categories re-slotted
        listOf(sender to LibraryShareCategoryTable.senderCategory, recipient to LibraryShareCategoryTable.recipientCategory)
            .filter { (current, _) -> current != agreed }
            .forEach { (_, column) ->
                val byPair = pairs.associate { it[LibraryShareCategoryTable.id].value to it[column].value }
                val owner = CategoryTable.selectAll().where { CategoryTable.id eq byPair.values.first() }.first()[CategoryTable.user]?.value ?: return@forEach
                val own =
                    CategoryTable
                        .selectAll()
                        .where { CategoryTable.ownedBy(owner) }
                        .orderBy(CategoryTable.order to SortOrder.ASC, CategoryTable.id to SortOrder.ASC)
                        .map { it[CategoryTable.id].value }
                val paired = byPair.values.toSet()
                val slots = own.indices.filter { own[it] in paired }
                val reordered = own.toMutableList()
                agreed.forEachIndexed { index, pairId -> reordered[slots[index]] = byPair.getValue(pairId) }
                reordered.forEachIndexed { index, id ->
                    CategoryTable.update({ CategoryTable.id eq id }) { it[CategoryTable.order] = index + 1 }
                }
            }
        Category.normalizeCategories()
        agreed.forEachIndexed { index, pairId ->
            LibraryShareCategoryTable.update({ LibraryShareCategoryTable.id eq pairId }) { it[lastPosition] = index }
        }
    }

    /** The (category, manga) links of the sender as they were at the last sync. */
    private fun deliveredLinks(shareId: Int): Set<Pair<Int, Int>> =
        LibraryShareDeliveredTable
            .selectAll()
            .where { (LibraryShareDeliveredTable.share eq shareId) and (LibraryShareDeliveredTable.kind eq "LINK") }
            .mapNotNull { row -> row[LibraryShareDeliveredTable.ref2]?.let { row[LibraryShareDeliveredTable.ref] to it } }
            .toSet()

    private fun markDeliveredLinks(
        shareId: Int,
        links: Collection<Pair<Int, Int>>,
    ) {
        links.forEach { (categoryId, mangaId) ->
            LibraryShareDeliveredTable.insert {
                it[share] = EntityID(shareId, LibraryShareTable)
                it[LibraryShareDeliveredTable.kind] = "LINK"
                it[LibraryShareDeliveredTable.ref] = categoryId
                it[LibraryShareDeliveredTable.ref2] = mangaId
            }
        }
    }

    private fun unmarkDeliveredLinks(
        shareId: Int,
        links: Collection<Pair<Int, Int>>,
    ) {
        links.forEach { (categoryId, mangaId) ->
            LibraryShareDeliveredTable.deleteWhere {
                (LibraryShareDeliveredTable.share eq shareId) and
                    (LibraryShareDeliveredTable.kind eq "LINK") and
                    (LibraryShareDeliveredTable.ref eq categoryId) and
                    (LibraryShareDeliveredTable.ref2 eq mangaId)
            }
        }
    }

    /**
     * Brings the recipient up to date with what the sender shares, comparing with what the share delivered before:
     * - manga that are new are added, manga that left the shared set are removed again,
     * - categories that are new are created (matched by name among the recipient's own),
     * - manga that were put into or taken out of a shared category follow, so moving a manga to another category
     *   moves it for the recipient too.
     * Only what changed on the sender's side since the last sync is applied: a manga the recipient removed, or moved
     * to a category of their own, is left alone until the sender changes that manga again. Reading state is never
     * copied. Returns the number of manga added.
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
        val currentLinks =
            CategoryMangaTable
                .selectAll()
                .where { CategoryMangaTable.category inList senderCategoryIds }
                .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                .filter { (_, mangaId) -> mangaId in senderLibrary }
                .toSet()
        val mangaIds = if (scope == Scope.LIBRARY) senderLibrary else currentLinks.map { it.second }.toSet()

        val deliveredManga = delivered(shareId, "MANGA")
        val newManga = mangaIds - deliveredManga
        // ponytail: an empty sender library never removes anything, it is far more likely to be a glitch than a clean-out
        val removedManga = if (mangaIds.isEmpty()) emptySet() else deliveredManga - mangaIds
        val newCategoryIds = senderCategoryIds.toSet() - delivered(shareId, "CATEGORY")
        val previousLinks = deliveredLinks(shareId)
        val addedLinks = currentLinks - previousLinks
        val removedLinks = previousLinks - currentLinks

        val now = Instant.now().epochSecond
        val recipientCategories = CategoryTable.selectAll().where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq 0) }.toList()
        val recipientCategoryIds = recipientCategories.map { it[CategoryTable.id].value }

        // series the sender migrated to another source: the recipient's progress and trackers move to the new one
        MangaSwap.carryOver(senderId, userId, removedManga)

        // manga that left the shared set leave the recipient's library and categories as well
        if (removedManga.isNotEmpty()) {
            UserMangaTable.update({ (UserMangaTable.user eq userId) and (UserMangaTable.manga inList removedManga.toList()) }) {
                it[inLibrary] = false
            }
            if (userId == 1) {
                // the first account is mirrored into the manga table
                MangaTable.update({ MangaTable.id inList removedManga.toList() }) { it[inLibrary] = false }
            }
            CategoryMangaTable.deleteWhere {
                (CategoryMangaTable.manga inList removedManga.toList()) and (CategoryMangaTable.category inList recipientCategoryIds)
            }
        }

        // manga the recipient already has are neither added again nor counted as added
        val libraryBefore = libraryOf(userId)
        val alreadyOwned =
            UserMangaTable
                .selectAll()
                .where { (UserMangaTable.user eq userId) and (UserMangaTable.manga inList newManga.toList()) }
                .associateBy { it[UserMangaTable.manga].value }
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
            MangaTable.update({ (MangaTable.id inList newManga.toList()) and (MangaTable.inLibrary eq false) }) {
                it[inLibrary] = true
                it[inLibraryAt] = now
            }
        }

        // a category of the sender is followed by one category of the recipient, remembered by id, so the recipient
        // can rename theirs without losing it. The first time it is matched by name among the recipient's own, or created.
        val mapping = categoryMapping(shareId).toMutableMap()
        val ownCategories = recipientCategories.associateBy { it[CategoryTable.name].lowercase() }
        val categoriesToPlace = newCategoryIds + addedLinks.map { it.first }
        val targetOf =
            senderCategories.filter { it[CategoryTable.id].value in categoriesToPlace }.associate { cat ->
                val senderCategoryId = cat[CategoryTable.id].value
                val name = cat[CategoryTable.name]
                val target =
                    mapping[senderCategoryId]
                        ?: (
                            ownCategories[name.lowercase()]?.get(CategoryTable.id)?.value?.takeIf { it !in mapping.values }
                                ?: CategoryTable
                                    .insertAndGetId {
                                        it[CategoryTable.name] = name
                                        it[CategoryTable.order] = Int.MAX_VALUE
                                        it[user] = EntityID(userId, UserTable)
                                    }.value
                        ).also { created ->
                            LibraryShareCategoryTable.insert {
                                it[LibraryShareCategoryTable.share] = EntityID(shareId, LibraryShareTable)
                                it[LibraryShareCategoryTable.senderCategory] = EntityID(senderCategoryId, CategoryTable)
                                it[LibraryShareCategoryTable.recipientCategory] = EntityID(created, CategoryTable)
                                it[LibraryShareCategoryTable.lastName] = name
                            }
                            mapping[senderCategoryId] = created
                        }
                senderCategoryId to target
            }
        Category.normalizeCategories()

        // manga taken out of a shared category are taken out of the matching category of the recipient
        removedLinks.filter { (_, mangaId) -> mangaId !in removedManga }.forEach { (senderCategoryId, mangaId) ->
            val target = mapping[senderCategoryId]
            if (target != null) {
                CategoryMangaTable.deleteWhere { (CategoryMangaTable.category eq target) and (CategoryMangaTable.manga eq mangaId) }
            }
        }

        // manga put into a shared category are put into the matching category of the recipient
        val recipientLibrary = libraryOf(userId)
        val defaultCategoryIds =
            CategoryTable
                .select(CategoryTable.id)
                .where { CategoryTable.ownedBy(userId) and (CategoryTable.id neq 0) and (CategoryTable.isDefault eq true) }
                .map { it[CategoryTable.id].value }
        val existingLinks =
            CategoryMangaTable
                .selectAll()
                .where { CategoryMangaTable.category inList targetOf.values.toList() }
                .map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
                .toSet()
        addedLinks
            .filter { (categoryId, mangaId) -> mangaId in recipientLibrary && categoryId in targetOf }
            .map { (categoryId, mangaId) -> targetOf.getValue(categoryId) to mangaId }
            .distinct()
            .filter { it !in existingLinks }
            .forEach { (categoryId, mangaId) ->
                CategoryMangaTable.insert {
                    it[category] = categoryId
                    it[manga] = mangaId
                }
            }

        // a manga that sits in a shared category is not "uncategorized" any more: it leaves the default categories it was
        // put into while it had none (one for one shares also fix what an earlier sync left behind)
        // (the Default categories are paired for the order only, they are not shared categories)
        val sharedTargets = mapping.filterKeys { it in senderCategoryIds }.values.toSet()
        val categorizedByShare =
            if (row[LibraryShareTable.mirror]) {
                CategoryMangaTable
                    .select(CategoryMangaTable.manga)
                    .where { CategoryMangaTable.category inList sharedTargets.toList() }
                    .map { it[CategoryMangaTable.manga].value }
                    .toSet()
            } else {
                addedLinks.filter { (categoryId, mangaId) -> mangaId in recipientLibrary && categoryId in targetOf }.map { it.second }.toSet()
            }
        val defaultsToLeave = defaultCategoryIds - sharedTargets
        if (categorizedByShare.isNotEmpty() && defaultsToLeave.isNotEmpty()) {
            CategoryMangaTable.deleteWhere {
                (CategoryMangaTable.manga inList categorizedByShare.toList()) and (CategoryMangaTable.category inList defaultsToLeave.toList())
            }
        }

        // accounts other than the first only see manga that are in one of their categories, so manga without one go
        // where the WebUI puts newly added manga: into the categories flagged as default
        val affected = newManga + removedLinks.map { it.second }.filter { it !in removedManga && it in recipientLibrary }
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
        (affected - categorized).forEach { mangaId ->
            defaultCategoryIds.forEach { categoryId ->
                CategoryMangaTable.insert {
                    it[category] = categoryId
                    it[manga] = mangaId
                }
            }
        }

        if (row[LibraryShareTable.mirror]) {
            mirrorCategoryNames(shareId)
            mirrorCategoryOrder(shareId)
        }

        // what was delivered now, to compare with next time
        markDelivered(shareId, "MANGA", newManga)
        unmarkDelivered(shareId, "MANGA", removedManga)
        markDelivered(shareId, "CATEGORY", newCategoryIds)
        markDeliveredLinks(shareId, addedLinks)
        unmarkDeliveredLinks(shareId, removedLinks)
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
            mirror = row[LibraryShareTable.mirror],
            proposedSynced = row[LibraryShareTable.proposedSynced],
            proposedMirror = row[LibraryShareTable.proposedMirror],
            proposedBy = row[LibraryShareTable.proposedBy],
        )
    }
}

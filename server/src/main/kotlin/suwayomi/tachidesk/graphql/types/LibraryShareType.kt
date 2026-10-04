package suwayomi.tachidesk.graphql.types

import suwayomi.tachidesk.manga.impl.LibraryShare

enum class LibraryShareScope { LIBRARY, CATEGORIES }

enum class LibraryShareStatus { PENDING, ACCEPTED, DECLINED, CANCELLED }

data class LibraryShareType(
    val id: Int,
    /** true when the current account is the one who has to answer */
    val incoming: Boolean,
    val senderUsername: String,
    val recipientUsername: String,
    val scope: LibraryShareScope,
    val categoryNames: List<String>,
    val mangaCount: Int,
    val status: LibraryShareStatus,
    val createdAt: Long,
    val respondedAt: Long,
    /** the sender keeps adding new manga and categories to this share, reading progress is never shared */
    val synced: Boolean,
    /** the recipient follows a synced share automatically */
    val autoSync: Boolean,
    val lastSyncedAt: Long,
    /** set when this share is a two way request, the id of the share it answers */
    val pairedWith: Int?,
    /** on a synced share the recipient accepted: the state of their two way request, null if they made none */
    val twoWayStatus: LibraryShareStatus?,
    /** one for one: renaming a shared category on one side renames it on the other side too */
    val mirror: Boolean,
    /** a change of synced and one for one that the other account still has to confirm, null if there is none */
    val proposedSynced: Boolean?,
    val proposedMirror: Boolean?,
    /** the pending change was proposed by the current account, so the other one has to confirm it */
    val proposalIsMine: Boolean,
) {
    companion object {
        fun from(
            share: LibraryShare.Share,
            currentUserId: Int,
        ) = LibraryShareType(
            id = share.id,
            incoming = share.recipientId == currentUserId,
            senderUsername = share.senderUsername,
            recipientUsername = share.recipientUsername,
            scope = LibraryShareScope.valueOf(share.scope.name),
            categoryNames = share.categoryNames,
            mangaCount = share.mangaCount,
            status = LibraryShareStatus.valueOf(share.status.name),
            createdAt = share.createdAt,
            respondedAt = share.respondedAt,
            synced = share.synced,
            autoSync = share.autoSync,
            lastSyncedAt = share.lastSyncedAt,
            pairedWith = share.pairedWith,
            twoWayStatus = share.twoWayStatus?.let { LibraryShareStatus.valueOf(it.name) },
            mirror = share.mirror,
            proposedSynced = share.proposedSynced,
            proposedMirror = share.proposedMirror,
            proposalIsMine = share.proposedBy == currentUserId,
        )
    }
}

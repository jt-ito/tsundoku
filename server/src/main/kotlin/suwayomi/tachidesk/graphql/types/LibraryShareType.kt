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
        )
    }
}

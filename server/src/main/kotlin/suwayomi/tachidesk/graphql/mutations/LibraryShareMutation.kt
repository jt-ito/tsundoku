package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.LibraryShareScope
import suwayomi.tachidesk.graphql.types.LibraryShareType
import suwayomi.tachidesk.manga.impl.LibraryShare
import suwayomi.tachidesk.manga.impl.MangaSwap
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser

class LibraryShareMutation {
    data class CreateLibraryShareInput(
        val clientMutationId: String? = null,
        /** exact username of the recipient, the sender has to get it from them */
        val username: String,
        val scope: LibraryShareScope,
        val categoryIds: List<Int> = emptyList(),
        /** keep sharing new manga and categories after the recipient accepted */
        val synced: Boolean = false,
        /** one for one: category renames follow in both directions, needs synced */
        val mirror: Boolean = false,
    )

    data class LibraryShareChangePayload(
        val clientMutationId: String?,
        val share: LibraryShareType,
        /** manga added to the library, only set when a share was accepted */
        val addedMangas: Int,
    )

    fun createLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val id = LibraryShare.create(userId, input.username, LibraryShare.Scope.valueOf(input.scope.name), input.categoryIds, input.synced, mirror = input.mirror)
        return payload(input.clientMutationId, id, userId, 0)
    }

    data class RespondToLibraryShareInput(
        val clientMutationId: String? = null,
        val id: Int,
        val accept: Boolean,
        /** follow the share automatically, only used when the sender made it synced */
        val autoSync: Boolean = false,
    )

    fun respondToLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RespondToLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val added =
            if (input.accept) {
                LibraryShare.accept(input.id, userId, input.autoSync)
            } else {
                LibraryShare.decline(input.id, userId)
                0
            }
        return payload(input.clientMutationId, input.id, userId, added)
    }

    data class RecordMangaSwapInput(
        val clientMutationId: String? = null,
        /** the series that was migrated away from */
        val oldMangaId: Int,
        /** the series it was migrated to */
        val newMangaId: Int,
    )

    data class RecordMangaSwapPayload(
        val clientMutationId: String?,
    )

    /**
     * Tells the server that the account replaced a series with another one (a migration to another source), so shared
     * libraries can move the other accounts' reading progress and trackers along with it.
     */
    fun recordMangaSwap(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RecordMangaSwapInput,
    ): RecordMangaSwapPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        MangaSwap.record(userId, input.oldMangaId, input.newMangaId)
        return RecordMangaSwapPayload(input.clientMutationId)
    }

    data class CancelLibraryShareInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    fun cancelLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CancelLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.cancelOrStop(input.id, userId)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class SetLibraryShareAutoSyncInput(
        val clientMutationId: String? = null,
        val id: Int,
        val autoSync: Boolean,
    )

    fun setLibraryShareAutoSync(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetLibraryShareAutoSyncInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.setAutoSync(input.id, userId, input.autoSync)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class SyncLibraryShareInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    fun syncLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SyncLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val added = LibraryShare.syncNow(input.id, userId)
        return payload(input.clientMutationId, input.id, userId, added)
    }

    data class RequestTwoWayLibraryShareInput(
        val clientMutationId: String? = null,
        /** the synced share the account received and accepted */
        val id: Int,
    )

    fun requestTwoWayLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RequestTwoWayLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.requestTwoWay(input.id, userId)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class ProposeLibraryShareEditInput(
        val clientMutationId: String? = null,
        val id: Int,
        val synced: Boolean,
        val mirror: Boolean = false,
    )

    fun proposeLibraryShareEdit(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: ProposeLibraryShareEditInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.proposeEdit(input.id, userId, input.synced, input.mirror)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class RespondToLibraryShareEditInput(
        val clientMutationId: String? = null,
        val id: Int,
        val accept: Boolean,
    )

    fun respondToLibraryShareEdit(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RespondToLibraryShareEditInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.respondToEdit(input.id, userId, input.accept)
        if (input.accept) LibraryShare.requestSync(userId)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class CancelLibraryShareEditInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    fun cancelLibraryShareEdit(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CancelLibraryShareEditInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.cancelEdit(input.id, userId)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    data class RemoveLibraryShareInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class RemoveLibrarySharePayload(
        val clientMutationId: String?,
        val removedId: Int,
    )

    fun removeLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RemoveLibraryShareInput,
    ): RemoveLibrarySharePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        LibraryShare.remove(input.id, userId)
        return RemoveLibrarySharePayload(input.clientMutationId, input.id)
    }

    private fun payload(
        clientMutationId: String?,
        shareId: Int,
        userId: Int,
        added: Int,
    ) = LibraryShareChangePayload(clientMutationId, LibraryShareType.from(LibraryShare.get(shareId, userId), userId), added)
}

package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.LibraryShareScope
import suwayomi.tachidesk.graphql.types.LibraryShareType
import suwayomi.tachidesk.manga.impl.LibraryShare
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
        val id = LibraryShare.create(userId, input.username, LibraryShare.Scope.valueOf(input.scope.name), input.categoryIds, input.synced)
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

    private fun payload(
        clientMutationId: String?,
        shareId: Int,
        userId: Int,
        added: Int,
    ) = LibraryShareChangePayload(clientMutationId, LibraryShareType.from(LibraryShare.get(shareId, userId), userId), added)
}

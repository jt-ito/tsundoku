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
        val id = LibraryShare.create(userId, input.username, LibraryShare.Scope.valueOf(input.scope.name), input.categoryIds)
        return payload(input.clientMutationId, id, userId, 0)
    }

    data class RespondToLibraryShareInput(
        val clientMutationId: String? = null,
        val id: Int,
        val accept: Boolean,
    )

    fun respondToLibraryShare(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RespondToLibraryShareInput,
    ): LibraryShareChangePayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val added =
            if (input.accept) {
                LibraryShare.accept(input.id, userId)
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
        LibraryShare.cancel(input.id, userId)
        return payload(input.clientMutationId, input.id, userId, 0)
    }

    private fun payload(
        clientMutationId: String?,
        shareId: Int,
        userId: Int,
        added: Int,
    ) = LibraryShareChangePayload(clientMutationId, LibraryShareType.from(LibraryShare.get(shareId, userId), userId), added)
}

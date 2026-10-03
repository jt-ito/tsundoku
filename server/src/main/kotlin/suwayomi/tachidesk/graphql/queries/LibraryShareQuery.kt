package suwayomi.tachidesk.graphql.queries

import graphql.schema.DataFetchingEnvironment
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.LibraryShareType
import suwayomi.tachidesk.manga.impl.LibraryShare
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser

class LibraryShareQuery {
    /** The library shares the current account sent or received. */
    fun libraryShares(dataFetchingEnvironment: DataFetchingEnvironment): List<LibraryShareType> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return LibraryShare.list(userId).map { LibraryShareType.from(it, userId) }
    }
}

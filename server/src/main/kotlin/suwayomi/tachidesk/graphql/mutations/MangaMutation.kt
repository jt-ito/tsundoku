@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.server.extensions.toGraphQLError
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.currentUserId
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.server.user.idOrNull
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.MangaMetaType
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.graphql.types.MetaInput
import suwayomi.tachidesk.manga.impl.LibraryShare
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * TODO Mutations
 * - Download x(all = -1) chapters
 * - Delete read/all downloaded chapters
 */
class MangaMutation {
    private val updater: IUpdater by injectLazy()

    data class UpdateMangaPatch(
        val inLibrary: Boolean? = null,
    )

    data class UpdateMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    data class UpdateMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateMangaPatch,
    )

    data class UpdateMangasPayload(
        val clientMutationId: String?,
        val mangas: List<MangaType>,
    )

    data class UpdateMangasInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val patch: UpdateMangaPatch,
    )

    private suspend fun updateMangas(
        userId: Int,
        ids: List<Int>,
        patch: UpdateMangaPatch,
    ) {
        transaction {
            if (patch.inLibrary != null) {
                val now = Instant.now().epochSecond

                // 1. Update user_manga table for calling user
                ids.forEach { mangaId ->
                    val existing =
                        suwayomi.tachidesk.manga.model.table.UserMangaTable
                            .selectAll()
                            .where {
                                (suwayomi.tachidesk.manga.model.table.UserMangaTable.user eq userId) and
                                    (suwayomi.tachidesk.manga.model.table.UserMangaTable.manga eq mangaId)
                            }
                            .firstOrNull()

                    if (existing != null) {
                        suwayomi.tachidesk.manga.model.table.UserMangaTable.update({
                            (suwayomi.tachidesk.manga.model.table.UserMangaTable.user eq userId) and
                                (suwayomi.tachidesk.manga.model.table.UserMangaTable.manga eq mangaId)
                        }) {
                            it[inLibrary] = patch.inLibrary
                            if (patch.inLibrary) it[inLibraryAt] = now
                        }
                    } else {
                        suwayomi.tachidesk.manga.model.table.UserMangaTable.insert {
                            it[user] = EntityID(userId, suwayomi.tachidesk.server.user.model.UserTable)
                            it[manga] = EntityID(mangaId, MangaTable)
                            it[inLibrary] = patch.inLibrary
                            it[inLibraryAt] = if (patch.inLibrary) now else 0
                        }
                    }
                }

                // 2. Legacy fallback update for primary admin / background sync
                if (userId == 1) {
                    MangaTable.update({ MangaTable.id inList ids }) { update ->
                        patch.inLibrary.also {
                            update[inLibrary] = it
                            if (it) update[inLibraryAt] = now
                        }
                    }
                }
            }
        }.apply {
            if (patch.inLibrary != null) {
                transaction {
                    // try to initialize uninitialized in library manga to ensure that the expected data is available (chapter list, metadata, ...)
                    val mangas =
                        transaction {
                            MangaTable
                                .selectAll()
                                .where { (MangaTable.id inList ids) and (MangaTable.initialized eq false) }
                                .map { MangaTable.toDataClass(it) }
                        }

                    updater.addMangasToQueue(mangas)
                }

                ids.forEach {
                    Library.handleMangaThumbnail(it, patch.inLibrary)
                }
            }
        }
    }

    @RequireAuth
    fun updateManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateMangaInput,
    ): CompletableFuture<UpdateMangaPayload?> {
        val (clientMutationId, id, patch) = input
        val userType = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        val userId = userType?.idOrNull ?: 1

        return future {
            updateMangas(userId, listOf(id), patch)
            if (patch.inLibrary != null) LibraryShare.requestSync(userId)

            val manga =
                transaction {
                    MangaType(MangaTable.selectAll().where { MangaTable.id eq id }.first(), userId)
                }

            UpdateMangaPayload(
                clientMutationId = clientMutationId,
                manga = manga,
            )
        }
    }

    @RequireAuth
    fun updateMangas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateMangasInput,
    ): CompletableFuture<UpdateMangasPayload?> {
        val (clientMutationId, ids, patch) = input
        val userType = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser)
        val userId = userType?.idOrNull ?: 1

        return future {
            updateMangas(userId, ids, patch)
            if (patch.inLibrary != null) LibraryShare.requestSync(userId)

            val mangas =
                transaction {
                    MangaTable.selectAll().where { MangaTable.id inList ids }.map { MangaType(it, userId) }
                }

            UpdateMangasPayload(
                clientMutationId = clientMutationId,
                mangas = mangas,
            )
        }
    }

    data class FetchMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class FetchMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    @RequireAuth
    @GraphQLDeprecated("Deprecated in Tachiyomix 1.6", ReplaceWith("fetchMangaAndChapters"))
    fun fetchManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchMangaInput,
    ): CompletableFuture<FetchMangaPayload?> {
        val (clientMutationId, id) = input
        val userId = dataFetchingEnvironment.currentUserId()

        return future {
            Manga.updateMangaAndChapters(id, updateChapters = false)

            val manga =
                transaction {
                    MangaTable.selectAll().where { MangaTable.id eq id }.first()
                }
            FetchMangaPayload(
                clientMutationId = clientMutationId,
                manga = MangaType(manga, userId),
            )
        }
    }

    data class FetchMangaAndChaptersInput(
        val clientMutationId: String? = null,
        val id: Int,
        val fetchManga: Boolean,
        val fetchChapters: Boolean,
    )

    data class FetchMangaAndChaptersPayload(
        val clientMutationId: String?,
        val manga: MangaType,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun fetchMangaAndChapters(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchMangaAndChaptersInput,
    ): CompletableFuture<DataFetcherResult<FetchMangaAndChaptersPayload?>> {
        val (clientMutationId, id, fetchManga, fetchChapters) = input
        val userId = dataFetchingEnvironment.currentUserId()

        return future {
            val error =
                try {
                    Manga.updateMangaAndChapters(
                        mangaId = id,
                        updateManga = fetchManga,
                        updateChapters = fetchChapters,
                    )
                    null
                } catch (e: Exception) {
                    KotlinLogging.logger { }.error(e) { "Error updating manga and chapters" }
                    e
                }

            val (manga, chapters) =
                transaction {
                    Pair(
                        MangaTable.selectAll().where { MangaTable.id eq id }.first(),
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga eq id }
                            .orderBy(ChapterTable.sourceOrder)
                            .map { ChapterType(it, userId) },
                    )
                }
            @Suppress("UNCHECKED_CAST")
            DataFetcherResult
                .newResult<FetchMangaAndChaptersPayload>()
                .data(
                    FetchMangaAndChaptersPayload(
                        clientMutationId = clientMutationId,
                        manga = MangaType(manga, userId),
                        chapters = chapters,
                    ),
                ).also {
                    if (error != null) {
                        it.error(error.toGraphQLError())
                    }
                }.build() as DataFetcherResult<FetchMangaAndChaptersPayload?>
        }
    }

    data class SetMangaMetaInput(
        val clientMutationId: String? = null,
        val meta: MangaMetaType,
    )

    data class SetMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType,
    )

    @RequireAuth
    fun setMangaMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetMangaMetaInput,
    ): SetMangaMetaPayload? {
        val (clientMutationId, meta) = input

        Manga.modifyMangaMeta(meta.mangaId, meta.key, meta.value, dataFetchingEnvironment.currentUserId())

        return SetMangaMetaPayload(clientMutationId, meta)
    }

    data class DeleteMangaMetaInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val key: String,
    )

    data class DeleteMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType?,
        val manga: MangaType,
    )

    @RequireAuth
    fun deleteMangaMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteMangaMetaInput,
    ): DeleteMangaMetaPayload? {
        val (clientMutationId, mangaId, key) = input
        val userId = dataFetchingEnvironment.currentUserId()

        val (meta, manga) =
            transaction {
                val meta =
                    MangaMetaTable
                        .selectAll()
                        .where { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) and (MangaMetaTable.user eq userId) }
                        .firstOrNull()

                MangaMetaTable.deleteWhere {
                    (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) and (MangaMetaTable.user eq userId)
                }

                val manga =
                    transaction {
                        MangaType(MangaTable.selectAll().where { MangaTable.id eq mangaId }.first(), userId)
                    }

                if (meta != null) {
                    MangaMetaType(meta)
                } else {
                    null
                } to manga
            }

        return DeleteMangaMetaPayload(clientMutationId, meta, manga)
    }

    data class SetMangaMetasItem(
        val mangaIds: List<Int>,
        val metas: List<MetaInput>,
    )

    data class SetMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<SetMangaMetasItem>,
    )

    data class SetMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun setMangaMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetMangaMetasInput,
    ): SetMangaMetasPayload? {
        val (clientMutationId, items) = input
        val userId = dataFetchingEnvironment.currentUserId()

        val metaByMangaId =
            items
                .flatMap { item ->
                    val metaMap = item.metas.associate { it.key to it.value }
                    item.mangaIds.map { mangaId -> mangaId to metaMap }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, maps) -> maps.reduce { acc, map -> acc + map } }

        Manga.modifyMangasMetas(metaByMangaId, userId)

        val allMangaIds = metaByMangaId.keys
        val allMetaKeys = metaByMangaId.values.flatMap { it.keys }.distinct()

        val (updatedMetas, mangas) =
            transaction {
                val updatedMetas =
                    MangaMetaTable
                        .selectAll()
                        .where {
                            (MangaMetaTable.ref inList allMangaIds) and
                                (MangaMetaTable.key inList allMetaKeys) and
                                (MangaMetaTable.user eq userId)
                        }
                        .map { MangaMetaType(it) }

                val mangas =
                    MangaTable
                        .selectAll()
                        .where { MangaTable.id inList allMangaIds }
                        .map { MangaType(it, userId) }
                        .distinctBy { it.id }

                updatedMetas to mangas
            }

        return SetMangaMetasPayload(clientMutationId, updatedMetas, mangas)
    }

    data class DeleteMangaMetasItem(
        val mangaIds: List<Int>,
        val keys: List<String>? = null,
        val prefixes: List<String>? = null,
    )

    data class DeleteMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<DeleteMangaMetasItem>,
    )

    data class DeleteMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun deleteMangaMetas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteMangaMetasInput,
    ): DeleteMangaMetasPayload? {
        val (clientMutationId, items) = input
        val userId = dataFetchingEnvironment.currentUserId()

        items.forEach { item ->
            require(!item.keys.isNullOrEmpty() || !item.prefixes.isNullOrEmpty()) {
                "Either 'keys' or 'prefixes' must be provided for each item"
            }
        }

        val (allDeletedMetas, allMangaIds) =
            transaction {
                val deletedMetas = mutableListOf<MangaMetaType>()
                val mangaIds = mutableSetOf<Int>()

                items.forEach { item ->
                    val keyCondition: Op<Boolean>? =
                        item.keys?.takeIf { it.isNotEmpty() }?.let { MangaMetaTable.key inList it }

                    val prefixCondition: Op<Boolean>? =
                        item.prefixes
                            ?.filter { it.isNotEmpty() }
                            ?.map { (MangaMetaTable.key like LikePattern("$it%")) as Op<Boolean> }
                            ?.reduceOrNull { acc, op -> acc or op }

                    val metaKeyCondition =
                        if (keyCondition != null && prefixCondition != null) {
                            keyCondition or prefixCondition
                        } else {
                            keyCondition ?: prefixCondition!!
                        }

                    val condition = (MangaMetaTable.ref inList item.mangaIds) and metaKeyCondition and (MangaMetaTable.user eq userId)

                    deletedMetas +=
                        MangaMetaTable
                            .selectAll()
                            .where { condition }
                            .map { MangaMetaType(it) }

                    MangaMetaTable.deleteWhere { condition }
                    mangaIds += item.mangaIds
                }

                deletedMetas to mangaIds
            }

        val mangas =
            transaction {
                MangaTable
                    .selectAll()
                    .where { MangaTable.id inList allMangaIds }
                    .map { MangaType(it, userId) }
                    .distinctBy { it.id }
            }

        return DeleteMangaMetasPayload(clientMutationId, allDeletedMetas, mangas)
    }
}

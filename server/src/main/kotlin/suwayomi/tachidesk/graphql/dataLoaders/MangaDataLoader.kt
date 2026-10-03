/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.Slf4jSqlDebugLogger
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.server.currentUserId
import suwayomi.tachidesk.graphql.types.MangaNodeList
import suwayomi.tachidesk.graphql.types.MangaNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.idOrNull

class MangaDataLoader : KotlinDataLoader<Int, MangaType> {
    override val dataLoaderName = "MangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userType = graphQLContext.get<suwayomi.tachidesk.server.user.UserType>(suwayomi.tachidesk.server.JavalinSetup.Attribute.TachideskUser)
                    val userId = userType?.idOrNull ?: 1
                    val manga =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.id inList ids }
                            .map { MangaType(it, userId) }
                            .associateBy { it.id }
                    ids.map { manga[it] }
                }
            }
        }
}

class MangaForCategoryDataLoader : KotlinDataLoader<Int, MangaNodeList> {
    override val dataLoaderName = "MangaForCategoryDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaNodeList> =
        DataLoaderFactory.newDataLoader<Int, MangaNodeList> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val userType = graphQLContext.get<suwayomi.tachidesk.server.user.UserType>(suwayomi.tachidesk.server.JavalinSetup.Attribute.TachideskUser)
                    val userId = userType?.idOrNull ?: 1

                    val itemsByRef =
                        if (ids.contains(0)) {
                            val baseQuery = MangaTable.selectAll()

                            val scopedQuery =
                                if (userId == 1) {
                                    baseQuery.where { MangaTable.inLibrary eq true }
                                } else {
                                    baseQuery.where {
                                        MangaTable.id inSubQuery (
                                            suwayomi.tachidesk.manga.model.table.UserMangaTable
                                                .select(suwayomi.tachidesk.manga.model.table.UserMangaTable.manga)
                                                .where {
                                                    (suwayomi.tachidesk.manga.model.table.UserMangaTable.user eq userId) and
                                                        (suwayomi.tachidesk.manga.model.table.UserMangaTable.inLibrary eq true)
                                                }
                                        )
                                    }
                                }

                            scopedQuery
                                .andWhere { suwayomi.tachidesk.manga.impl.Category.uncategorizedOf(userId) }
                                .map { MangaType(it, userId) }
                                .let {
                                    mapOf(0 to it)
                                }
                        } else {
                            emptyMap()
                        } +
                            CategoryMangaTable
                                .innerJoin(MangaTable)
                                .selectAll()
                                .where { CategoryMangaTable.category inList ids }
                                .map { Pair(it[CategoryMangaTable.category].value, MangaType(it, userId)) }
                                .groupBy { it.first }
                                .mapValues { it.value.map { pair -> pair.second } }

                    ids.map { (itemsByRef[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

class MangaForSourceDataLoader : KotlinDataLoader<Long, MangaNodeList> {
    override val dataLoaderName = "MangaForSourceDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, MangaNodeList> =
        DataLoaderFactory.newDataLoader<Long, MangaNodeList> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val mangaBySourceId =
                        MangaTable
                            .selectAll()
                            .where { MangaTable.sourceReference inList ids }
                            .map { MangaType(it, userId) }
                            .groupBy { it.sourceId }
                    ids.map { (mangaBySourceId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

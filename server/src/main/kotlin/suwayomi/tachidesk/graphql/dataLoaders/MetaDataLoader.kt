package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.Slf4jSqlDebugLogger
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.server.currentUserId
import suwayomi.tachidesk.graphql.types.CategoryMetaType
import suwayomi.tachidesk.graphql.types.ChapterMetaType
import suwayomi.tachidesk.graphql.types.GlobalMetaType
import suwayomi.tachidesk.graphql.types.MangaMetaType
import suwayomi.tachidesk.graphql.types.SourceMetaType
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.manga.impl.Category

class GlobalMetaDataLoader : KotlinDataLoader<String, GlobalMetaType> {
    override val dataLoaderName = "GlobalMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<String, GlobalMetaType> =
        DataLoaderFactory.newDataLoader<String, GlobalMetaType> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        GlobalMetaTable
                            .selectAll()
                            .where { (GlobalMetaTable.key inList ids) and (GlobalMetaTable.user eq userId) }
                            .map { GlobalMetaType(it) }
                            .associateBy { it.key }
                    ids.map { metasByRefId[it] }
                }
            }
        }
}

class ChapterMetaDataLoader : KotlinDataLoader<Int, List<ChapterMetaType>> {
    override val dataLoaderName = "ChapterMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<ChapterMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<ChapterMetaType>> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        ChapterMetaTable
                            .selectAll()
                            .where { (ChapterMetaTable.ref inList ids) and (ChapterMetaTable.user eq userId) }
                            .map { ChapterMetaType(it) }
                            .groupBy { it.chapterId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class MangaMetaDataLoader : KotlinDataLoader<Int, List<MangaMetaType>> {
    override val dataLoaderName = "MangaMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<MangaMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<MangaMetaType>> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        MangaMetaTable
                            .selectAll()
                            .where { (MangaMetaTable.ref inList ids) and (MangaMetaTable.user eq userId) }
                            .map { MangaMetaType(it) }
                            .groupBy { it.mangaId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class CategoryMetaDataLoader : KotlinDataLoader<Int, List<CategoryMetaType>> {
    override val dataLoaderName = "CategoryMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<CategoryMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<CategoryMetaType>> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    // the id 0 is the Default category of the account that asks
                    val defaultRowIds = Category.defaultRowIds()
                    val rowIds = ids.map { Category.resolveId(it, userId) }
                    val metasByRefId =
                        CategoryMetaTable
                            .selectAll()
                            .where { CategoryMetaTable.ref inList rowIds }
                            .map { CategoryMetaType(it, defaultRowIds) }
                            .groupBy { it.categoryId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class SourceMetaDataLoader : KotlinDataLoader<Long, List<SourceMetaType>> {
    override val dataLoaderName = "SourceMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, List<SourceMetaType>> =
        DataLoaderFactory.newDataLoader<Long, List<SourceMetaType>> { ids ->
            val userId = graphQLContext.currentUserId()
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        SourceMetaTable
                            .selectAll()
                            .where { (SourceMetaTable.ref inList ids) and (SourceMetaTable.user eq userId) }
                            .map { SourceMetaType(it) }
                            .groupBy { it.sourceId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.model.UserDataClass
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga

/** The Default category of every account means "in none of my categories", the first account is not special. */
class DefaultCategoryTest : ApplicationTest() {
    private lateinit var member: UserDataClass

    @OptIn(ExperimentalKeywordApi::class)
    @BeforeEach
    fun setUp() {
        TransactionManager.defaultDatabase =
            Database.connect(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;",
                "org.h2.Driver",
                databaseConfig = DatabaseConfig { useNestedTransactions = true; preserveKeywordCasing = false; defaultSchema = null },
            )
        member = UserManager.createUser("default-${System.nanoTime()}", "password-123")
    }

    @AfterEach
    fun tearDown() {
        clearTables(CategoryMangaTable, UserMangaTable, MangaTable)
        UserManager.deleteUser(member.id)
    }

    @Test
    fun `every account's default category is the id 0 for the API and resolves to its own row`() {
        val memberRow = Category.defaultCategoryId(member.id)
        assertEquals(true, memberRow != 0)
        assertEquals(0, Category.defaultCategoryId(1))
        assertEquals(memberRow, Category.resolveId(0, member.id))
        assertEquals(7, Category.resolveId(7, member.id))
        assertEquals(true, memberRow in Category.defaultRowIds())

        val apiId = transaction { Category.apiId(CategoryTable.selectAll().where { CategoryTable.id eq memberRow }.first()) }
        assertEquals(0, apiId)
    }

    @Test
    fun `a default category is never linked to a manga`() {
        val manga = createLibraryManga("a")
        val own = transaction { CategoryTable.insertAndGetId { it[name] = "Mine"; it[user] = EntityID(member.id, UserTable) }.value }

        CategoryManga.addMangasToCategories(listOf(manga), listOf(0, Category.defaultCategoryId(member.id), own))

        val linked = transaction { CategoryMangaTable.selectAll().where { CategoryMangaTable.manga eq manga }.map { it[CategoryMangaTable.category].value } }
        assertEquals(listOf(own), linked)
    }

    @Test
    fun `the default category of an account is only visible while it has uncategorized manga or no other category`() {
        // no other category: visible
        assertEquals(true, Category.isDefaultCategoryVisible(member.id))

        val own = transaction { CategoryTable.insertAndGetId { it[name] = "Mine"; it[user] = EntityID(member.id, UserTable) }.value }
        val manga = createLibraryManga("a")
        transaction {
            UserMangaTable.insertAndGetId {
                it[user] = EntityID(member.id, UserTable)
                it[UserMangaTable.manga] = EntityID(manga, MangaTable)
                it[inLibrary] = true
            }
        }
        // a manga in no category of the account
        assertEquals(true, Category.isDefaultCategoryVisible(member.id))

        // everything is categorized and the account removed its default category: hidden, for this account only
        CategoryManga.addMangasToCategories(listOf(manga), listOf(own))
        Category.modifyMeta(Category.defaultCategoryId(member.id), "default_category_hidden", "true")
        assertEquals(false, Category.isDefaultCategoryVisible(member.id))
        assertEquals(true, Category.isDefaultCategoryVisible(1))
    }
}

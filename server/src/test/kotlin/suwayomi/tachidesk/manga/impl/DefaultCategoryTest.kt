package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.server.database.migration.M0079_BuiltInCategory
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

    @Test
    fun `a renamed default category is still the default category of its account`() {
        val row = Category.defaultCategoryId(member.id)
        val rowsBefore = transaction { CategoryTable.selectAll().count() }

        transaction { CategoryTable.update({ CategoryTable.id eq row }) { it[name] = "Reading" } }

        assertEquals(row, Category.defaultCategoryId(member.id))
        assertEquals(row, Category.resolveId(0, member.id))
        assertEquals(true, row in Category.defaultRowIds())
        assertEquals(rowsBefore, transaction { CategoryTable.selectAll().count() })
        assertEquals(0, transaction { Category.apiId(CategoryTable.selectAll().where { CategoryTable.id eq row }.first()) })
    }

    @Test
    fun `the migration flags the oldest Default row of an account and leaves renamed and other rows alone`() {
        // the first account is never touched, so the test needs an account that is not the first (ids depend on the test order)
        val account = if (member.id != 1) member else UserManager.createUser("migration-${System.nanoTime()}", "password-123")
        val oldest = Category.defaultCategoryId(account.id)
        val (renamed, newest, other) =
            transaction {
                val owner = EntityID(account.id, UserTable)
                Triple(
                    CategoryTable.insertAndGetId { it[name] = "Reading"; it[user] = owner }.value,
                    CategoryTable.insertAndGetId { it[name] = "default"; it[user] = owner }.value,
                    CategoryTable.insertAndGetId { it[name] = "Default"; it[user] = EntityID(1, UserTable) }.value,
                )
            }
        // other tests clear the table, the built-in row of the first account may be missing
        transaction {
            if (CategoryTable.selectAll().where { CategoryTable.id eq 0 }.empty()) {
                CategoryTable.insert { it[id] = EntityID(0, CategoryTable); it[name] = "Default" }
            }
        }
        // the state before the flag existed
        transaction { CategoryTable.update { it[isBuiltIn] = false } }

        val statements = M0079_BuiltInCategory().sql.split(";").map { it.trim() }.filter { it.startsWith("UPDATE") }
        transaction { statements.forEach { exec(it) } }

        val flagged = transaction { CategoryTable.selectAll().where { CategoryTable.isBuiltIn eq true }.map { it[CategoryTable.id].value }.toSet() }
        assertEquals(true, 0 in flagged)
        assertEquals(true, oldest in flagged)
        assertEquals(false, renamed in flagged)
        assertEquals(false, newest in flagged)
        assertEquals(false, other in flagged) // a category the first account made itself is never touched
        transaction { CategoryTable.deleteWhere { CategoryTable.id eq other } }
        if (account.id != member.id) UserManager.deleteUser(account.id)
    }

    @Test
    fun `a manga put into the default category on purpose stays in it besides its other categories`() {
        val manga = createLibraryManga("on-purpose")
        val other = createLibraryManga("without-category")
        val own = transaction { CategoryTable.insertAndGetId { it[name] = "Mine"; it[user] = EntityID(member.id, UserTable) }.value }
        val defaultRow = Category.defaultCategoryId(member.id)

        CategoryManga.addMangasToCategories(listOf(manga), listOf(defaultRow, own), allowDefault = true)

        fun idsOf(condition: org.jetbrains.exposed.v1.core.Op<Boolean>) =
            transaction { MangaTable.selectAll().where { condition and (MangaTable.id inList listOf(manga, other)) }.map { it[MangaTable.id].value }.toSet() }

        // in the default category (on purpose, or because it has no category) and in its own category
        assertEquals(setOf(manga, other), idsOf(Category.defaultMembersOf(member.id)))
        // only the manga without a category is "in none of the categories"
        assertEquals(setOf(other), idsOf(Category.uncategorizedOf(member.id)))
        assertEquals(setOf(manga), idsOf(Category.linkedToDefaultOf(member.id)))
        val linked = transaction { CategoryMangaTable.selectAll().where { CategoryMangaTable.manga eq manga }.map { it[CategoryMangaTable.category].value }.toSet() }
        assertEquals(setOf(defaultRow, own), linked)
    }

    @Test
    fun `the default category of another account does not count`() {
        val manga = createLibraryManga("shared")
        val otherMember = UserManager.createUser("default-other-${System.nanoTime()}", "password-123")
        try {
            CategoryManga.addMangasToCategories(listOf(manga), listOf(Category.defaultCategoryId(otherMember.id)), allowDefault = true)

            val membersOfFirst = transaction { MangaTable.selectAll().where { Category.linkedToDefaultOf(member.id) and (MangaTable.id eq manga) }.count() }
            assertEquals(0L, membersOfFirst)
            // a manga linked to the default category of an account is not uncategorized for the others either way: nothing changes for them
            val uncategorizedForMember = transaction { MangaTable.selectAll().where { Category.uncategorizedOf(member.id) and (MangaTable.id eq manga) }.count() }
            assertEquals(1L, uncategorizedForMember)
        } finally {
            UserManager.deleteUser(otherMember.id)
        }
    }

    private fun ownCategory(
        name: String,
        order: Int,
        isDefault: Boolean = false,
    ): Int =
        transaction {
            CategoryTable.insertAndGetId {
                it[CategoryTable.name] = name
                it[CategoryTable.order] = order
                it[CategoryTable.isDefault] = isDefault
                it[user] = EntityID(member.id, UserTable)
            }.value
        }

    private fun flagged(): Set<Int> =
        transaction {
            CategoryTable
                .selectAll()
                .where { (CategoryTable.user eq member.id) and (CategoryTable.isBuiltIn eq false) and (CategoryTable.isDefault eq true) }
                .map { it[CategoryTable.id].value }
                .toSet()
        }

    @Test
    fun `deleting the default category makes the top category the default one for new manga`() {
        val top = ownCategory("Top", 1)
        val second = ownCategory("Second", 2)

        Category.removeDefaultCategory(member.id)

        assertEquals(setOf(top), flagged())
        assertEquals(true, Category.isDefaultDeleted(member.id))
        assertEquals(false, second in flagged())
    }

    @Test
    fun `an existing default mark is kept when the default category is deleted`() {
        ownCategory("Top", 1)
        val second = ownCategory("Second", 2, isDefault = true)

        Category.removeDefaultCategory(member.id)

        assertEquals(setOf(second), flagged())
    }

    @Test
    fun `while the default category is deleted only one category is the default one`() {
        val top = ownCategory("Top", 1)
        val second = ownCategory("Second", 2)
        Category.removeDefaultCategory(member.id)

        Category.setDefaultFlag(member.id, listOf(second), true)
        assertEquals(setOf(second), flagged())

        // the last one cannot lose the mark, another one has to be chosen instead
        assertThrows(IllegalArgumentException::class.java) { Category.setDefaultFlag(member.id, listOf(second), false) }
        assertEquals(setOf(second), flagged())

        Category.setDefaultFlag(member.id, listOf(top), true)
        assertEquals(setOf(top), flagged())
    }

    @Test
    fun `creating the default category again brings the deleted one back`() {
        val top = ownCategory("Top", 1)
        val defaultRow = Category.defaultCategoryId(member.id)
        // renamed before it was deleted
        transaction { CategoryTable.update({ CategoryTable.id eq defaultRow }) { it[name] = "Reading" } }
        Category.removeDefaultCategory(member.id)
        assertEquals(true, Category.isDefaultDeleted(member.id))

        val restored = Category.restoreDefaultCategory(member.id)

        assertEquals(defaultRow, restored[CategoryTable.id].value)
        assertEquals("Default", restored[CategoryTable.name])
        assertEquals(false, Category.isDefaultDeleted(member.id))
        assertEquals(true, Category.isDefaultCategoryVisible(member.id))
        // Default is the default one for new manga again: the stand-in gave the mark back
        assertEquals(emptySet<Int>(), flagged())
        assertEquals(true, restored[CategoryTable.isDefault])
        assertEquals(true, top > 0)
    }

    @Test
    fun `several categories can be the default ones while the default category exists`() {
        val top = ownCategory("Top", 1)
        val second = ownCategory("Second", 2)

        Category.setDefaultFlag(member.id, listOf(top), true)
        Category.setDefaultFlag(member.id, listOf(second), true)
        assertEquals(setOf(top, second), flagged())

        Category.setDefaultFlag(member.id, listOf(top, second), false)
        assertEquals(emptySet<Int>(), flagged())
    }

    @Test
    fun `an account that deleted the default category before gets a default one when it is looked at`() {
        val top = ownCategory("Top", 1)
        ownCategory("Second", 2)
        // deleted with an older version: hidden, no default mark
        Category.modifyMeta(Category.defaultCategoryId(member.id), "default_category_hidden", "true")
        assertEquals(emptySet<Int>(), flagged())

        Category.ensureFallbackDefault(member.id)

        assertEquals(setOf(top), flagged())
        // an account that did not delete it is left alone
        val other = UserManager.createUser("default-keep-${System.nanoTime()}", "password-123")
        try {
            Category.ensureFallbackDefault(other.id)
            assertEquals(false, Category.isDefaultDeleted(other.id))
        } finally {
            UserManager.deleteUser(other.id)
        }
    }
}

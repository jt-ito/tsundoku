package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.LibraryShareTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserMangaTable
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createLibraryManga

class LibraryShareTest : ApplicationTest() {
    private lateinit var sender: suwayomi.tachidesk.server.user.model.UserDataClass
    private lateinit var recipient: suwayomi.tachidesk.server.user.model.UserDataClass

    @OptIn(ExperimentalKeywordApi::class)
    @BeforeEach
    fun setUp() {
        TransactionManager.defaultDatabase =
            Database.connect(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;",
                "org.h2.Driver",
                databaseConfig = DatabaseConfig { useNestedTransactions = true; preserveKeywordCasing = false; defaultSchema = null },
            )
        sender = UserManager.createUser("sender-${System.nanoTime()}", "password-123")
        recipient = UserManager.createUser("recipient-${System.nanoTime()}", "password-123")
    }

    @AfterEach
    fun tearDown() {
        clearTables(LibraryShareTable, CategoryMangaTable, CategoryTable, UserMangaTable, MangaTable)
        UserManager.deleteUser(sender.id)
        UserManager.deleteUser(recipient.id)
    }

    private fun addToLibrary(mangaId: Int) =
        transaction {
            UserMangaTable.insert {
                it[user] = EntityID(sender.id, UserTable)
                it[manga] = EntityID(mangaId, MangaTable)
                it[inLibrary] = true
            }
        }

    private fun libraryOf(userId: Int) =
        transaction {
            UserMangaTable.selectAll().where { (UserMangaTable.user eq userId) and (UserMangaTable.inLibrary eq true) }.count()
        }

    @Test
    fun `nothing is copied before the recipient accepts, categories follow the manga`() {
        val a = createLibraryManga("a")
        val b = createLibraryManga("b")
        addToLibrary(a)
        addToLibrary(b)
        val categoryId =
            transaction {
                val id = CategoryTable.insertAndGetId {
                    it[name] = "Favourites"
                    it[user] = EntityID(sender.id, UserTable)
                }.value
                CategoryMangaTable.insert { it[category] = id; it[manga] = a }
                id
            }

        assertThrows(IllegalArgumentException::class.java) {
            LibraryShare.create(sender.id, "nobody-by-that-name", LibraryShare.Scope.LIBRARY, emptyList())
        }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.CATEGORIES, listOf(categoryId))
        assertEquals(0, libraryOf(recipient.id))
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.accept(shareId, sender.id) } // only the recipient may accept

        assertEquals(1, LibraryShare.accept(shareId, recipient.id))
        assertEquals(1, libraryOf(recipient.id)) // only the manga of the shared category
        val copied = transaction { CategoryTable.selectAll().where { CategoryTable.user eq recipient.id }.map { it[CategoryTable.name] } }
        assertEquals(true, "Favourites" in copied)
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.accept(shareId, recipient.id) } // answered once
    }

    @Test
    fun `uncategorized manga of a shared library land in the recipient's default category`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        LibraryShare.accept(shareId, recipient.id)
        val inOwnCategory =
            transaction {
                CategoryMangaTable
                    .innerJoin(CategoryTable)
                    .selectAll()
                    .where { (CategoryTable.user eq recipient.id) and (CategoryMangaTable.manga eq a) }
                    .count()
            }
        assertEquals(1, inOwnCategory)
    }

    @Test
    fun `a declined library share copies nothing`() {
        addToLibrary(createLibraryManga("a"))
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        LibraryShare.decline(shareId, recipient.id)
        assertEquals(0, libraryOf(recipient.id))
    }
}

package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
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
        clearTables(suwayomi.tachidesk.manga.model.table.LibraryShareDeliveredTable, LibraryShareTable, CategoryMangaTable, CategoryTable, UserMangaTable, MangaTable)
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
    fun `the first account's library that only exists in the manga table is shared`() {
        createLibraryManga("legacy") // in_library on the manga row, no user_manga row
        val shareId = LibraryShare.create(1, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        assertEquals(1, LibraryShare.get(shareId, 1).mangaCount)
        assertEquals(1, LibraryShare.accept(shareId, recipient.id))
        assertEquals(1, libraryOf(recipient.id))
    }

    @Test
    fun `a synced share delivers what the sender adds later, but only once`() {
        val first = createLibraryManga("first")
        addToLibrary(first)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)

        // not synced automatically unless the recipient chose so
        LibraryShare.accept(shareId, recipient.id, autoSync = false)
        val second = createLibraryManga("second")
        addToLibrary(second)
        LibraryShare.syncAll()
        assertEquals(1, libraryOf(recipient.id))
        assertEquals(1, LibraryShare.syncNow(shareId, recipient.id))
        assertEquals(2, libraryOf(recipient.id))

        // the recipient drops one, the next automatic sync must not bring it back
        LibraryShare.setAutoSync(shareId, recipient.id, true)
        transaction {
            UserMangaTable.update({ (UserMangaTable.user eq recipient.id) and (UserMangaTable.manga eq first) }) { it[inLibrary] = false }
        }
        val third = createLibraryManga("third")
        addToLibrary(third)
        LibraryShare.syncAll()
        assertEquals(2, libraryOf(recipient.id)) // second + third, not first

        // an unsynced share has nothing to sync
        val plain = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        LibraryShare.accept(plain, recipient.id)
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.syncNow(plain, recipient.id) }
    }

    @Test
    fun `a new category of a synced share reaches the recipient, and the sender can stop syncing`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        transaction {
            val id = CategoryTable.insertAndGetId {
                it[name] = "Added later"
                it[user] = EntityID(sender.id, UserTable)
            }.value
            CategoryMangaTable.insert { it[category] = id; it[manga] = a }
        }
        LibraryShare.syncAll()
        val names = transaction { CategoryTable.selectAll().where { CategoryTable.user eq recipient.id }.map { it[CategoryTable.name] } }
        assertEquals(true, "Added later" in names)

        LibraryShare.cancelOrStop(shareId, sender.id)
        val b = createLibraryManga("b")
        addToLibrary(b)
        LibraryShare.syncAll()
        assertEquals(1, libraryOf(recipient.id))
    }

    @Test
    fun `the recipient of a synced share can ask for two way sync, the sender has to accept it`() {
        val first = createLibraryManga("first")
        addToLibrary(first)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)

        // only an accepted synced share can be answered with a request
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.requestTwoWay(shareId, recipient.id) }
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        val requestId = LibraryShare.requestTwoWay(shareId, recipient.id)
        assertEquals(LibraryShare.Status.PENDING, LibraryShare.get(shareId, recipient.id).twoWayStatus)
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.requestTwoWay(shareId, recipient.id) }

        // nothing flows back before the original sender accepts
        val mine = createLibraryManga("recipient's own")
        transaction {
            UserMangaTable.insert {
                it[user] = EntityID(recipient.id, UserTable)
                it[manga] = EntityID(mine, MangaTable)
                it[inLibrary] = true
            }
        }
        LibraryShare.syncAll()
        assertEquals(1, libraryOf(sender.id))

        LibraryShare.accept(requestId, sender.id, autoSync = true)
        assertEquals(2, libraryOf(sender.id)) // first + the recipient's own manga

        // and later additions of the recipient follow
        val later = createLibraryManga("recipient's later")
        transaction {
            UserMangaTable.insert {
                it[user] = EntityID(recipient.id, UserTable)
                it[manga] = EntityID(later, MangaTable)
                it[inLibrary] = true
            }
        }
        LibraryShare.syncAll()
        assertEquals(3, libraryOf(sender.id))
    }

    @Test
    fun `sharing into another account's category does not make the sender's manga categorized`() {
        val a = createLibraryManga("a") // the first account's, legacy library
        val shareId = LibraryShare.create(1, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        LibraryShare.accept(shareId, recipient.id)

        val linked = transaction { CategoryMangaTable.selectAll().where { CategoryMangaTable.manga eq a }.count() }
        assertEquals(true, linked > 0) // it sits in a category of the recipient
        val uncategorizedForFirst =
            transaction { MangaTable.selectAll().where { Category.uncategorizedOf(1) }.map { it[MangaTable.id].value } }
        assertEquals(true, a in uncategorizedForFirst)
        val uncategorizedForRecipient =
            transaction { MangaTable.selectAll().where { Category.uncategorizedOf(recipient.id) }.map { it[MangaTable.id].value } }
        assertEquals(false, a in uncategorizedForRecipient)
    }

    @Test
    fun `sending the library again does not duplicate what the recipient already has`() {
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

        val first = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        assertEquals(2, LibraryShare.accept(first, recipient.id))

        // the sender adds one more, then sends the library again, once as a whole and once as a category
        val c = createLibraryManga("c")
        addToLibrary(c)
        val second = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        assertEquals(1, LibraryShare.accept(second, recipient.id)) // only the new one counts
        val third = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.CATEGORIES, listOf(categoryId))
        assertEquals(0, LibraryShare.accept(third, recipient.id))

        transaction {
            val rows = UserMangaTable.selectAll().where { UserMangaTable.user eq recipient.id }.map { it[UserMangaTable.manga].value }
            assertEquals(rows.size, rows.toSet().size) // no manga twice
            assertEquals(3, rows.size)

            val ownCategories = CategoryTable.selectAll().where { CategoryTable.user eq recipient.id }.map { it[CategoryTable.id].value to it[CategoryTable.name] }
            assertEquals(1, ownCategories.count { it.second == "Favourites" }) // matched by name, not created again
            val links = CategoryMangaTable.selectAll().where { CategoryMangaTable.category inList ownCategories.map { it.first } }.map { it[CategoryMangaTable.category].value to it[CategoryMangaTable.manga].value }
            assertEquals(links.size, links.toSet().size) // no manga twice in a category
        }
    }

    @Test
    fun `a change of the sender reaches an automatic share on its own, shortly after`() {
        addToLibrary(createLibraryManga("first"))
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)
        assertEquals(1, libraryOf(recipient.id))

        addToLibrary(createLibraryManga("added later"))
        LibraryShare.requestSync(sender.id)

        // no manual sync and no timer involved: the request runs in the background
        var waited = 0
        while (libraryOf(recipient.id) < 2 && waited < 100) {
            Thread.sleep(100)
            waited++
        }
        assertEquals(2, libraryOf(recipient.id))
    }

    @Test
    fun `an empty library cannot be shared`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        }
    }

    @Test
    fun `a declined library share copies nothing`() {
        addToLibrary(createLibraryManga("a"))
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        LibraryShare.decline(shareId, recipient.id)
        assertEquals(0, libraryOf(recipient.id))
    }
}

package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
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
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.LibraryShareTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.UserChapterTable
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

    private fun senderRemoves(mangaId: Int) =
        transaction {
            UserMangaTable.update({ (UserMangaTable.user eq sender.id) and (UserMangaTable.manga eq mangaId) }) { it[inLibrary] = false }
        }

    private fun recipientCategoriesOf(mangaId: Int): List<String> =
        transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where { (CategoryTable.user eq recipient.id) and (CategoryMangaTable.manga eq mangaId) }
                .map { it[CategoryTable.name] }
        }

    @Test
    fun `a manga the sender removes is removed for the recipient, and comes back when the sender adds it again`() {
        val a = createLibraryManga("a")
        val b = createLibraryManga("b")
        addToLibrary(a)
        addToLibrary(b)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)
        assertEquals(2, libraryOf(recipient.id))

        senderRemoves(a)
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(1, libraryOf(recipient.id))

        transaction {
            UserMangaTable.update({ (UserMangaTable.user eq sender.id) and (UserMangaTable.manga eq a) }) { it[inLibrary] = true }
        }
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(2, libraryOf(recipient.id))
    }

    @Test
    fun `moving a manga to another category moves it for the recipient`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        val (reading, done) =
            transaction {
                val reading = CategoryTable.insertAndGetId { it[name] = "Reading"; it[user] = EntityID(sender.id, UserTable) }.value
                val done = CategoryTable.insertAndGetId { it[name] = "Done"; it[user] = EntityID(sender.id, UserTable) }.value
                CategoryMangaTable.insert { it[category] = reading; it[manga] = a }
                reading to done
            }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)
        assertEquals(listOf("Reading"), recipientCategoriesOf(a))

        transaction {
            CategoryMangaTable.deleteWhere { (CategoryMangaTable.category eq reading) and (CategoryMangaTable.manga eq a) }
            CategoryMangaTable.insert { it[category] = done; it[manga] = a }
        }
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(listOf("Done"), recipientCategoriesOf(a))
    }

    @Test
    fun `a change of the recipient is left alone until the sender changes that manga`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        val reading =
            transaction {
                val id = CategoryTable.insertAndGetId { it[name] = "Reading"; it[user] = EntityID(sender.id, UserTable) }.value
                CategoryMangaTable.insert { it[category] = id; it[manga] = a }
                id
            }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        // the recipient takes it out of the category, the sender did nothing
        transaction {
            val own = CategoryTable.selectAll().where { (CategoryTable.user eq recipient.id) and (CategoryTable.name eq "Reading") }.first()[CategoryTable.id].value
            CategoryMangaTable.deleteWhere { (CategoryMangaTable.category eq own) and (CategoryMangaTable.manga eq a) }
        }
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(false, "Reading" in recipientCategoriesOf(a))
        assertEquals(true, reading > 0)
    }

    private fun categoryNames(userId: Int): List<String> =
        transaction { CategoryTable.selectAll().where { CategoryTable.user eq userId }.map { it[CategoryTable.name] } }

    private fun renameCategory(
        userId: Int,
        from: String,
        to: String,
    ) = transaction {
        CategoryTable.update({ (CategoryTable.user eq userId) and (CategoryTable.name eq from) }) { it[name] = to }
    }

    private fun sharedCategorySetup(mirror: Boolean): Pair<Int, Int> {
        val a = createLibraryManga("a")
        addToLibrary(a)
        transaction {
            val id = CategoryTable.insertAndGetId { it[name] = "Reading"; it[user] = EntityID(sender.id, UserTable) }.value
            CategoryMangaTable.insert { it[category] = id; it[manga] = a }
        }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true, mirror = mirror)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)
        return shareId to a
    }

    @Test
    fun `a recipient can rename a shared category and it still follows the same category of the sender`() {
        val (shareId, _) = sharedCategorySetup(mirror = false)
        renameCategory(recipient.id, "Reading", "My reading")

        // a new manga in the sender's category lands in the renamed one, no second "Reading" appears
        val b = createLibraryManga("b")
        addToLibrary(b)
        transaction {
            val reading = CategoryTable.selectAll().where { (CategoryTable.user eq sender.id) and (CategoryTable.name eq "Reading") }.first()[CategoryTable.id].value
            CategoryMangaTable.insert { it[category] = reading; it[manga] = b }
        }
        LibraryShare.syncNow(shareId, recipient.id)

        assertEquals(listOf("My reading"), recipientCategoriesOf(b))
        assertEquals(false, "Reading" in categoryNames(recipient.id))
        // without one for one the sender's name is not touched and does not overwrite the recipient's
        renameCategory(sender.id, "Reading", "Sender's name")
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(true, "My reading" in categoryNames(recipient.id))
        assertEquals(true, "Sender's name" in categoryNames(sender.id))
    }

    @Test
    fun `one for one keeps the names of shared categories equal in both directions`() {
        val (shareId, _) = sharedCategorySetup(mirror = true)

        renameCategory(sender.id, "Reading", "Now reading")
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(true, "Now reading" in categoryNames(recipient.id))

        renameCategory(recipient.id, "Now reading", "Finished soon")
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(true, "Finished soon" in categoryNames(sender.id))
        assertEquals(false, "Now reading" in categoryNames(recipient.id))
    }

    @Test
    fun `a series the sender migrated carries the recipient's progress along`() {
        val old = createLibraryManga("old")
        val new = createLibraryManga("new")
        addToLibrary(old)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        fun chaptersOf(mangaId: Int) =
            transaction {
                (1..5).map { number ->
                    ChapterTable
                        .insertAndGetId {
                            it[name] = "$number"
                            it[url] = "$mangaId-$number"
                            it[sourceOrder] = number
                            it[manga] = EntityID(mangaId, MangaTable)
                            it[chapter_number] = number.toFloat()
                        }.value
                }
            }
        val oldChapters = chaptersOf(old)
        val newChapters = chaptersOf(new)
        // the recipient read 1-3 of the old series and bookmarked 2, with a page in progress
        transaction {
            oldChapters.take(3).forEachIndexed { index, id ->
                UserChapterTable.insert {
                    it[user] = EntityID(recipient.id, UserTable)
                    it[chapter] = EntityID(id, ChapterTable)
                    it[isRead] = true
                    it[isBookmarked] = index == 1
                    it[lastPageRead] = 7
                }
            }
        }

        // what the WebUI does when the sender migrates: tell the server, add the new series, remove the old one
        MangaSwap.record(sender.id, old, new)
        addToLibrary(new)
        senderRemoves(old)
        LibraryShare.syncNow(shareId, recipient.id)

        val states =
            transaction {
                UserChapterTable
                    .selectAll()
                    .where { (UserChapterTable.user eq recipient.id) and (UserChapterTable.chapter inList newChapters) }
                    .associate { it[UserChapterTable.chapter].value to it }
            }
        assertEquals(newChapters.take(3).toSet(), states.filterValues { it[UserChapterTable.isRead] }.keys)
        assertEquals(setOf(newChapters[1]), states.filterValues { it[UserChapterTable.isBookmarked] }.keys)
        // like a normal migration, the page in progress does not move
        assertEquals(setOf(0), states.values.map { it[UserChapterTable.lastPageRead] }.toSet())
    }

    @Test
    fun `a manga moved out of the default into a category leaves the recipient's default category`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        val reading = transaction { CategoryTable.insertAndGetId { it[name] = "Reading"; it[user] = EntityID(sender.id, UserTable) }.value }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true, mirror = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)
        // without a category it lands in the recipient's default category
        assertEquals(listOf("Default"), recipientCategoriesOf(a))

        transaction { CategoryMangaTable.insert { it[category] = reading; it[manga] = a } }
        LibraryShare.syncNow(shareId, recipient.id)

        assertEquals(listOf("Reading"), recipientCategoriesOf(a))
    }

    @Test
    fun `one for one keeps the order of shared categories equal in both directions`() {
        val a = createLibraryManga("a")
        addToLibrary(a)
        transaction {
            listOf("Reading", "Planned", "Done").forEach { n ->
                val id = CategoryTable.insertAndGetId { it[name] = n; it[order] = Int.MAX_VALUE; it[user] = EntityID(sender.id, UserTable) }.value
                CategoryMangaTable.insert { it[category] = id; it[manga] = a }
            }
            Category.normalizeCategories()
        }
        fun orderOf(owner: Int) =
            transaction {
                CategoryTable.selectAll().where { (CategoryTable.user eq owner) and (CategoryTable.name neq "Default") }
                    .orderBy(CategoryTable.order to org.jetbrains.exposed.v1.core.SortOrder.ASC).map { it[CategoryTable.name] }
            }
        fun setOrder(owner: Int, names: List<String>) =
            transaction {
                names.forEachIndexed { i, n -> CategoryTable.update({ (CategoryTable.user eq owner) and (CategoryTable.name eq n) }) { it[order] = 100 + i } }
                Category.normalizeCategories()
            }
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true, mirror = true)
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        setOrder(sender.id, listOf("Done", "Reading", "Planned"))
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(listOf("Done", "Reading", "Planned"), orderOf(recipient.id))

        setOrder(recipient.id, listOf("Planned", "Done", "Reading"))
        LibraryShare.syncNow(shareId, recipient.id)
        assertEquals(listOf("Planned", "Done", "Reading"), orderOf(sender.id))

        // the Default category is placed too
        setOrder(sender.id, listOf("Default", "Planned", "Done", "Reading"))
        LibraryShare.syncNow(shareId, recipient.id)
        val recipientNames = transaction { CategoryTable.selectAll().where { CategoryTable.user eq recipient.id }.orderBy(CategoryTable.order to org.jetbrains.exposed.v1.core.SortOrder.ASC).map { it[CategoryTable.name] } }
        assertEquals("Default", recipientNames.first())
    }

    @Test
    fun `changing the settings of a share needs the other account to confirm`() {
        addToLibrary(createLibraryManga("a"))
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList())
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.proposeEdit(shareId, sender.id, true, false) } // still pending
        LibraryShare.accept(shareId, recipient.id)

        // the recipient asks for sync + one for one, nothing changes until the sender confirms
        LibraryShare.proposeEdit(shareId, recipient.id, synced = true, mirror = true)
        var share = LibraryShare.get(shareId, sender.id)
        assertEquals(false, share.synced)
        assertEquals(true, share.proposedSynced)
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.respondToEdit(shareId, recipient.id, true) } // not their own
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.proposeEdit(shareId, sender.id, true, false) } // one at a time

        LibraryShare.respondToEdit(shareId, sender.id, accept = true)
        share = LibraryShare.get(shareId, sender.id)
        assertEquals(true, share.synced)
        assertEquals(true, share.mirror)
        assertEquals(null, share.proposedBy)

        // declined and taken back proposals change nothing
        LibraryShare.proposeEdit(shareId, sender.id, synced = false, mirror = false)
        LibraryShare.respondToEdit(shareId, recipient.id, accept = false)
        assertEquals(true, LibraryShare.get(shareId, sender.id).synced)
        LibraryShare.proposeEdit(shareId, sender.id, synced = true, mirror = false)
        LibraryShare.cancelEdit(shareId, sender.id)
        assertEquals(true, LibraryShare.get(shareId, sender.id).mirror)

        // turning sync off also turns the rest off
        LibraryShare.setAutoSync(shareId, recipient.id, true)
        LibraryShare.proposeEdit(shareId, sender.id, synced = false, mirror = false)
        LibraryShare.respondToEdit(shareId, recipient.id, accept = true)
        share = LibraryShare.get(shareId, recipient.id)
        assertEquals(false, share.synced)
        assertEquals(false, share.mirror)
        assertEquals(false, share.autoSync)
    }

    @Test
    fun `either account can remove a share that was answered, it stops syncing for both`() {
        val first = createLibraryManga("first")
        addToLibrary(first)
        val shareId = LibraryShare.create(sender.id, recipient.username, LibraryShare.Scope.LIBRARY, emptyList(), synced = true)
        assertThrows(IllegalArgumentException::class.java) { LibraryShare.remove(shareId, sender.id) } // still waiting for an answer
        LibraryShare.accept(shareId, recipient.id, autoSync = true)

        val outsider = UserManager.createUser("outsider-${System.nanoTime()}", "password-123")
        try {
            assertThrows(IllegalArgumentException::class.java) { LibraryShare.remove(shareId, outsider.id) }
        } finally {
            UserManager.deleteUser(outsider.id)
        }

        LibraryShare.remove(shareId, recipient.id)
        assertEquals(0, LibraryShare.list(sender.id).size)
        addToLibrary(createLibraryManga("later"))
        LibraryShare.syncAll()
        assertEquals(1, libraryOf(recipient.id)) // what was copied stays, nothing new arrives
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

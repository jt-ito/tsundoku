package suwayomi.tachidesk.manga.impl

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.model.UserDataClass
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.test.ApplicationTest

class CategoryReorderTest : ApplicationTest() {
    private lateinit var other: UserDataClass

    @OptIn(ExperimentalKeywordApi::class)
    @BeforeEach
    fun setUp() {
        TransactionManager.defaultDatabase =
            Database.connect(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;",
                "org.h2.Driver",
                databaseConfig = DatabaseConfig { useNestedTransactions = true; preserveKeywordCasing = false; defaultSchema = null },
            )
        other = UserManager.createUser("reorder-${System.nanoTime()}", "password-123")
    }

    @AfterEach
    fun tearDown() {
        transaction { CategoryTable.deleteWhere { (CategoryTable.user eq 1) and (CategoryTable.name neq "Default") } }
        UserManager.deleteUser(other.id)
    }


    private fun create(
        name: String,
        owner: Int,
    ) = transaction {
        CategoryTable.insert {
            it[CategoryTable.name] = name
            it[order] = Int.MAX_VALUE
            it[user] = EntityID(owner, UserTable)
        }
        Category.normalizeCategories()
    }

    private fun namesOf(owner: Int) =
        transaction {
            CategoryTable
                .selectAll()
                .where { CategoryTable.user eq owner }
                .orderBy(CategoryTable.order to SortOrder.ASC, CategoryTable.id to SortOrder.ASC)
                .map { it[CategoryTable.name] }
        }

    @Test
    fun `moving a category only counts the categories of the account`() {
        // the accounts' categories are interleaved in the raw order values, as they are in a real database
        create("A", 1)
        create("X", other.id)
        create("B", 1)
        create("Y", other.id)
        create("C", 1)
        val c = transaction { CategoryTable.selectAll().where { CategoryTable.name eq "C" }.first()[CategoryTable.id].value }

        // position 1 of the first account's list, whatever the other account has in between
        Category.moveCategoryToPosition(c, 1, userId = 1)

        assertEquals("C", namesOf(1).first())
        assertEquals(listOf("A", "B"), namesOf(1).filter { it in setOf("A", "B") })
        assertEquals(listOf("X", "Y"), namesOf(other.id).filter { it in setOf("X", "Y") })
    }
}

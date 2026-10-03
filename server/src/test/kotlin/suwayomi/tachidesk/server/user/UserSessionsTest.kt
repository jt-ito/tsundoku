package suwayomi.tachidesk.server.user

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import suwayomi.tachidesk.global.impl.util.Jwt
import suwayomi.tachidesk.server.user.model.UserDataClass
import suwayomi.tachidesk.test.ApplicationTest

class UserSessionsTest : ApplicationTest() {
    private lateinit var user: UserDataClass

    @OptIn(ExperimentalKeywordApi::class)
    @BeforeEach
    fun setUp() {
        TransactionManager.defaultDatabase =
            Database.connect(
                "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;",
                "org.h2.Driver",
                databaseConfig = DatabaseConfig { useNestedTransactions = true; preserveKeywordCasing = false; defaultSchema = null },
            )
        user = UserManager.createUser("session-${System.nanoTime()}", "password-123")
    }

    @AfterEach
    fun tearDown() {
        UserManager.deleteUser(user.id)
    }

    private fun login() = Jwt.generateJwt(user.id, user.username, user.role)

    @Test
    fun `a refresh token works until its session ends, other sessions are not affected`() {
        val phone = login()
        val laptop = login()
        assertTrue(Jwt.refreshJwt(phone.refreshToken).isNotEmpty())

        Jwt.revokeRefreshToken(phone.refreshToken)
        assertThrows<IllegalArgumentException> { Jwt.refreshJwt(phone.refreshToken) }
        assertTrue(Jwt.refreshJwt(laptop.refreshToken).isNotEmpty())
    }

    @Test
    fun `a new password ends every session of the account`() {
        val tokens = login()
        UserManager.updateUser(user.id, newPassword = "another-password-456")
        assertThrows<IllegalArgumentException> { Jwt.refreshJwt(tokens.refreshToken) }
    }

    @Test
    fun `an access token cannot be revoked or refreshed as a refresh token`() {
        val tokens = login()
        assertThrows<IllegalArgumentException> { Jwt.revokeRefreshToken(tokens.accessToken) }
        assertThrows<IllegalArgumentException> { Jwt.refreshJwt(tokens.accessToken) }
    }
}

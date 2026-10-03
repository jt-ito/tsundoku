package suwayomi.tachidesk.server.user

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.model.UserDataClass
import suwayomi.tachidesk.server.user.model.UserTable
import suwayomi.tachidesk.server.user.model.toDataClass

object UserManager {
    val USERNAME_REGEX = Regex("^[a-zA-Z0-9._-]{3,32}$")
    const val MIN_PASSWORD_LENGTH = 4
    const val MAX_PASSWORD_LENGTH = 128

    fun validateUsername(username: String) {
        val trimmed = username.trim()
        require(trimmed.isNotEmpty()) { "Username cannot be empty" }
        require(trimmed.length in 3..32) { "Username must be between 3 and 32 characters" }
        require(USERNAME_REGEX.matches(trimmed)) {
            "Username can only contain alphanumeric characters, underscores, hyphens, and dots"
        }
        require(!trimmed.contains("<") && !trimmed.contains(">") && !trimmed.contains("\"") && !trimmed.contains("'") && !trimmed.contains("/") && !trimmed.contains("\\")) {
            "Username contains invalid characters"
        }
    }

    fun validatePassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Password must be at least $MIN_PASSWORD_LENGTH characters"
        }
        require(password.length <= MAX_PASSWORD_LENGTH) {
            "Password must not exceed $MAX_PASSWORD_LENGTH characters"
        }
        require(password.none { it.isISOControl() || it == '\u0000' }) {
            "Password cannot contain control characters"
        }
    }

    fun authenticate(username: String, password: String): UserDataClass? {
        val trimmed = username.trim()
        if (trimmed.length !in 2..64 || password.length > MAX_PASSWORD_LENGTH || password.isEmpty()) {
            return null
        }
        if (trimmed.any { it.isISOControl() || it == '<' || it == '>' || it == '\u0000' || it == '"' || it == '\'' }) {
            return null
        }
        return try {
            transaction {
                val row =
                    UserTable
                        .selectAll()
                        .where { UserTable.username eq trimmed }
                        .firstOrNull()

                if (row != null && row[UserTable.passwordHash].isEmpty()) {
                    // unclaimed first-run account, see SetupManager
                    null
                } else if (row != null) {
                    val salt = row[UserTable.salt]
                    val hash = row[UserTable.passwordHash]
                    if (PasswordHasher.verifyPassword(password, salt, hash)) {
                        val userId = row[UserTable.id].value
                        val now = System.currentTimeMillis()
                        UserTable.update({ UserTable.id eq userId }) {
                            it[lastLoginAt] = now
                        }
                        UserTable.toDataClass(row).copy(lastLoginAt = now)
                    } else {
                        null
                    }
                } else {
                    // Fallback to serverConfig for bootstrap/legacy admin
                    if (username == serverConfig.authUsername.value && password == serverConfig.authPassword.value) {
                        UserDataClass(
                            id = 1,
                            username = username,
                            role = "ADMIN",
                            createdAt = 0,
                            lastLoginAt = System.currentTimeMillis(),
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            // DB not ready or migration running
            if (username == serverConfig.authUsername.value && password == serverConfig.authPassword.value) {
                UserDataClass(
                    id = 1,
                    username = username,
                    role = "ADMIN",
                    createdAt = 0,
                    lastLoginAt = System.currentTimeMillis(),
                )
            } else {
                null
            }
        }
    }

    fun getUser(id: Int): UserDataClass? =
        transaction {
            UserTable
                .selectAll()
                .where { UserTable.id eq id }
                .firstOrNull()
                ?.let { UserTable.toDataClass(it) }
        }

    fun listUsers(): List<UserDataClass> =
        transaction {
            UserTable
                .selectAll()
                .orderBy(UserTable.id)
                .map { UserTable.toDataClass(it) }
        }

    fun createUser(
        username: String,
        password: String,
        role: String = "MEMBER",
    ): UserDataClass {
        val cleanUsername = username.trim()
        validateUsername(cleanUsername)
        validatePassword(password)
        require(role.uppercase() in setOf("ADMIN", "MEMBER")) { "Role must be ADMIN or MEMBER" }
        val normalizedRole = role.uppercase()

        return transaction {
            val existing = UserTable.selectAll().where { UserTable.username eq cleanUsername }.firstOrNull()
            require(existing == null) { "Username already exists" }

            val salt = PasswordHasher.generateSalt()
            val hash = PasswordHasher.hashPassword(password, salt)
            val now = System.currentTimeMillis()

            val newId =
                UserTable.insertAndGetId {
                    it[UserTable.username] = cleanUsername
                    it[UserTable.passwordHash] = hash
                    it[UserTable.salt] = salt
                    it[UserTable.role] = normalizedRole
                    it[UserTable.createdAt] = now
                    it[UserTable.lastLoginAt] = 0
                }.value

            // Create default category for new user
            CategoryTable.insert {
                it[name] = "Default"
                it[isDefault] = true
                it[order] = 0
                it[user] = EntityID(newId, UserTable)
            }

            UserDataClass(
                id = newId,
                username = cleanUsername,
                role = normalizedRole,
                createdAt = now,
                lastLoginAt = 0,
            )
        }
    }

    /**
     * Gives a restored server backup the account of [username]: the existing one, or a new one with the stored login.
     * An existing account keeps its password and role.
     */
    fun findOrCreateForRestore(
        username: String,
        passwordHash: String,
        salt: String,
        role: String,
        createdAt: Long,
    ): Int =
        transaction {
            val existing = UserTable.selectAll().where { UserTable.username eq username }.firstOrNull()
            if (existing != null) {
                return@transaction existing[UserTable.id].value
            }

            validateUsername(username)
            val newId =
                UserTable.insertAndGetId {
                    it[UserTable.username] = username
                    it[UserTable.passwordHash] = passwordHash
                    it[UserTable.salt] = salt
                    it[UserTable.role] = if (role.uppercase() in setOf("ADMIN", "MEMBER")) role.uppercase() else "MEMBER"
                    it[UserTable.createdAt] = createdAt
                    it[UserTable.lastLoginAt] = 0
                }.value

            CategoryTable.insert {
                it[name] = "Default"
                it[isDefault] = true
                it[order] = 0
                it[user] = EntityID(newId, UserTable)
            }

            newId
        }

    fun updateUser(
        id: Int,
        newUsername: String? = null,
        newPassword: String? = null,
        newRole: String? = null,
    ): UserDataClass =
        transaction {
            val row = UserTable.selectAll().where { UserTable.id eq id }.firstOrNull()
                ?: throw NoSuchElementException("User not found with id $id")

            val cleanUsername = newUsername?.trim()
            if (!cleanUsername.isNullOrEmpty()) {
                validateUsername(cleanUsername)
                val existing =
                    UserTable
                        .selectAll()
                        .where { (UserTable.username eq cleanUsername) and (UserTable.id neq id) }
                        .firstOrNull()
                require(existing == null) { "Username already exists" }
            }

            if (!newPassword.isNullOrEmpty()) {
                validatePassword(newPassword)
            }

            UserTable.update({ UserTable.id eq id }) {
                if (!cleanUsername.isNullOrEmpty()) {
                    it[username] = cleanUsername
                }
                if (!newPassword.isNullOrEmpty()) {
                    val salt = PasswordHasher.generateSalt()
                    val hash = PasswordHasher.hashPassword(newPassword, salt)
                    it[UserTable.salt] = salt
                    it[UserTable.passwordHash] = hash
                }
                if (!newRole.isNullOrBlank()) {
                    require(newRole.uppercase() in setOf("ADMIN", "MEMBER")) { "Role must be ADMIN or MEMBER" }
                    it[role] = newRole.uppercase()
                }
            }

            // a new password signs the account out everywhere
            if (!newPassword.isNullOrEmpty()) {
                UserSessions.deleteAll(id)
            }

            UserTable.selectAll().where { UserTable.id eq id }.first().let { UserTable.toDataClass(it) }
        }

    fun deleteUser(id: Int) {
        require(id != 1) { "Cannot delete primary admin user (id=1)" }
        transaction {
            UserTable.deleteWhere { UserTable.id eq id }
            // these tables only know the account by its number, nothing removes their rows with it
            GlobalMetaTable.deleteWhere { GlobalMetaTable.user eq id }
            MangaMetaTable.deleteWhere { MangaMetaTable.user eq id }
            ChapterMetaTable.deleteWhere { ChapterMetaTable.user eq id }
            SourceMetaTable.deleteWhere { SourceMetaTable.user eq id }
        }
        TrackerManager.forgetUser(id)
    }
}

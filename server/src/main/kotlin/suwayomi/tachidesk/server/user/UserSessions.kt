package suwayomi.tachidesk.server.user

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.user.model.UserSessionTable
import suwayomi.tachidesk.server.user.model.UserTable
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Sessions make "stay signed in" revocable: a refresh token is bound to one session, signing out deletes it, and a
 * password change deletes all sessions of the account. A leaked refresh token is therefore not good for 180 days
 * regardless of what the owner does.
 */
object UserSessions {
    fun create(userId: Int): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = Instant.now().epochSecond

        transaction {
            UserSessionTable.insert {
                it[id] = sessionId
                it[user] = EntityID(userId, UserTable)
                it[createdAt] = now
                it[lastUsedAt] = now
            }
        }
        return sessionId
    }

    /** @return whether the session of the account is still alive, and marks it as used if so */
    fun touch(
        sessionId: String,
        userId: Int,
    ): Boolean =
        transaction {
            UserSessionTable.update({ (UserSessionTable.id eq sessionId) and (UserSessionTable.user eq userId) }) {
                it[lastUsedAt] = Instant.now().epochSecond
            } > 0
        }

    fun delete(sessionId: String) {
        transaction { UserSessionTable.deleteWhere { UserSessionTable.id eq sessionId } }
    }

    fun deleteAll(userId: Int) {
        transaction { UserSessionTable.deleteWhere { UserSessionTable.user eq userId } }
    }
}

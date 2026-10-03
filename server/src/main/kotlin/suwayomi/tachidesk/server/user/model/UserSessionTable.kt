package suwayomi.tachidesk.server.user.model

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** One row per signed-in device and account. A refresh token only works while the row of its session exists. */
object UserSessionTable : Table("user_session") {
    val id = varchar("id", 64)
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val createdAt = long("created_at").default(0)
    val lastUsedAt = long("last_used_at").default(0)

    override val primaryKey = PrimaryKey(id)
}

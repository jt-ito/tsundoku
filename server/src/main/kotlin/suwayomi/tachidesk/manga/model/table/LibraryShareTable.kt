package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.server.user.model.UserTable

object LibraryShareTable : IntIdTable("library_share") {
    val sender = reference("sender_id", UserTable, ReferenceOption.CASCADE)
    val recipient = reference("recipient_id", UserTable, ReferenceOption.CASCADE)
    val scope = varchar("scope", 16) // LIBRARY or CATEGORIES
    val categoryIds = varchar("category_ids", 2048).default("") // comma separated, only for CATEGORIES
    val status = varchar("status", 16).default("PENDING") // PENDING, ACCEPTED, DECLINED, CANCELLED
    val createdAt = long("created_at").default(0)
    val respondedAt = long("responded_at").default(0)
}

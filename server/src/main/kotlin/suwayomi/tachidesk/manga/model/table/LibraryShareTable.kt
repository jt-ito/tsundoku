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

    /** the sender keeps the share up to date with new manga and categories */
    val synced = bool("synced").default(false)

    /** the recipient lets a synced share follow the sender by itself */
    val autoSync = bool("auto_sync").default(false)
    val lastSyncedAt = long("last_synced_at").default(0)

    /** set on a two way request: the share of the other direction it answers */
    val pairedWith = integer("paired_with").nullable()
}

/** What a share has already delivered, so a manga the recipient removed is not added again by the next sync. */
object LibraryShareDeliveredTable : IntIdTable("library_share_delivered") {
    val share = reference("share_id", LibraryShareTable, ReferenceOption.CASCADE)
    val kind = varchar("kind", 16) // MANGA or CATEGORY
    val ref = integer("ref_id")
}

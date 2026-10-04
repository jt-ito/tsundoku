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

    /** one for one: renaming a shared category on one side renames it on the other side too */
    val mirror = bool("mirror").default(false)

    /** a change of the settings that the other account still has to confirm, null when there is none */
    val proposedSynced = bool("proposed_synced").nullable()
    val proposedMirror = bool("proposed_mirror").nullable()
    val proposedBy = integer("proposed_by").nullable()
}

/**
 * Which category of the recipient follows which category of the sender, by id. A recipient may rename their category
 * without breaking this, and a one for one share keeps the names equal. [lastName] is the name both sides had when they
 * were last in step.
 */
object LibraryShareCategoryTable : IntIdTable("library_share_category") {
    val share = reference("share_id", LibraryShareTable, ReferenceOption.CASCADE)
    val senderCategory = reference("sender_category_id", CategoryTable, ReferenceOption.CASCADE)
    val recipientCategory = reference("recipient_category_id", CategoryTable, ReferenceOption.CASCADE)
    val lastName = varchar("last_name", 64).default("")
    val lastPosition = integer("last_position").nullable()
}

/** What a share has already delivered, so a manga the recipient removed is not added again by the next sync. */
object LibraryShareDeliveredTable : IntIdTable("library_share_delivered") {
    val share = reference("share_id", LibraryShareTable, ReferenceOption.CASCADE)
    val kind = varchar("kind", 16) // MANGA or CATEGORY
    val ref = integer("ref_id")

    /** a LINK is a category (ref) and a manga (ref2) */
    val ref2 = integer("ref2_id").nullable()
}

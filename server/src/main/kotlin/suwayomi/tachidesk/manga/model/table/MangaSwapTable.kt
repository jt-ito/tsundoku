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

/** "this account replaced a series with another one" (a migration to another source), told by the WebUI. */
object MangaSwapTable : IntIdTable("manga_swap") {
    val user = reference("user_id", UserTable, ReferenceOption.CASCADE)
    val oldManga = reference("old_manga_id", MangaTable, ReferenceOption.CASCADE)
    val newManga = reference("new_manga_id", MangaTable, ReferenceOption.CASCADE)
    val createdAt = long("created_at")
}

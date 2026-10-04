package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/** One for one shares also keep the order of the paired categories equal; this is the order agreed at the last sync. */
@Suppress("ClassName", "unused")
class M0075_LibraryShareCategoryOrder : SQLMigration() {
    override val sql =
        """
        ALTER TABLE library_share_category ADD COLUMN IF NOT EXISTS last_position INT;
        """.trimIndent()
}

package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/** A change of a share's settings is proposed by one account and applies once the other one confirms. */
@Suppress("ClassName", "unused")
class M0074_LibraryShareEdits : SQLMigration() {
    override val sql =
        """
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS proposed_synced BOOLEAN NULL;
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS proposed_mirror BOOLEAN NULL;
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS proposed_by INT NULL;
        """.trimIndent()
}

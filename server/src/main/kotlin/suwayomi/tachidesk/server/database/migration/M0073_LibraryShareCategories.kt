package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.serverConfig

/**
 * A synced share follows categories by id instead of by name (a category can be renamed on either side), and can be
 * one for one: renaming a shared category on one side renames it on the other side too.
 */
@Suppress("ClassName", "unused")
class M0073_LibraryShareCategories : SQLMigration() {
    override val sql by lazy {
        val idColumn =
            when (serverConfig.databaseType.value) {
                DatabaseType.H2 -> "INT AUTO_INCREMENT PRIMARY KEY"
                DatabaseType.POSTGRESQL -> "SERIAL PRIMARY KEY"
            }

        """
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS mirror BOOLEAN NOT NULL DEFAULT FALSE;

        CREATE TABLE IF NOT EXISTS library_share_category (
            id $idColumn,
            share_id INT NOT NULL REFERENCES library_share(id) ON DELETE CASCADE,
            sender_category_id INT NOT NULL REFERENCES category(id) ON DELETE CASCADE,
            recipient_category_id INT NOT NULL REFERENCES category(id) ON DELETE CASCADE,
            last_name VARCHAR(64) NOT NULL DEFAULT ''
        );
        """.trimIndent()
    }
}

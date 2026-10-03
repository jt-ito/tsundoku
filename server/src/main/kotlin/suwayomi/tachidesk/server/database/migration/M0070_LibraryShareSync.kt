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

/** A library share can stay synced: the sender keeps adding to it, the recipient decides to follow automatically. */
@Suppress("ClassName", "unused")
class M0070_LibraryShareSync : SQLMigration() {
    override val sql by lazy {
        val idColumn =
            when (serverConfig.databaseType.value) {
                DatabaseType.H2 -> "INT AUTO_INCREMENT PRIMARY KEY"
                DatabaseType.POSTGRESQL -> "SERIAL PRIMARY KEY"
            }

        """
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS synced BOOLEAN NOT NULL DEFAULT FALSE;
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS auto_sync BOOLEAN NOT NULL DEFAULT FALSE;
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS last_synced_at BIGINT NOT NULL DEFAULT 0;
        ALTER TABLE library_share ADD COLUMN IF NOT EXISTS paired_with INT NULL;

        CREATE TABLE IF NOT EXISTS library_share_delivered (
            id $idColumn,
            share_id INT NOT NULL REFERENCES library_share(id) ON DELETE CASCADE,
            kind VARCHAR(16) NOT NULL,
            ref_id INT NOT NULL
        );
        """.trimIndent()
    }
}

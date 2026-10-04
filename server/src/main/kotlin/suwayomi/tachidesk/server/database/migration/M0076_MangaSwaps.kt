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

/** Migrating a series to another source is recorded, so a shared library can move the other account's progress too. */
@Suppress("ClassName", "unused")
class M0076_MangaSwaps : SQLMigration() {
    override val sql by lazy {
        val idColumn =
            when (serverConfig.databaseType.value) {
                DatabaseType.H2 -> "INT AUTO_INCREMENT PRIMARY KEY"
                DatabaseType.POSTGRESQL -> "SERIAL PRIMARY KEY"
            }

        """
        CREATE TABLE IF NOT EXISTS manga_swap (
            id $idColumn,
            user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
            old_manga_id INT NOT NULL REFERENCES manga(id) ON DELETE CASCADE,
            new_manga_id INT NOT NULL REFERENCES manga(id) ON DELETE CASCADE,
            created_at BIGINT NOT NULL
        );
        """.trimIndent()
    }
}

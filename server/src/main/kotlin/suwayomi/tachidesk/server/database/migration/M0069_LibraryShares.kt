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

/** Offers to share a whole library or some categories with another account, applied once the recipient accepts. */
@Suppress("ClassName", "unused")
class M0069_LibraryShares : SQLMigration() {
    override val sql by lazy {
        val idColumn =
            when (serverConfig.databaseType.value) {
                DatabaseType.H2 -> "INT AUTO_INCREMENT PRIMARY KEY"
                DatabaseType.POSTGRESQL -> "SERIAL PRIMARY KEY"
            }

        """
        CREATE TABLE IF NOT EXISTS library_share (
            id $idColumn,
            sender_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
            recipient_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
            scope VARCHAR(16) NOT NULL,
            category_ids VARCHAR(2048) NOT NULL DEFAULT '',
            status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
            created_at BIGINT NOT NULL DEFAULT 0,
            responded_at BIGINT NOT NULL DEFAULT 0
        );
        """.trimIndent()
    }
}

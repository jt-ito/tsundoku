package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/** Sessions of signed-in devices, so that a refresh token can be revoked. Refresh tokens from before need a new login. */
@Suppress("ClassName", "unused")
class M0071_UserSessions : SQLMigration() {
    override val sql =
        """
        CREATE TABLE IF NOT EXISTS user_session (
            id VARCHAR(64) PRIMARY KEY,
            user_id INT NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
            created_at BIGINT NOT NULL DEFAULT 0,
            last_used_at BIGINT NOT NULL DEFAULT 0
        );
        """.trimIndent()
}

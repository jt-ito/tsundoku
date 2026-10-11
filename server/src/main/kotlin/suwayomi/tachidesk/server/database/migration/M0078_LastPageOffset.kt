package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * How far into its last read page a user got (0 to 1 of the page height), so that webtoon style reading can resume at
 * the exact spot and not only at the top of the page.
 */
@Suppress("ClassName", "unused")
class M0078_LastPageOffset : SQLMigration() {
    override val sql =
        """
        ALTER TABLE user_chapter ADD COLUMN last_page_offset DOUBLE PRECISION DEFAULT 0 NOT NULL;
        """.trimIndent()
}

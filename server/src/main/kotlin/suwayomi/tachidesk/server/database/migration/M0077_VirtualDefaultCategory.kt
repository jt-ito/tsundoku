package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * The Default category of every account now means "in none of my categories", like the built-in one of the first
 * account always did. Only rows of other accounts are touched (never a category the first account made itself).
 * The Default rows of the other accounts stay (they hold the order and the hidden flag) but no longer
 * hold manga: their links are removed, those manga are simply uncategorized and show up in the Default category.
 */
@Suppress("ClassName", "unused")
class M0077_VirtualDefaultCategory : SQLMigration() {
    override val sql =
        """
        DELETE FROM categorymanga
        WHERE category IN (SELECT id FROM category WHERE LOWER(name) = 'default' AND id <> 0 AND user_id <> 1);
        """.trimIndent()
}

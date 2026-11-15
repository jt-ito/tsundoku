package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * The Default category of an account is marked with a flag instead of being found by its name, so that it can be
 * renamed like any other category. The built-in one (id 0) is the Default category of the first account, every other
 * account has a row of its own. Only the flag is added: no category, link or name is changed or removed.
 *
 * If an account has several rows named "Default" (renaming the Default category used to leave a copy behind), the
 * oldest one is the Default category, the others stay normal categories with the name they have.
 */
@Suppress("ClassName", "unused")
class M0079_BuiltInCategory : SQLMigration() {
    override val sql =
        """
        ALTER TABLE category ADD COLUMN is_built_in BOOLEAN DEFAULT FALSE NOT NULL;

        UPDATE category SET is_built_in = TRUE WHERE id = 0;

        UPDATE category SET is_built_in = TRUE
        WHERE id <> 0
          AND user_id IS NOT NULL
          AND user_id <> 1
          AND LOWER(name) = 'default'
          AND id = (
              SELECT MIN(other.id) FROM category other
              WHERE other.user_id = category.user_id AND LOWER(other.name) = 'default'
          );
        """.trimIndent()
}

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
 * A PostgreSQL database that got its rows from a copy (the H2 -> PostgreSQL migration) could keep the counters of
 * its id columns behind the highest id, so the next insert failed with "duplicate key value violates unique
 * constraint" (for example when adding the first manga of an extension). This sets every counter to the highest
 * existing id. Harmless on a healthy database.
 */
@Suppress("ClassName", "unused")
class M0068_ResyncSequences : SQLMigration() {
    override val sql by lazy {
        when (serverConfig.databaseType.value) {
            DatabaseType.H2 -> "UPDATE category SET id = id WHERE 1 = 0;"
            DatabaseType.POSTGRESQL -> postgresQuery()
        }
    }

    // GREATEST: a table whose only row has id 0 (the default category) must not set the counter to 0, which is out of
    // bounds for a sequence and made a brand new database fail to start
    // the check for a sequence is inside the loop: in the SELECT the database may run it for tables without an id column
    private fun postgresQuery() =
        """
        DO $$
        DECLARE
            col record;
        BEGIN
            FOR col IN
                SELECT table_schema, table_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND column_name = 'id'
            LOOP
                IF pg_get_serial_sequence(format('%I.%I', col.table_schema, col.table_name), 'id') IS NOT NULL THEN
                    EXECUTE format(
                        'SELECT setval(pg_get_serial_sequence(%L, %L), GREATEST(COALESCE((SELECT MAX(id) FROM %I.%I), 1), 1))',
                        format('%I.%I', col.table_schema, col.table_name), 'id', col.table_schema, col.table_name
                    );
                END IF;
            END LOOP;
        END $$;
        """.trimIndent()
}

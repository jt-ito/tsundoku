package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ExtensionStoreTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.TrackSearchTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.sql.DriverManager
import java.util.Properties

@Serializable
data class PostgresConnectionParams(
    val host: String = "localhost",
    val port: Int = 5432,
    val databaseName: String = "suwayomi",
    val username: String = "postgres",
    val password: String = "",
    val useHikariPool: Boolean = true,
    // when true, the host/port/databaseName/username/password above are ignored and the server's own
    // bundled PostgreSQL instance is used/started instead - no external server required
    val useEmbedded: Boolean = false,
)

@Serializable
data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val databaseVersion: String? = null,
)

@Serializable
data class DatabaseStats(
    val currentType: String,
    val currentUrl: String,
    val isPostgreSQL: Boolean,
    val isEmbedded: Boolean,
    val mangaCount: Long,
    val chapterCount: Long,
    val categoryCount: Long,
    val historyCount: Long,
    val trackRecordCount: Long,
)

@Serializable
data class MigrationResult(
    val success: Boolean,
    val message: String,
    val details: Map<String, Long> = emptyMap(),
)

object DatabaseMigrationService {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var isMigrating: Boolean = false
        private set

    // parents before children, so inserting in this order never violates a foreign key - used for both directions
    private val TABLES_TO_MIGRATE: List<Table> =
        listOf(
            suwayomi.tachidesk.server.user.model.UserTable,
            CategoryTable,
            ExtensionStoreTable,
            ExtensionTable,
            SourceTable,
            GlobalMetaTable,
            CategoryMetaTable,
            SourceMetaTable,
            MangaTable,
            CategoryMangaTable,
            MangaMetaTable,
            ChapterTable,
            ChapterMetaTable,
            PageTable,
            TrackSearchTable,
            TrackRecordTable,
            suwayomi.tachidesk.manga.model.table.UserMangaTable,
            suwayomi.tachidesk.manga.model.table.UserChapterTable,
            suwayomi.tachidesk.manga.model.table.LibraryShareTable,
            suwayomi.tachidesk.manga.model.table.LibraryShareDeliveredTable,
            suwayomi.tachidesk.manga.model.table.LibraryShareCategoryTable,
        )

    // the same tables, by their actual SQL name - used to resync each engine's own auto-increment/sequence
    // bookkeeping after a raw data copy, so rows written by the app afterwards don't collide with copied ids.
    // (A hand written list of snake_case names used to be here, most of which are not the real table names, so the
    // counters of those tables were never resynced.)
    private val SEQUENCE_TABLE_NAMES: List<String> = TABLES_TO_MIGRATE.filter { it !== SourceTable }.map { it.tableName }

    fun getDatabaseStats(): DatabaseStats {
        val currentType = serverConfig.databaseType.value.name
        val isEmbedded = serverConfig.databaseType.value == DatabaseType.POSTGRESQL && serverConfig.useEmbeddedPostgres.value
        val currentUrl =
            when (serverConfig.databaseType.value) {
                DatabaseType.POSTGRESQL -> {
                    if (isEmbedded) {
                        "jdbc:postgresql://localhost:${EmbeddedPostgresManager.ensureStarted().port}/${EmbeddedPostgresManager.DATABASE_NAME} (built in)"
                    } else {
                        "jdbc:${serverConfig.databaseUrl.value}"
                    }
                }

                DatabaseType.H2 -> {
                    "jdbc:h2:${Injekt.get<ApplicationDirs>().dataRoot}/database"
                }
            }
        val isPostgres = serverConfig.databaseType.value == DatabaseType.POSTGRESQL

        var mangaCount = 0L
        var chapterCount = 0L
        var categoryCount = 0L
        var historyCount = 0L
        var trackCount = 0L

        try {
            dbTransaction {
                mangaCount = MangaTable.selectAll().count()
                chapterCount = ChapterTable.selectAll().count()
                categoryCount = CategoryTable.selectAll().count()
                historyCount =
                    ChapterTable
                        .selectAll()
                        .where { (ChapterTable.isRead eq true) or (ChapterTable.lastPageRead greater 0) }
                        .count()
                trackCount = TrackRecordTable.selectAll().count()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not fetch database stats" }
        }

        return DatabaseStats(
            currentType = currentType,
            currentUrl = currentUrl,
            isPostgreSQL = isPostgres,
            isEmbedded = isEmbedded,
            mangaCount = mangaCount,
            chapterCount = chapterCount,
            categoryCount = categoryCount,
            historyCount = historyCount,
            trackRecordCount = trackCount,
        )
    }

    /** Resolves "use the built-in instance" into real, connectable host/port/credentials, starting it if needed. */
    private fun resolveParams(params: PostgresConnectionParams): PostgresConnectionParams =
        if (params.useEmbedded) EmbeddedPostgresManager.ensureStarted() else params

    fun testConnection(rawParams: PostgresConnectionParams): ConnectionTestResult {
        val params = resolveParams(rawParams)
        val jdbcUrl = "jdbc:postgresql://${params.host}:${params.port}/${params.databaseName}"
        val props =
            Properties().apply {
                setProperty("user", params.username)
                setProperty("password", params.password)
                setProperty("connectTimeout", "5")
                setProperty("socketTimeout", "5")
            }

        return try {
            Class.forName("org.postgresql.Driver")
            DriverManager.getConnection(jdbcUrl, props).use { conn ->
                val meta = conn.metaData
                val version = "${meta.databaseProductName} ${meta.databaseProductVersion}"
                ConnectionTestResult(
                    success = true,
                    message = "Successfully connected to PostgreSQL at ${params.host}:${params.port}/${params.databaseName}",
                    databaseVersion = version,
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "Unknown error"
            // If the database does not exist, try to inform user or check if connection to postgres maintenance db succeeds
            if (msg.contains("database \"${params.databaseName}\" does not exist", ignoreCase = true)) {
                val maintenanceUrl = "jdbc:postgresql://${params.host}:${params.port}/postgres"
                try {
                    DriverManager.getConnection(maintenanceUrl, props).use { maintenanceConn ->
                        val meta = maintenanceConn.metaData
                        val version = "${meta.databaseProductName} ${meta.databaseProductVersion}"
                        return ConnectionTestResult(
                            success = true,
                            message =
                                "PostgreSQL server reached ($version), but database '${params.databaseName}' does not exist yet. " +
                                    "It will be created automatically when you start the migration!",
                            databaseVersion = version,
                        )
                    }
                } catch (mEx: Exception) {
                    // fall through to original error
                }
            }

            logger.warn(e) { "PostgreSQL connection test failed for $jdbcUrl" }
            ConnectionTestResult(
                success = false,
                message = "Connection failed: $msg",
            )
        }
    }

    private fun ensureDatabaseExists(params: PostgresConnectionParams) {
        val testResult = testConnection(params)
        if (testResult.success && testResult.databaseVersion != null && !testResult.message.contains("does not exist")) {
            return
        }

        // Try creating the database via the 'postgres' default database connection
        val maintenanceUrl = "jdbc:postgresql://${params.host}:${params.port}/postgres"
        val props =
            Properties().apply {
                setProperty("user", params.username)
                setProperty("password", params.password)
                setProperty("connectTimeout", "10")
            }

        try {
            DriverManager.getConnection(maintenanceUrl, props).use { conn ->
                val stmt = conn.createStatement()
                // Check if database exists
                val rs =
                    stmt.executeQuery(
                        "SELECT 1 FROM pg_database WHERE datname = '${params.databaseName.replace("'", "''")}'",
                    )
                if (!rs.next()) {
                    logger.info { "Creating PostgreSQL database '${params.databaseName}'..." }
                    stmt.executeUpdate("CREATE DATABASE \"${params.databaseName.replace("\"", "\"\"")}\"")
                    logger.info { "Database '${params.databaseName}' created successfully." }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not automatically create database '${params.databaseName}': ${e.message}" }
        }
    }

    @Synchronized
    fun migrateH2ToPostgres(rawParams: PostgresConnectionParams): MigrationResult {
        if (isMigrating) {
            return MigrationResult(
                success = false,
                message = "A database migration is already in progress.",
            )
        }

        // Snapshot before anything is mutated, so a failure partway through can restore the original,
        // still-intact backend instead of leaving the app pointed at a half-written target (see the catch below).
        val originalDatabaseType = serverConfig.databaseType.value
        val originalDatabaseUrl = serverConfig.databaseUrl.value
        val originalDatabaseUsername = serverConfig.databaseUsername.value
        val originalDatabasePassword = serverConfig.databasePassword.value
        val originalUseHikari = serverConfig.useHikariConnectionPool.value
        val originalUseEmbeddedPostgres = serverConfig.useEmbeddedPostgres.value

        isMigrating = true
        try {
            val params = resolveParams(rawParams)
            val appDirs = Injekt.get<ApplicationDirs>()
            val h2File = File("${appDirs.dataRoot}/database.mv.db")

            if (!h2File.exists() && serverConfig.databaseType.value == DatabaseType.H2) {
                return MigrationResult(
                    success = false,
                    message = "No H2 database file found at ${h2File.absolutePath}",
                )
            }

            // Step 1: Create backup of current H2 database file. Done through H2's own live BACKUP command
            // (not a raw file copy) since the app still has the file open here - a plain file copy can fail
            // outright on Windows (NTFS byte-range locks block reading a file another process has open) and
            // risks a torn/inconsistent snapshot even where it doesn't.
            if (h2File.exists()) {
                val backupFile = File(h2File.parentFile, "${h2File.nameWithoutExtension}.backup-${System.currentTimeMillis()}.zip")
                // a plain, independent JDBC connection to the same live file - H2 allows concurrent connections
                // to one database within the same JVM, so this doesn't disturb the app's own connection/pool
                Class.forName("org.h2.Driver")
                DriverManager.getConnection("jdbc:h2:${appDirs.dataRoot}/database").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("BACKUP TO '${backupFile.absolutePath.replace("'", "''")}'")
                    }
                }
                logger.info { "Created H2 backup at: ${backupFile.absolutePath}" }
            }

            // Step 2: Ensure Postgres database exists
            ensureDatabaseExists(params)

            // Step 3: Test connection to PostgreSQL before altering configuration
            val test = testConnection(params)
            if (!test.success) {
                return MigrationResult(
                    success = false,
                    message = "Cannot connect to PostgreSQL: ${test.message}",
                )
            }

            // Step 4: Connect to H2 database for reading
            val h2JdbcUrl = "jdbc:h2:${appDirs.dataRoot}/database;ACCESS_MODE_DATA=r"
            val h2Db = Database.connect(h2JdbcUrl, "org.h2.Driver", databaseConfig = migrationDbConfig())

            // Step 5: Update server configuration to PostgreSQL
            // This triggers DBManager to connect to PostgreSQL, create the 'suwayomi' schema, and run all migrations!
            serverConfig.databaseUrl.value = "postgresql://${params.host}:${params.port}/${params.databaseName}"
            serverConfig.databaseUsername.value = params.username
            // the built-in instance's password is managed (and kept) by EmbeddedPostgresManager, not in the settings file
            serverConfig.databasePassword.value = if (rawParams.useEmbedded) "" else params.password
            serverConfig.useHikariConnectionPool.value = params.useHikariPool
            serverConfig.useEmbeddedPostgres.value = rawParams.useEmbedded
            serverConfig.databaseType.value = DatabaseType.POSTGRESQL

            // Make sure the schema/migrations exist on the target before copying data into it. This also runs
            // via the app's own reactive settings-change listener for its shared connection pool, but databaseUp()
            // is safe to call redundantly (synchronized against concurrent execution) - and a dedicated connection
            // (below) for the copy itself avoids racing that listener for when DBManager's own pool is ready.
            databaseUp()

            val targetDb =
                Database.connect(
                    "jdbc:postgresql://${params.host}:${params.port}/${params.databaseName}",
                    "org.postgresql.Driver",
                    user = params.username,
                    password = params.password,
                    databaseConfig = migrationDbConfig(Schema("suwayomi")),
                )

            // Steps 6-8: clear whatever databaseUp() just seeded on this fresh target (e.g. the default admin
            // user from M0066), then transfer every table and resync PostgreSQL's sequences - a migration
            // replaces the target's contents, it doesn't merge with them.
            val migrationStats = copyAllTablesIntoPostgres(h2Db, targetDb)

            val backendDescription = if (rawParams.useEmbedded) "the built-in PostgreSQL" else "PostgreSQL"
            logger.info { "Database migration from H2 to PostgreSQL completed successfully! Stats: $migrationStats" }
            return MigrationResult(
                success = true,
                message = "Successfully migrated all data from H2 to PostgreSQL and activated $backendDescription backend.",
                details = migrationStats,
            )
        } catch (e: Exception) {
            logger.error(e) { "Migration from H2 to PostgreSQL failed" }
            // A failed migration must never leave the app pointed at a half-written target while the real,
            // untouched data sits under the original backend - restore exactly what was there before Step 5.
            try {
                serverConfig.databaseType.value = originalDatabaseType
                serverConfig.databaseUrl.value = originalDatabaseUrl
                serverConfig.databaseUsername.value = originalDatabaseUsername
                serverConfig.databasePassword.value = originalDatabasePassword
                serverConfig.useHikariConnectionPool.value = originalUseHikari
                serverConfig.useEmbeddedPostgres.value = originalUseEmbeddedPostgres
                databaseUp()
            } catch (rollbackEx: Exception) {
                logger.error(rollbackEx) { "Failed to roll back to the original backend after a failed H2->PostgreSQL migration" }
            }
            return MigrationResult(
                success = false,
                message = "Migration failed: ${e.message ?: e::class.simpleName}",
            )
        } finally {
            isMigrating = false
        }
    }

    /** The reverse of [migrateH2ToPostgres]: copies everything out of the currently-active PostgreSQL
     * (built-in or external, doesn't matter - it's already connected either way) into a fresh H2 file,
     * then activates H2. The PostgreSQL data itself is left untouched, only ever read from. */
    @Synchronized
    fun migratePostgresToH2(): MigrationResult {
        if (isMigrating) {
            return MigrationResult(
                success = false,
                message = "A database migration is already in progress.",
            )
        }
        if (serverConfig.databaseType.value != DatabaseType.POSTGRESQL) {
            return MigrationResult(
                success = false,
                message = "The server is not currently using PostgreSQL.",
            )
        }

        // Snapshot before anything is mutated, so a failure partway through can restore the original,
        // still-intact PostgreSQL backend instead of leaving the app pointed at a half-written H2 file.
        val originalDatabaseType = serverConfig.databaseType.value

        isMigrating = true
        try {
            val appDirs = Injekt.get<ApplicationDirs>()
            val h2File = File("${appDirs.dataRoot}/database.mv.db")

            // Step 1: an independent connection to the CURRENT PostgreSQL, made before flipping any config -
            // switching to H2 below tears down the connection pool this server is currently using, so the
            // source connection must not depend on it
            val (sourceUrl, sourceUser, sourcePassword) =
                if (serverConfig.useEmbeddedPostgres.value) {
                    val params = EmbeddedPostgresManager.ensureStarted()
                    Triple(
                        "jdbc:postgresql://${params.host}:${params.port}/${params.databaseName}",
                        params.username,
                        params.password,
                    )
                } else {
                    Triple(
                        "jdbc:${serverConfig.databaseUrl.value}",
                        serverConfig.databaseUsername.value,
                        serverConfig.databasePassword.value,
                    )
                }
            val sourceDb =
                Database.connect(
                    sourceUrl,
                    "org.postgresql.Driver",
                    user = sourceUser,
                    password = sourcePassword,
                    databaseConfig = migrationDbConfig(Schema("suwayomi")),
                )

            // Step 2: keep any existing H2 file out of the way instead of silently overwriting/merging into it
            if (h2File.exists()) {
                val backupFile = File(h2File.parentFile, "${h2File.name}.backup-${System.currentTimeMillis()}")
                h2File.copyTo(backupFile, overwrite = true)
                h2File.delete()
                File("${h2File.absolutePath}.trace.db").delete()
                logger.info { "Moved existing H2 file out of the way: ${backupFile.absolutePath}" }
            }

            // Step 3: switch to H2 and make sure its schema/migrations exist before copying data into it (see the
            // matching comment in migrateH2ToPostgres for why a dedicated connection is used here, not DBManager.db)
            serverConfig.databaseType.value = DatabaseType.H2
            databaseUp()

            val targetDb =
                Database.connect("jdbc:h2:${appDirs.dataRoot}/database", "org.h2.Driver", databaseConfig = migrationDbConfig())

            // Remove the schema-seeded rows (e.g. the default admin user from M0066) on this brand new H2
            // file before copying the real rows in - see clearTargetTables() for why.
            transaction(targetDb) {
                clearTargetTables()
            }

            val migrationStats = mutableMapOf<String, Long>()

            // Step 4: transfer data for all tables in topological order (H2 doesn't need the
            // disable/re-enable-constraints dance PostgreSQL does; this order already satisfies every foreign key)
            for (table in TABLES_TO_MIGRATE) {
                val count = copyTableData(sourceDb, targetDb, table)
                migrationStats[table.tableName] = count
                logger.info { "Migrated table ${table.tableName}: $count rows transferred." }
            }

            // Step 5: resync H2's auto-increment counters so new rows don't collide with the copied ids
            transaction(targetDb) {
                for (tbl in SEQUENCE_TABLE_NAMES) {
                    try {
                        val maxId =
                            exec("SELECT COALESCE(MAX(\"ID\"), 0) FROM \"${tbl.uppercase()}\"") { rs ->
                                if (rs.next()) rs.getLong(1) else 0L
                            } ?: 0L
                        exec("ALTER TABLE \"${tbl.uppercase()}\" ALTER COLUMN \"ID\" RESTART WITH ${maxId + 1}")
                    } catch (e: Exception) {
                        logger.debug(e) { "Sequence restart for $tbl skipped or failed: ${e.message}" }
                    }
                }
            }

            logger.info { "Database migration from PostgreSQL to H2 completed successfully! Stats: $migrationStats" }
            return MigrationResult(
                success = true,
                message = "Successfully migrated all data from PostgreSQL to H2 and activated the H2 backend.",
                details = migrationStats,
            )
        } catch (e: Exception) {
            logger.error(e) { "Migration from PostgreSQL to H2 failed" }
            // A failed migration must never leave the app pointed at a half-written H2 file while the real,
            // untouched data sits under PostgreSQL - restore exactly what was there before Step 3.
            try {
                serverConfig.databaseType.value = originalDatabaseType
                databaseUp()
            } catch (rollbackEx: Exception) {
                logger.error(rollbackEx) { "Failed to roll back to the original backend after a failed PostgreSQL->H2 migration" }
            }
            return MigrationResult(
                success = false,
                message = "Migration failed: ${e.message ?: e::class.simpleName}",
            )
        } finally {
            isMigrating = false
        }
    }

    // Matches the preserveKeywordCasing=false already set on the app's main pool in DBManager.setupDatabase():
    // without it, Exposed quotes reserved-word columns (e.g. "name", "role") preserving their lowercase Kotlin
    // spelling, which doesn't match the uppercase H2 folds unquoted DDL into - "Column not found" on every
    // such column. These ad-hoc migration connections don't inherit the main pool's config, so they need it too.
    @OptIn(ExperimentalKeywordApi::class)
    internal fun migrationDbConfig(schema: Schema? = null) =
        DatabaseConfig {
            preserveKeywordCasing = false
            if (schema != null) defaultSchema = schema
        }

    // Wipes every migrated table on the target, children first, so a fresh schema's seed data (e.g. the
    // default admin user M0066 inserts via databaseUp()) doesn't collide with the real rows being copied in.
    // Must be called from within a transaction on the target database.
    private fun clearTargetTables() {
        for (table in TABLES_TO_MIGRATE.asReversed()) {
            table.deleteAll()
        }
    }

    /**
     * Copies every table from [sourceDb] into a PostgreSQL [targetDb], replacing whatever the target's own
     * schema migrations seeded (e.g. the default admin user) - shared by the H2->PostgreSQL migration and by
     * [EmbeddedPostgresManager]'s automatic major-version upgrade (source there is the old-major PostgreSQL
     * cluster, read via a temporarily-started old server binary, since Postgres refuses to start against a
     * data directory from a different major version at all - there's never a risk of touching it via the
     * wrong binary).
     */
    internal fun copyAllTablesIntoPostgres(
        sourceDb: Database,
        targetDb: Database,
    ): Map<String, Long> {
        transaction(targetDb) {
            exec("SET session_replication_role = 'replica';")
            clearTargetTables()
        }

        val migrationStats = mutableMapOf<String, Long>()
        for (table in TABLES_TO_MIGRATE) {
            val count = copyTableData(sourceDb, targetDb, table)
            migrationStats[table.tableName] = count
            logger.info { "Migrated table ${table.tableName}: $count rows transferred." }
        }

        transaction(targetDb) {
            exec("SET session_replication_role = 'origin';")
            for (tbl in SEQUENCE_TABLE_NAMES) {
                try {
                    exec(
                        "SELECT setval(pg_get_serial_sequence('suwayomi.$tbl', 'id'), GREATEST(COALESCE((SELECT MAX(id) FROM suwayomi.$tbl), 1), 1));",
                    )
                } catch (e: Exception) {
                    logger.debug(e) { "Sequence set for suwayomi.$tbl skipped or failed: ${e.message}" }
                }
            }
        }

        return migrationStats
    }

    private fun <T : Table> copyTableData(
        sourceDb: Database,
        targetDb: Database,
        table: T,
        batchSize: Int = 500,
    ): Long {
        val totalRows = transaction(sourceDb) { table.selectAll().count() }
        if (totalRows == 0L) return 0L

        val columns = table.columns
        var offset = 0L

        while (offset < totalRows) {
            val rows =
                transaction(sourceDb) {
                    table
                        .selectAll()
                        .limit(batchSize)
                        .offset(offset)
                        .toList()
                }
            if (rows.isEmpty()) break

            transaction(targetDb) {
                table.batchInsert(rows, shouldReturnGeneratedValues = false) { row ->
                    for (col in columns) {
                        val value = row[col]
                        @Suppress("UNCHECKED_CAST")
                        this[col as Column<Any?>] = value
                    }
                }
            }
            offset += rows.size
        }

        // Defense-in-depth beyond batchInsert()'s own throw-on-failure guarantee: re-count the target so a
        // silent short copy (e.g. a trigger or constraint quietly dropping rows) is caught here, before any
        // caller acts on a copy that looks complete but isn't.
        val copiedRows = transaction(targetDb) { table.selectAll().count() }
        if (copiedRows != totalRows) {
            throw IllegalStateException(
                "Row count mismatch copying \"${table.tableName}\": source had $totalRows, target has $copiedRows",
            )
        }

        return totalRows
    }
}

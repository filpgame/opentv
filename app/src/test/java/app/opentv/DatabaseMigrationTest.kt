/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The upgrade from the previous schema, built from its exported JSON. Room checks the migrated tables
 * against the current entities when it opens the file, so a migration that leaves a column or a
 * default out fails here instead of crashing a user's app on launch. (The destructive fallback does
 * not catch that: it only covers a version with no migration at all.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** Creates an empty database exactly as the exported schema [version] describes it. */
    private fun createFromSchema(version: Int): SQLiteDatabase {
        // Unit tests run with the module directory as the working directory.
        val schema = Json.parseToJsonElement(
            File("schemas/app.opentv.data.db.OpenTvDatabase/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        for (entity in schema.getValue("entities").jsonArray) {
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    @Test
    fun `12 to 13 keeps every row and adds blank stamps`() = runTest {
        createFromSchema(12).use { old ->
            old.execSQL("INSERT INTO sources (id, name, kind, url, userAgent, liveFormat, enabled, lastCatalogSyncMillis) VALUES (1, 'lista', 'M3U', 'http://x.invalid', 'ua', 'HLS', 1, 0)")
            old.execSQL("INSERT INTO movies (id, sourceId, streamId, name, categoryId, streamUrl, favourite, addedMillis) VALUES (7, 1, 'm', 'Pinóquio [L]', 'Filmes | Legendados', 'http://x.invalid/7', 1, 0)")
            old.execSQL("INSERT INTO series (id, sourceId, seriesId, name, favourite, addedMillis) VALUES (8, 1, 's', 'The Boys [L]', 0, 0)")
            old.execSQL("INSERT INTO channels (id, sourceId, streamId, name, displayName, groupKey, qualityRank, qualityLabel, tvArchive, tvArchiveDays, streamUrl, favourite, hidden, sortIndex, lastSeenMillis) VALUES (9, 1, 'c', 'TNT SÉRIES', 'TNT SÉRIES', 'tntseries', 0, '', 0, 0, 'http://x.invalid/9', 0, 0, 0, 0)")
        }

        val db = Room.databaseBuilder(context, OpenTvDatabase::class.java, name)
            .addMigrations(*OpenTvDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val movie = db.movies().byId(7)!!
            assertThat(movie.name).isEqualTo("Pinóquio [L]")
            assertThat(movie.favourite).isTrue()
            assertThat(movie.searchKey).isEmpty()
            assertThat(movie.variantKey).isEmpty()
            assertThat(movie.adult).isFalse()
            assertThat(db.series().byId(8)!!.subtitled).isFalse()
            assertThat(db.channels().byId(9)!!.searchKey).isEmpty()
            assertThat(db.sources().byId(1)!!.name).isEqualTo("lista")
        } finally {
            db.close()
        }
    }
}

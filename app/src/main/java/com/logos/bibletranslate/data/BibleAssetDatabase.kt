package com.logos.bibletranslate.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Bundled Bible texts and the (optional) precomputed word-translation table
 * are pre-built, read-only SQLite files shipped as assets. SQLite can't open
 * a database directly from inside the APK, so each one is copied to internal
 * storage once on first access.
 */
object BibleAssetDatabase {

    private val openDatabases = mutableMapOf<String, SQLiteDatabase>()
    private val missingAssets = mutableSetOf<String>()

    fun open(context: Context, language: BibleLanguage): SQLiteDatabase =
        openByFileName(context, language.assetFileName)
            ?: error("Required Bible asset missing: ${language.assetFileName}")

    /**
     * Returns null if the database isn't available yet — either a bundled asset that doesn't
     * exist (e.g. word_translations.db before Phase 2 runs), or a downloadable-only translation
     * the user hasn't downloaded (see TranslationDownloadManager).
     */
    fun openByFileName(context: Context, fileName: String): SQLiteDatabase? {
        openDatabases[fileName]?.let { return it }

        val destFile = context.getDatabasePath(fileName)
        // A file already on disk always wins over the missing-asset cache — this is exactly
        // what happens right after TranslationDownloadManager downloads a translation into this
        // same path: it wasn't bundled as an asset (so an earlier lookup may have cached it as
        // missing), but it's real now, so open it directly instead of trying (and failing) to
        // copy a same-named asset that was never packaged.
        if (!destFile.exists()) {
            if (fileName in missingAssets) return null
            destFile.parentFile?.mkdirs()
            try {
                copyAsset(context, "bibles/$fileName", destFile)
            } catch (e: FileNotFoundException) {
                missingAssets += fileName
                return null
            }
        }

        val db = SQLiteDatabase.openDatabase(
            destFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        openDatabases[fileName] = db
        return db
    }

    /** Called after a fresh download lands a file at the same path this class reads from —
     *  clears the missing-asset memo so a later [openByFileName] call re-checks the disk. */
    fun invalidate(fileName: String) {
        missingAssets -= fileName
        openDatabases.remove(fileName)?.close()
    }

    private fun copyAsset(context: Context, assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}

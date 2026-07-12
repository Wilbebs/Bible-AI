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

    /** Returns null if the asset doesn't exist (e.g. word_translations.db before Phase 2 runs). */
    fun openByFileName(context: Context, fileName: String): SQLiteDatabase? {
        if (fileName in missingAssets) return null
        openDatabases[fileName]?.let { return it }

        val destFile = context.getDatabasePath(fileName)
        if (!destFile.exists()) {
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

    private fun copyAsset(context: Context, assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}

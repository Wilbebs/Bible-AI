package com.logos.bibletranslate.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches downloadable-only translation databases (everything not in
 * [BibleLanguage.DEFAULT_DOWNLOADED]) from this repo's raw GitHub content on demand, and writes
 * them to the exact path [BibleAssetDatabase] already reads bundled assets from
 * (`context.getDatabasePath(fileName)`) — so once a download lands, the existing DB-open code
 * picks it up with no further plumbing.
 */
class TranslationDownloadManager(private val context: Context) {

    /** True for a bundled-by-default language, or a downloadable one already fetched to disk. */
    fun isAvailable(language: BibleLanguage): Boolean =
        language.isBundledByDefault || context.getDatabasePath(language.assetFileName).exists()

    suspend fun download(language: BibleLanguage): Result<Unit> = withContext(Dispatchers.IO) {
        if (language.isBundledByDefault) return@withContext Result.success(Unit)
        try {
            val url = URL(RAW_BASE_URL + language.assetFileName)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Download failed: HTTP ${connection.responseCode}"))
            }

            val destFile = context.getDatabasePath(language.assetFileName)
            destFile.parentFile?.mkdirs()
            // Download to a temp file first so a connection drop mid-transfer can never leave a
            // half-written .db sitting at the real path where BibleAssetDatabase would open it.
            val tmpFile = File(destFile.parentFile, "${language.assetFileName}.part")
            connection.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.delete()
                return@withContext Result.failure(Exception("Couldn't save downloaded file"))
            }
            BibleAssetDatabase.invalidate(language.assetFileName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Removes a previously-downloaded translation to free space. No-op for bundled-by-default languages. */
    fun delete(language: BibleLanguage) {
        if (language.isBundledByDefault) return
        BibleAssetDatabase.invalidate(language.assetFileName)
        context.getDatabasePath(language.assetFileName).delete()
    }

    companion object {
        private const val RAW_BASE_URL = "https://raw.githubusercontent.com/Wilbebs/Bible-AI/main/bible_downloads/"
    }
}

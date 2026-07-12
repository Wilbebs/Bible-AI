package com.logos.bibletranslate.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VerseTranslateDialog(data: VerseDialogData, onDismiss: () -> Unit) {
    val context = LocalContext.current

    val shareText = buildString {
        append("${data.bookName} ${data.chapter}:${data.verse}\n")
        append("${data.sourceLanguage.displayName}: ${data.originalText}\n")
        data.translations.forEach { entry ->
            append("${entry.language.displayName}: ${entry.text ?: "(unavailable)"}\n")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { shareVerse(context, shareText) }) { Text("Share") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { copyVerse(context, shareText) }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        title = { Text("${data.bookName} ${data.chapter}:${data.verse}") },
        text = {
            Column {
                Text(
                    text = "${data.sourceLanguage.displayName}: ${data.originalText}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                data.translations.forEach { entry ->
                    Text(
                        text = "${entry.language.displayName}: ${entry.text ?: "(not available)"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

private fun copyVerse(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText("verse", text))
}

private fun shareVerse(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

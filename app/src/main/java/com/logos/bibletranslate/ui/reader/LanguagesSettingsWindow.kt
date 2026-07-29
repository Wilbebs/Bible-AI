package com.logos.bibletranslate.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logos.bibletranslate.data.BibleLanguage

/**
 * Full-screen "Languages" settings window (Settings gear → Languages). Occupies the whole
 * screen minus a 25dp border on every edge, with an X in the top-right corner to close.
 * Lists every [BibleLanguage] — bundled-by-default ones show as always-available, everything
 * else shows a download affordance (spinner while in flight, checkmark + delete once fetched).
 */
@Composable
fun LanguagesSettingsWindow(
    downloadedLanguages: Set<BibleLanguage>,
    downloadingLanguages: Set<BibleLanguage>,
    onClose: () -> Unit,
    onDownload: (BibleLanguage) -> Unit,
    onDelete: (BibleLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(25.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Languages",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close settings")
                    }
                }
                Divider()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(BibleLanguage.entries) { language ->
                        LanguageRow(
                            language = language,
                            isDownloaded = language.isBundledByDefault || language in downloadedLanguages,
                            isDownloading = language in downloadingLanguages,
                            onDownload = { onDownload(language) },
                            onDelete = { onDelete(language) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: BibleLanguage,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(language.displayNameWithTranslation, style = MaterialTheme.typography.bodyLarge)
            if (language.isBundledByDefault) {
                Text(
                    "Included",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            language.isBundledByDefault -> {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Included with the app",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            isDownloading -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            isDownloaded -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${language.displayNameWithTranslation}")
                    }
                }
            }
            else -> {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download ${language.displayNameWithTranslation}")
                }
            }
        }
    }
}

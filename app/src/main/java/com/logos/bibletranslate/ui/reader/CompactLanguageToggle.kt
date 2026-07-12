package com.logos.bibletranslate.ui.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.logos.bibletranslate.data.BibleLanguage

/**
 * Abbreviated EN/ES/PT segmented control (chat-feature-addendum §5) — the
 * same component is reused for both the global reader top-bar toggle and
 * the in-bubble language toggle so the interaction pattern is identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLanguageToggle(
    options: List<BibleLanguage>,
    selected: BibleLanguage,
    onSelected: (BibleLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, language ->
            SegmentedButton(
                selected = language == selected,
                onClick = { onSelected(language) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(language.code.uppercase())
            }
        }
    }
}

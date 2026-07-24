package com.example.gravimusicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.gravimusicplayer.DefaultStartPlayOrder
import com.example.gravimusicplayer.GraviPickerSettings
import com.example.gravimusicplayer.ui.theme.GraviMusicPlayerTheme

@Composable
fun SettingsScreen(
    rootUriString: String?,
    defaultStartPlayOrder: DefaultStartPlayOrder,
    genreSeparator: String,
    showBrowserThumbnails: Boolean,
    queueSearchResults: Boolean,
    skipSilenceEnabled: Boolean,
    graviPickerSettings: GraviPickerSettings,
    onChooseFolder: () -> Unit,
    onDefaultStartPlayOrderChanged: (DefaultStartPlayOrder) -> Unit,
    onGenreSeparatorChanged: (String) -> Unit,
    onApplyGenreSeparator: (String) -> Unit,
    onShowBrowserThumbnailsChanged: (Boolean) -> Unit,
    onQueueSearchResultsChanged: (Boolean) -> Unit,
    onSkipSilenceEnabledChanged: (Boolean) -> Unit,
    onGraviPickerSettingsChanged: (GraviPickerSettings) -> Unit,
    onResetSettings: () -> Unit,
    onClearCaches: () -> Unit,
    onClearPerformanceData: () -> Unit,
    onExportPerformanceData: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Button(onClick = onChooseFolder) {
            Text(if (rootUriString == null) "Choose music folder" else "Change music folder")
        }
        SwitchSettingRow(
            label = "Show browser thumbnails",
            checked = showBrowserThumbnails,
            onCheckedChanged = onShowBrowserThumbnailsChanged,
        )
        DefaultStartPlayOrderSetting(
            selectedOrder = defaultStartPlayOrder,
            onOrderSelected = onDefaultStartPlayOrderChanged,
        )
        SwitchSettingRow(
            label = "Queue search results",
            checked = queueSearchResults,
            infoText = "When enabled, opening a song in folder search queues all song results. When disabled, it queues songs in the selected song's folder and subfolders.",
            onCheckedChanged = onQueueSearchResultsChanged,
        )
        SwitchSettingRow(
            label = "Shorten trailing and leading silences",
            checked = skipSilenceEnabled,
            infoText = "Reduces more than 1.5 seconds of near-silent/static padding to 1.5 seconds. Quiet musical intros/outros are kept.",
            onCheckedChanged = onSkipSilenceEnabledChanged,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = genreSeparator,
                onValueChange = onGenreSeparatorChanged,
                label = {
                    SettingLabel(
                        label = "Genre separator",
                        infoText = "The Genre ID3 tag in a KBOTs Mixes MP3 stores its Merge Playlists. It's stored as string with this separator dividing the values.",
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onApplyGenreSeparator(genreSeparator) }) {
                Text("Apply")
            }
        }
        Text(
            "Gravi shuffle",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        SwitchSettingRow(
            label = "Parent odds",
            checked = graviPickerSettings.parentOdds,
            infoText = "Balances folder branches when picking a folder. This makes the categories (General, Pop, Rock, EDM) equally likely, instead of just the individual playlists.",
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(parentOdds = it).sanitized())
            },
        )
        SwitchSettingRow(
            label = "Child odds",
            checked = graviPickerSettings.childOdds,
            infoText = "Balances subfolder branches inside the picked folder before picking a file.",
            onCheckedChanged = {
                onGraviPickerSettingsChanged(graviPickerSettings.copy(childOdds = it).sanitized())
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IntegerSettingField(
                label = "Queue entries",
                value = graviPickerSettings.queueEntries,
                onValueChanged = {
                    onGraviPickerSettingsChanged(
                        graviPickerSettings.copy(queueEntries = it).sanitized()
                    )
                },
                modifier = Modifier.weight(1f),
            )
            IntegerSettingField(
                label = "Depth",
                value = graviPickerSettings.depth,
                onValueChanged = {
                    onGraviPickerSettingsChanged(graviPickerSettings.copy(depth = it).sanitized())
                },
                modifier = Modifier.weight(1f),
                infoText = "Controls which folder depth becomes a candidate. Setting this to 1 has the effect of Parent odds but makes playlists with more songs more likely than others in the same category.",
            )
        }
        IntegerSettingField(
            label = "Playlist songs before less odds",
            value = graviPickerSettings.evenOddsMinFileCount,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(evenOddsMinFileCount = it).sanitized()
                )
            },
            modifier = Modifier.fillMaxWidth(),
            infoText = "Folders with this many files or fewer become less likely. Use -1 to disable this.",
        )
        DecimalSettingField(
            label = "Playlist less odds strength",
            value = graviPickerSettings.lessLikelyDivisor,
            onValueChanged = {
                onGraviPickerSettingsChanged(
                    graviPickerSettings.copy(lessLikelyDivisor = it).sanitized()
                )
            },
            modifier = Modifier.fillMaxWidth(),
            infoText = "How strongly low-file-count folders are reduced. Higher means less likely.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onClearCaches,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear cache")
            }
            Button(
                onClick = onResetSettings,
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset settings")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onClearPerformanceData,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear debug data")
            }
            Button(
                onClick = onExportPerformanceData,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save debug data")
            }
        }
    }
}

@Composable
private fun DefaultStartPlayOrderSetting(
    selectedOrder: DefaultStartPlayOrder,
    onOrderSelected: (DefaultStartPlayOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(
            label = "Default file and genre start order",
            infoText = "Controls whether opening a file from Folders or a genre from Genres starts ordered or shuffled by default.",
        )
        Row {
            TextButton(onClick = { expanded = true }) {
                Text(selectedOrder.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DefaultStartPlayOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = {
                            expanded = false
                            onOrderSelected(order)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    infoText: String? = null,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toIntOrNull()?.let(onValueChanged)
        },
        label = { SettingLabel(label, infoText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun DecimalSettingField(
    label: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    infoText: String? = null,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { newValue ->
            newValue.toFloatOrNull()?.let(onValueChanged)
        },
        label = { SettingLabel(label, infoText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun SwitchSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    infoText: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabel(label, infoText)
        Switch(checked = checked, onCheckedChange = onCheckedChanged)
    }
}

@Composable
private fun SettingLabel(label: String, infoText: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        if (infoText != null) {
            InfoPopupButton(label, infoText)
        }
    }
}

@Composable
private fun InfoPopupButton(title: String, text: String) {
    var isOpen by remember { mutableStateOf(false) }

    IconButton(
        onClick = { isOpen = true },
        modifier = Modifier.size(24.dp),
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = "$title info",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
    }
    if (isOpen) {
        AlertDialog(
            onDismissRequest = { isOpen = false },
            confirmButton = {
                TextButton(onClick = { isOpen = false }) {
                    Text("OK")
                }
            },
            title = { Text(title) },
            text = { Text(text) },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    GraviMusicPlayerTheme {
        SettingsScreen(
            rootUriString = null,
            defaultStartPlayOrder = DefaultStartPlayOrder.ORDERED,
            genreSeparator = ";",
            showBrowserThumbnails = true,
            queueSearchResults = true,
            skipSilenceEnabled = true,
            graviPickerSettings = GraviPickerSettings(),
            onChooseFolder = {},
            onDefaultStartPlayOrderChanged = {},
            onGenreSeparatorChanged = {},
            onApplyGenreSeparator = {},
            onShowBrowserThumbnailsChanged = {},
            onQueueSearchResultsChanged = {},
            onSkipSilenceEnabledChanged = {},
            onGraviPickerSettingsChanged = {},
            onResetSettings = {},
            onClearCaches = {},
            onClearPerformanceData = {},
            onExportPerformanceData = {},
        )
    }
}

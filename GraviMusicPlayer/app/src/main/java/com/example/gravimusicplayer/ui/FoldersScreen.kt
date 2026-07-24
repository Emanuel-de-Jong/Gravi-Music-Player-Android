package com.example.gravimusicplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.gravimusicplayer.BrowserEntry
import com.example.gravimusicplayer.BrowserSortMode
import com.example.gravimusicplayer.LibraryRepository
import com.example.gravimusicplayer.formatTrackCount
import com.example.gravimusicplayer.ui.theme.GraviMusicPlayerTheme

@Composable
fun FoldersScreen(
    rootUriString: String?,
    folderStack: List<String>,
    entries: List<BrowserEntry>,
    currentTrackCount: Int,
    searchQuery: String,
    sortMode: BrowserSortMode,
    sortAscending: Boolean,
    showThumbnails: Boolean,
    isFolderActionRunning: Boolean,
    onChooseFolder: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSortModeChanged: (BrowserSortMode) -> Unit,
    onToggleSortDirection: () -> Unit,
    onOpenFolder: (BrowserEntry) -> Unit,
    onBack: () -> Unit,
    onPlayFolder: () -> Unit,
    onShuffleFolder: () -> Unit,
    onGraviShuffleFolder: () -> Unit,
    onExportFolder: () -> Unit,
    onPlayFile: (BrowserEntry) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(sortMode, sortAscending) {
        listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Folders",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (rootUriString == null) {
            Text("Choose your music folder to start browsing local audio files.")
            Button(onClick = onChooseFolder) { Text("Choose music folder") }
            return@Column
        }

        Text(
            text = formatTrackCount(currentTrackCount),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.weight(1f),
            )
            BrowserSortModeSelector(sortMode, onSortModeChanged)
            SortDirectionButton(sortAscending, onToggleSortDirection)
        }
        val hasPlayableFolderContent = entries.isNotEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                enabled = folderStack.isNotEmpty(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            CompactFolderButton(
                "Ordered",
                hasPlayableFolderContent && !isFolderActionRunning,
                onPlayFolder
            )
            CompactFolderButton(
                "Shuffled",
                hasPlayableFolderContent && !isFolderActionRunning,
                onShuffleFolder
            )
            CompactFolderButton(
                "Gravi",
                hasPlayableFolderContent && !isFolderActionRunning,
                onGraviShuffleFolder
            )
            CompactFolderButton(
                "Export",
                hasPlayableFolderContent && !isFolderActionRunning,
                onExportFolder
            )
        }
        if (isFolderActionRunning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Preparing folder…")
            }
        }
        if (entries.isEmpty()) {
            Text("No folders or supported audio files found here. Supported extensions: ${LibraryRepository.audioExtensions.joinToString()}.")
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(entries, key = { it.uriString }) { entry ->
                BrowserEntryRow(
                    entry = entry,
                    showThumbnails = showThumbnails,
                    onClick = {
                        if (entry.isDirectory) onOpenFolder(entry) else onPlayFile(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactFolderButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BrowserSortModeSelector(
    sortMode: BrowserSortMode,
    onSortModeChanged: (BrowserSortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Button(onClick = { expanded = true }) {
            Text(sortMode.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BrowserSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onSortModeChanged(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun BrowserEntryRow(entry: BrowserEntry, showThumbnails: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.isDirectory) {
                Icon(Icons.Filled.Folder, contentDescription = null)
            } else if (showThumbnails) {
                BrowserThumbnail(entry.audioItem?.artworkUriString)
            }
            Text(
                entry.name,
                modifier = Modifier.weight(1f),
            )
            if (entry.trackCount != null) {
                Text(
                    formatTrackCount(entry.trackCount),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BrowserThumbnail(artworkUriString: String?) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, contentDescription = null)
        AsyncImage(
            model = artworkUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun FoldersScreenPreview() {
    GraviMusicPlayerTheme {
        FoldersScreen(
            rootUriString = null,
            folderStack = emptyList(),
            entries = emptyList(),
            currentTrackCount = 0,
            searchQuery = "",
            sortMode = BrowserSortMode.FILENAME,
            sortAscending = true,
            showThumbnails = false,
            isFolderActionRunning = false,
            onChooseFolder = {},
            onSearchQueryChanged = {},
            onSortModeChanged = {},
            onToggleSortDirection = {},
            onOpenFolder = {},
            onBack = {},
            onPlayFolder = {},
            onShuffleFolder = {},
            onGraviShuffleFolder = {},
            onExportFolder = {},
            onPlayFile = {},
        )
    }
}

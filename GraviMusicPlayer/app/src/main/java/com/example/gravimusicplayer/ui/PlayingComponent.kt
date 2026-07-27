package com.example.gravimusicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.gravimusicplayer.AudioItem
import com.example.gravimusicplayer.LoopMode
import com.example.gravimusicplayer.PlaybackSnapshot
import com.example.gravimusicplayer.formatTime
import com.example.gravimusicplayer.queueContext
import com.example.gravimusicplayer.queueDisplayTitle
import com.example.gravimusicplayer.ui.theme.GraviMusicPlayerTheme

@Composable
fun MiniPlayer(
    snapshot: PlaybackSnapshot,
    showArtwork: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val item = snapshot.currentItem ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onExpand),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                artworkUriString = item.artworkUriString.takeIf { showArtwork },
                modifier = Modifier.size(40.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    snapshot.queueDisplayTitle(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
fun PlayScreen(
    snapshot: PlaybackSnapshot,
    expanded: Boolean,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onShuffleQueue: () -> Unit,
    showThumbnails: Boolean,
    waveformValues: List<Float>,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    val item = snapshot.currentItem

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expanded, onClick = onCollapse),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse, enabled = expanded) {
                Icon(Icons.Filled.ExpandLess, contentDescription = "Collapse player")
            }
            Text(
                "Now playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        ArtworkCard(item?.artworkUriString)
        Text(
            text = item?.displayTitle ?: "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = snapshot.queueDisplayTitle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (waveformValues.isEmpty()) {
            Slider(
                value = snapshot.positionMs.toFloat()
                    .coerceIn(0f, snapshot.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..snapshot.durationMs.toFloat().coerceAtLeast(1f),
                enabled = snapshot.durationMs > 0,
            )
        } else {
            WaveformSlider(
                values = waveformValues,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                onSeek = onSeek,
                enabled = snapshot.durationMs > 0,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(snapshot.positionMs), style = MaterialTheme.typography.bodySmall)
            Text(
                text = snapshot.audioInfoText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(formatTime(snapshot.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = item != null) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(
                onClick = onPlayPause,
                enabled = item != null,
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(44.dp),
                )
            }
            IconButton(onClick = onNext, enabled = item != null) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
        PlaybackModeRow(
            snapshot = snapshot,
            onShuffleQueue = onShuffleQueue,
            onLoopModeChanged = onLoopModeChanged,
        )
        if (snapshot.errorMessage != null) {
            Text(snapshot.errorMessage, color = MaterialTheme.colorScheme.error)
        }
        NowPlayingTabs(
            snapshot = snapshot,
            onPlayQueueIndex = onPlayQueueIndex,
            onRemoveQueueItem = onRemoveQueueItem,
            showThumbnails = showThumbnails,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WaveformSlider(
    values: List<Float>,
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
    enabled: Boolean,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.surfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val playedFraction = if (durationMs > 0) {
        positionMs.toFloat() / durationMs.toFloat()
    } else {
        0f
    }.coerceIn(0f, 1f)
    val seekFromX = { x: Float ->
        if (enabled && durationMs > 0) {
            onSeek((x.coerceIn(0f, widthPx.toFloat()) / widthPx * durationMs).toInt())
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { size ->
                widthPx = size.width.coerceAtLeast(1)
            }
            .pointerInput(enabled, durationMs, widthPx) {
                detectTapGestures { offset -> seekFromX(offset.x) }
            }
            .pointerInput(enabled, durationMs, widthPx) {
                detectDragGestures { change, _ -> seekFromX(change.position.x) }
            }
    ) {
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.9f
        val barStep = size.width / values.size.coerceAtLeast(1)
        val strokeWidth = (barStep * 0.7f).coerceIn(1f, 4f)
        values.forEachIndexed { index, value ->
            val x = index * barStep + barStep / 2f
            val barHeight = (value.coerceIn(0f, 1f) * maxBarHeight).coerceAtLeast(2f)
            val color =
                if (index.toFloat() / values.size <= playedFraction) primaryColor else mutedColor
            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        val cursorX = size.width * playedFraction
        drawLine(
            color = cursorColor,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, size.height),
            strokeWidth = 2f,
        )
    }
}

private enum class NowPlayingTab(
    val title: String,
) {
    QUEUE("Queue"),
    LYRICS("Lyrics"),
}

@Composable
private fun NowPlayingTabs(
    snapshot: PlaybackSnapshot,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    showThumbnails: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(NowPlayingTab.QUEUE) }

    Column(modifier = modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = NowPlayingTab.entries.indexOf(selectedTab)) {
            NowPlayingTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            if (tab == NowPlayingTab.QUEUE) {
                                "${tab.title} (${snapshot.queue.size} tracks)"
                            } else {
                                tab.title
                            }
                        )
                    },
                )
            }
        }
        when (selectedTab) {
            NowPlayingTab.QUEUE -> QueueList(
                snapshot = snapshot,
                onPlayQueueIndex = onPlayQueueIndex,
                onRemoveQueueItem = onRemoveQueueItem,
                showThumbnails = showThumbnails,
                modifier = Modifier.weight(1f),
            )

            NowPlayingTab.LYRICS -> LyricsView(
                audioItem = snapshot.currentItem,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ArtworkCard(artworkUriString: String?) {
    Card(
        modifier = Modifier.size(180.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        ArtworkImage(
            artworkUriString = artworkUriString,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ArtworkImage(artworkUriString: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, contentDescription = null)
        AsyncImage(
            model = artworkUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlaybackModeRow(
    snapshot: PlaybackSnapshot,
    onShuffleQueue: () -> Unit,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShuffleQueueButton(
            enabled = snapshot.queue.size > 1,
            onShuffleQueue = onShuffleQueue,
        )
        LoopModeSelector(snapshot.loopMode, onLoopModeChanged)
    }
}

@Composable
private fun ShuffleQueueButton(
    enabled: Boolean,
    onShuffleQueue: () -> Unit,
) {
    TextButton(onClick = onShuffleQueue, enabled = enabled) {
        Icon(Icons.Filled.Shuffle, contentDescription = null)
        Text("Shuffle")
    }
}

@Composable
private fun LoopModeSelector(
    loopMode: LoopMode,
    onLoopModeChanged: (LoopMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                if (loopMode == LoopMode.SONG) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = null,
            )
            Text(loopMode.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LoopMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onLoopModeChanged(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueList(
    snapshot: PlaybackSnapshot,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    showThumbnails: Boolean,
    modifier: Modifier = Modifier,
) {
    var queueSearchQuery by rememberSaveable { mutableStateOf("") }
    var appliedQueueSearchQuery by rememberSaveable { mutableStateOf("") }
    var lastQueueIndex by rememberSaveable { mutableIntStateOf(snapshot.currentIndex) }
    val listState = rememberLazyListState()
    val displayedQueueItems = snapshot.queue.withIndex()
        .filter { queueItem ->
            appliedQueueSearchQuery.isBlank() || queueItem.value.matchesQueueSearch(
                appliedQueueSearchQuery
            )
        }

    LaunchedEffect(appliedQueueSearchQuery) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(snapshot.currentIndex, snapshot.queue, appliedQueueSearchQuery) {
        val currentIndex = snapshot.currentIndex
        val hasQueueIndexChanged = currentIndex != lastQueueIndex
        lastQueueIndex = currentIndex
        if (!hasQueueIndexChanged) return@LaunchedEffect
        if (currentIndex < 0 || appliedQueueSearchQuery.isNotBlank() || listState.isScrollInProgress) {
            return@LaunchedEffect
        }

        val rowIndex = displayedQueueItems.indexOfFirst { it.index == currentIndex }
        if (rowIndex < 0) return@LaunchedEffect

        val currentItemIsVisible =
            listState.layoutInfo.visibleItemsInfo.any { it.index == rowIndex }
        if (!currentItemIsVisible) {
            listState.animateScrollToItem(rowIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (snapshot.queue.isEmpty()) {
            Text("Queue is empty. Start playback from a folder, file, or genre to populate it.")
        }
        SearchTextField(
            value = queueSearchQuery,
            onValueChange = { query ->
                queueSearchQuery = query
                if (query.isBlank()) appliedQueueSearchQuery = ""
            },
            onSearch = { appliedQueueSearchQuery = queueSearchQuery },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                displayedQueueItems,
                key = { "${it.index}-${it.value.uriString}" }) { indexedQueueItem ->
                val index = indexedQueueItem.index
                val queueItem = indexedQueueItem.value
                val isCurrentItem = index == snapshot.currentIndex
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayQueueIndex(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentItem) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 10.dp,
                            top = 10.dp,
                            end = 2.dp,
                            bottom = 10.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showThumbnails) {
                            ArtworkImage(
                                artworkUriString = queueItem.artworkUriString,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isCurrentItem) "▶ ${queueItem.displayTitle}" else queueItem.displayTitle,
                                fontWeight = if (isCurrentItem) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                text = queueItem.queueContext(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveQueueItem(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove from queue",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun AudioItem.matchesQueueSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return true

    return listOf(displayTitle, metadataTitle.orEmpty(), folderPath)
        .any { it.contains(normalizedQuery, ignoreCase = true) }
}

@Composable
private fun LyricsView(
    audioItem: AudioItem?,
    modifier: Modifier = Modifier,
) {
    val lyrics = audioItem?.lyrics?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                text = lyrics ?: if (audioItem == null) {
                    "No song is playing."
                } else {
                    "No lyrics found for this song."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PlayScreenPreview() {
    GraviMusicPlayerTheme {
        PlayScreen(
            snapshot = PlaybackSnapshot(),
            expanded = true,
            onCollapse = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onSeek = {},
            onPlayQueueIndex = {},
            onRemoveQueueItem = {},
            onShuffleQueue = {},
            showThumbnails = false,
            waveformValues = emptyList(),
            onLoopModeChanged = {},
        )
    }
}

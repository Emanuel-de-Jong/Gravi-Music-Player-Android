package com.example.gravimusicplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.gravimusicplayer.AudioItem
import com.example.gravimusicplayer.BrowserEntry
import com.example.gravimusicplayer.BrowserSortMode
import com.example.gravimusicplayer.DefaultStartPlayOrder
import com.example.gravimusicplayer.GraviQueuePicker
import com.example.gravimusicplayer.LibraryRepository
import com.example.gravimusicplayer.PendingPlaybackRequest
import com.example.gravimusicplayer.PlaybackService
import com.example.gravimusicplayer.PlaybackSnapshot
import com.example.gravimusicplayer.PlayerPreferences
import com.example.gravimusicplayer.QueueOrder
import com.example.gravimusicplayer.QueueType
import com.example.gravimusicplayer.TagGroup
import com.example.gravimusicplayer.WaveformAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@PreviewScreenSizes
@Composable
fun GraviMusicPlayerApp() {
    val context = LocalContext.current
    val libraryRepository = remember(context) { LibraryRepository(context) }
    val waveformAnalyzer = remember(context) { WaveformAnalyzer(context) }
    val graviQueuePicker = remember { GraviQueuePicker() }
    val preferences = remember(context) { PlayerPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FOLDERS) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var rootUriString by rememberSaveable { mutableStateOf(preferences.rootUriString) }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var folderSearchQuery by rememberSaveable { mutableStateOf("") }
    var genreSearchQuery by rememberSaveable { mutableStateOf("") }
    var browserSortMode by rememberSaveable { mutableStateOf(BrowserSortMode.FILENAME) }
    var browserSortAscending by rememberSaveable { mutableStateOf(true) }
    var genreSortAscending by rememberSaveable { mutableStateOf(true) }
    var browserEntries by remember { mutableStateOf(emptyList<BrowserEntry>()) }
    var tagGroups by remember { mutableStateOf(emptyList<TagGroup>()) }
    var isGeneratingCache by remember { mutableStateOf(preferences.rootUriString != null) }
    var libraryCacheVersion by remember { mutableIntStateOf(0) }
    var cacheGenerationRequest by remember { mutableIntStateOf(0) }
    var pendingPlaybackRequest by remember { mutableStateOf<PendingPlaybackRequest?>(null) }
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var defaultStartPlayOrder by rememberSaveable {
        mutableStateOf(preferences.defaultStartPlayOrder)
    }
    var savedLoopMode by rememberSaveable { mutableStateOf(preferences.loopMode) }
    var genreSeparator by rememberSaveable { mutableStateOf(preferences.genreSeparator) }
    var appliedGenreSeparator by rememberSaveable { mutableStateOf(preferences.genreSeparator) }
    var showBrowserThumbnails by rememberSaveable { mutableStateOf(preferences.showBrowserThumbnails) }
    var queueSearchResults by rememberSaveable { mutableStateOf(preferences.queueSearchResults) }
    var skipSilenceEnabled by rememberSaveable { mutableStateOf(preferences.skipSilenceEnabled) }
    var graviPickerSettings by remember { mutableStateOf(preferences.graviPickerSettings) }
    var pendingPlaylistExport by remember { mutableStateOf<List<AudioItem>?>(null) }
    var isFolderActionRunning by remember { mutableStateOf(false) }
    var mediaLibraryPermissionVersion by remember { mutableIntStateOf(0) }
    var waveformUriString by remember { mutableStateOf<String?>(null) }
    var waveformValues by remember { mutableStateOf(emptyList<Float>()) }
    var waveformRequestId by remember { mutableIntStateOf(0) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                rootUriString = uri.toString()
                preferences.rootUriString = rootUriString
                folderStack = emptyList()
                folderSearchQuery = ""
                genreSearchQuery = ""
                browserEntries = emptyList()
                tagGroups = emptyList()
                libraryRepository.clearSessionCache()
                cacheGenerationRequest++
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mediaLibraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            libraryRepository.clearSessionCache()
            mediaLibraryPermissionVersion++
        }
    val playlistExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val exportItems = pendingPlaylistExport
            pendingPlaylistExport = null
            if (uri != null && exportItems != null) {
                writeM3u8Playlist(context, uri, exportItems)
            }
        }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val boundService = (service as PlaybackService.PlaybackBinder).getService()
                playbackService = boundService
                boundService.setLoopMode(savedLoopMode)
                boundService.setSkipSilenceEnabled(skipSilenceEnabled)
                playbackSnapshot = boundService.getSnapshot()
                boundService.setListener { playbackSnapshot = it }
                pendingPlaybackRequest?.let {
                    boundService.playQueue(
                        it.queue,
                        it.startIndex,
                        it.queueType,
                        it.queueName,
                        it.queueOrder,
                    )
                    pendingPlaybackRequest = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService?.setListener(null)
                playbackService = null
            }
        }
        context.bindService(
            Intent(context, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            playbackService?.setListener(null)
            context.unbindService(connection)
        }
    }

    LaunchedEffect(
        rootUriString,
        mediaLibraryPermissionVersion,
        appliedGenreSeparator,
        cacheGenerationRequest
    ) {
        val rootUri = rootUriString
        if (rootUri == null) {
            isGeneratingCache = false
            browserEntries = emptyList()
            tagGroups = emptyList()
        } else {
            isGeneratingCache = true
            tagGroups = withContext(Dispatchers.IO) {
                if (!libraryRepository.hasCompleteCache(rootUri, appliedGenreSeparator)) {
                    libraryRepository.generateAllCaches(rootUri, appliedGenreSeparator)
                }
                libraryRepository.buildTagGroups(
                    libraryRepository.loadCachedGenreAudioItems(rootUri, appliedGenreSeparator)
                )
            }
            libraryCacheVersion++
            isGeneratingCache = false
        }
    }

    LaunchedEffect(
        rootUriString,
        folderStack,
        folderSearchQuery,
        libraryCacheVersion,
        isGeneratingCache
    ) {
        val rootUri = rootUriString
        browserEntries =
            if (rootUri == null || isGeneratingCache) emptyList() else withContext(Dispatchers.IO) {
                if (folderSearchQuery.isBlank()) {
                    libraryRepository.loadBrowserEntries(rootUri, folderStack)
                } else {
                    libraryRepository.searchBrowserEntries(rootUri, folderSearchQuery)
                }
            }
    }

    LaunchedEffect(rootUriString) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        rootUriString ?: return@LaunchedEffect
        val mediaLibraryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(mediaLibraryPermission) != PackageManager.PERMISSION_GRANTED) {
            mediaLibraryPermissionLauncher.launch(mediaLibraryPermission)
        }
    }

    LaunchedEffect(
        playbackSnapshot.currentItem?.uriString,
        playbackSnapshot.currentItem?.lastModifiedMs,
        playbackSnapshot.currentItem?.sizeBytes,
    ) {
        val currentItem = playbackSnapshot.currentItem
        waveformUriString = currentItem?.uriString
        waveformValues = emptyList()
        if (currentItem != null) {
            val requestUriString = currentItem.uriString
            val requestId = ++waveformRequestId
            val values = withContext(Dispatchers.IO) {
                val coroutineContext = currentCoroutineContext()
                waveformAnalyzer.waveform(currentItem) {
                    !coroutineContext.isActive
                }
            }
            if (waveformUriString == requestUriString && waveformRequestId == requestId) {
                waveformValues = values
            }
        }
    }

    AppScaffold(
        currentDestination = currentDestination,
        isGeneratingCache = isGeneratingCache,
        onDestinationSelected = { destination ->
            isPlayerExpanded = false
            currentDestination = destination
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isGeneratingCache) {
                LibraryLoadingScreen()
            } else if (isPlayerExpanded) {
                PlayScreen(
                    snapshot = playbackSnapshot,
                    expanded = true,
                    onCollapse = { isPlayerExpanded = false },
                    onPlayPause = { playbackService?.togglePlayPause() },
                    onNext = { playbackService?.playNext() },
                    onPrevious = { playbackService?.playPrevious() },
                    onSeek = { playbackService?.seekTo(it) },
                    onPlayQueueIndex = { playbackService?.playQueueIndex(it) },
                    onShuffleQueue = { playbackService?.shuffleQueue() },
                    showThumbnails = showBrowserThumbnails,
                    waveformValues = waveformValues.takeIf {
                        waveformUriString == playbackSnapshot.currentItem?.uriString
                    }.orEmpty(),
                    onLoopModeChanged = {
                        savedLoopMode = it
                        preferences.loopMode = it
                        playbackService?.setLoopMode(it)
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        val sortedBrowserEntries = sortBrowserEntries(
                            browserEntries,
                            browserSortMode,
                            browserSortAscending,
                        )
                        val filteredTagGroups = tagGroups.filter {
                            genreSearchQuery.isBlank() || it.name.contains(
                                genreSearchQuery,
                                ignoreCase = true
                            )
                        }.let { groups ->
                            if (genreSortAscending) {
                                groups.sortedBy { it.name.lowercase() }
                            } else {
                                groups.sortedByDescending { it.name.lowercase() }
                            }
                        }
                        when (currentDestination) {
                            AppDestinations.FOLDERS -> FoldersScreen(
                                rootUriString = rootUriString,
                                folderStack = folderStack,
                                entries = sortedBrowserEntries,
                                currentTrackCount = sortedBrowserEntries.sumOf {
                                    it.trackCount ?: if (it.audioItem != null) 1 else 0
                                },
                                searchQuery = folderSearchQuery,
                                sortMode = browserSortMode,
                                sortAscending = browserSortAscending,
                                showThumbnails = showBrowserThumbnails,
                                isFolderActionRunning = isFolderActionRunning,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { folderSearchQuery = it },
                                onSortModeChanged = { browserSortMode = it },
                                onToggleSortDirection = {
                                    browserSortAscending = !browserSortAscending
                                },
                                onOpenFolder = {
                                    folderStack =
                                        it.uriString.split('/').filter { part -> part.isNotBlank() }
                                    folderSearchQuery = ""
                                },
                                onBack = { folderStack = folderStack.dropLast(1) },
                                onPlayFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.ORDERED,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.shuffleQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    )
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.SHUFFLED,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onGraviShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    val selectedGraviPickerSettings = graviPickerSettings
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.buildGraviQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    ),
                                                    selectedGraviPickerSettings,
                                                    selectedFolderStack.joinToString("/"),
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.GRAVI_SHUFFLED,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onExportFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val exportItems = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            pendingPlaylistExport = exportItems
                                            playlistExporter.launch(
                                                playlistFileName(
                                                    selectedFolderStack
                                                )
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onPlayFile = { entry ->
                                    val selectedItem = entry.audioItem ?: return@FoldersScreen
                                    val selectedRootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = selectedItem.folderPath.split('/')
                                        .filter { it.isNotBlank() }
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue =
                                                if (folderSearchQuery.isBlank() || queueSearchResults) {
                                                    sortedBrowserEntries.mapNotNull { it.audioItem }
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        libraryRepository.loadRecursiveAudioItems(
                                                            selectedRootUri,
                                                            selectedFolderStack,
                                                        )
                                                    }
                                                }
                                            val startIndex =
                                                queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                                    .coerceAtLeast(0)
                                            val queueName =
                                                if (folderSearchQuery.isBlank() || queueSearchResults) {
                                                    folderQueueName(folderStack)
                                                } else {
                                                    folderQueueName(selectedFolderStack)
                                                }
                                            val playbackQueue = if (
                                                defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED
                                            ) {
                                                queue.shuffledWithCurrentItemFirst(selectedItem)
                                            } else {
                                                queue
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                playbackQueue,
                                                if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) 0 else startIndex,
                                                QueueType.FOLDER,
                                                queueName,
                                                if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) {
                                                    QueueOrder.SHUFFLED
                                                } else {
                                                    QueueOrder.ORDERED
                                                },
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                            )

                            AppDestinations.GENRES -> GenresScreen(
                                rootUriString = rootUriString,
                                tagGroups = filteredTagGroups,
                                searchQuery = genreSearchQuery,
                                sortAscending = genreSortAscending,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { genreSearchQuery = it },
                                onToggleSortDirection = {
                                    genreSortAscending = !genreSortAscending
                                },
                                onPlayTag = { tagGroup ->
                                    val playbackQueue = if (
                                        defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED
                                    ) {
                                        graviQueuePicker.shuffleQueue(tagGroup.items)
                                    } else {
                                        tagGroup.items
                                    }
                                    pendingPlaybackRequest = requestPlayback(
                                        context,
                                        playbackService,
                                        playbackQueue,
                                        0,
                                        QueueType.GENRE,
                                        tagGroup.name,
                                        if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) {
                                            QueueOrder.SHUFFLED
                                        } else {
                                            QueueOrder.ORDERED
                                        },
                                    )
                                },
                            )

                            AppDestinations.SETTINGS -> SettingsScreen(
                                rootUriString = rootUriString,
                                defaultStartPlayOrder = defaultStartPlayOrder,
                                genreSeparator = genreSeparator,
                                showBrowserThumbnails = showBrowserThumbnails,
                                queueSearchResults = queueSearchResults,
                                skipSilenceEnabled = skipSilenceEnabled,
                                graviPickerSettings = graviPickerSettings,
                                onChooseFolder = { folderPicker.launch(null) },
                                onDefaultStartPlayOrderChanged = {
                                    defaultStartPlayOrder = it
                                    preferences.defaultStartPlayOrder = it
                                },
                                onGenreSeparatorChanged = {
                                    genreSeparator = it
                                },
                                onApplyGenreSeparator = {
                                    genreSeparator = it
                                    appliedGenreSeparator = it.ifBlank { ";" }
                                    preferences.genreSeparator = it
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onShowBrowserThumbnailsChanged = {
                                    showBrowserThumbnails = it
                                    preferences.showBrowserThumbnails = it
                                },
                                onQueueSearchResultsChanged = {
                                    queueSearchResults = it
                                    preferences.queueSearchResults = it
                                },
                                onSkipSilenceEnabledChanged = {
                                    skipSilenceEnabled = it
                                    preferences.skipSilenceEnabled = it
                                    playbackService?.setSkipSilenceEnabled(it)
                                },
                                onGraviPickerSettingsChanged = {
                                    graviPickerSettings = it
                                    preferences.graviPickerSettings = it
                                },
                                onResetSettings = {
                                    preferences.resetSettingsExceptRootUri()
                                    defaultStartPlayOrder = preferences.defaultStartPlayOrder
                                    savedLoopMode = preferences.loopMode
                                    genreSeparator = preferences.genreSeparator
                                    appliedGenreSeparator = preferences.genreSeparator
                                    showBrowserThumbnails = preferences.showBrowserThumbnails
                                    queueSearchResults = preferences.queueSearchResults
                                    skipSilenceEnabled = preferences.skipSilenceEnabled
                                    graviPickerSettings = preferences.graviPickerSettings
                                    playbackService?.setLoopMode(savedLoopMode)
                                    playbackService?.setSkipSilenceEnabled(skipSilenceEnabled)
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onClearCaches = {
                                    libraryRepository.clearAllCaches(rootUriString)
                                    waveformAnalyzer.clearCache()
                                    waveformValues = emptyList()
                                    browserEntries = emptyList()
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                            )
                        }
                    }
                    MiniPlayer(
                        snapshot = playbackSnapshot,
                        showArtwork = showBrowserThumbnails,
                        onExpand = { isPlayerExpanded = true },
                        onPlayPause = { playbackService?.togglePlayPause() },
                        onNext = { playbackService?.playNext() },
                    )
                }
            }
        }
    }
}

private fun sortBrowserEntries(
    entries: List<BrowserEntry>,
    sortMode: BrowserSortMode,
    sortAscending: Boolean,
): List<BrowserEntry> {
    val folders = entries.filter { it.isDirectory }
    val files = entries.filter { !it.isDirectory }
    return sortBrowserEntryGroup(folders, sortMode, sortAscending)
        .plus(sortBrowserEntryGroup(files, sortMode, sortAscending))
}

private fun sortBrowserEntryGroup(
    entries: List<BrowserEntry>,
    sortMode: BrowserSortMode,
    sortAscending: Boolean,
): List<BrowserEntry> {
    val comparator = compareBy<BrowserEntry> { browserSortKey(it, sortMode) }
        .thenBy { it.name.lowercase() }
    return if (sortAscending) entries.sortedWith(comparator) else entries.sortedWith(comparator.reversed())
}

private fun browserSortKey(entry: BrowserEntry, sortMode: BrowserSortMode): Comparable<*> {
    val item = entry.audioItem
    return when (sortMode) {
        BrowserSortMode.FILENAME -> item?.displayTitle?.lowercase() ?: entry.name.lowercase()
        BrowserSortMode.ARTIST -> item?.artist?.lowercase() ?: item?.displayTitle?.lowercase()
        ?: entry.name.lowercase()

        BrowserSortMode.TITLE -> item?.metadataTitle?.lowercase() ?: item?.displayTitle?.lowercase()
        ?: entry.name.lowercase()

        BrowserSortMode.DURATION -> item?.durationMs ?: 0L
        BrowserSortMode.RELEASE_DATE -> item?.releaseDate.orEmpty().lowercase()
        BrowserSortMode.ADDITION_DATE -> item?.lastModifiedMs ?: 0L
    }
}

private fun folderQueueName(folderStack: List<String>): String {
    return if (folderStack.isEmpty()) "Root" else folderStack.joinToString(" / ")
}

private fun playlistFileName(folderStack: List<String>): String {
    val folderName = if (folderStack.isEmpty()) "Root music folder" else folderStack.last()
    val safeFolderName = folderName
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .ifBlank { "playlist" }
    return "$safeFolderName.m3u8"
}

private fun writeM3u8Playlist(context: Context, uri: Uri, items: List<AudioItem>) {
    val playlistContent = buildM3u8Playlist(items)
    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
        outputStream.write(playlistContent.toByteArray(Charsets.UTF_8))
    }
}

private fun buildM3u8Playlist(items: List<AudioItem>): String {
    return buildString {
        appendLine("#EXTM3U")
        items.forEach { item ->
            appendLine(item.uriString)
        }
    }
}

private fun requestPlayback(
    context: Context,
    playbackService: PlaybackService?,
    queue: List<AudioItem>,
    startIndex: Int,
    queueType: QueueType,
    queueName: String,
    queueOrder: QueueOrder,
): PendingPlaybackRequest? {
    PlaybackService.start(context)
    playbackService?.playQueue(queue, startIndex, queueType, queueName, queueOrder)
    return if (playbackService == null) PendingPlaybackRequest(
        queue,
        startIndex,
        queueType,
        queueName,
        queueOrder,
    ) else null
}

private fun List<AudioItem>.shuffledWithCurrentItemFirst(currentItem: AudioItem): List<AudioItem> {
    val remainingItems = filter { it.uriString != currentItem.uriString }.shuffled()
    return listOf(currentItem) + remainingItems
}